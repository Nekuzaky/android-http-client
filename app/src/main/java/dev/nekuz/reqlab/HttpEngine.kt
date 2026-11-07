package dev.nekuz.reqlab

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class FailureKind { TLS, HOST_NOT_FOUND, CONNECT, TIMEOUT, PROTOCOL, NETWORK, INTERNAL }

sealed class Outcome {
    class Done(val response: RawResponse) : Outcome()

    data class Failed(val kind: FailureKind, val message: String) : Outcome()

    object Cancelled : Outcome()

    object DeadlineExceeded : Outcome()
}

class CancelToken {
    private val cancelled = AtomicBoolean(false)
    private val deadlineHit = AtomicBoolean(false)
    private val current = AtomicReference<HttpURLConnection?>(null)

    @Volatile
    private var headerDeadlineNanos = 0L

    @Volatile
    private var headerBaselineHeap = 0L

    fun enterHeaderPhase(deadlineNanos: Long) {
        headerBaselineHeap = usedHeap()
        headerDeadlineNanos = deadlineNanos
    }

    fun leaveHeaderPhase() {
        headerDeadlineNanos = 0L
    }

    fun headerPhaseOverrun(now: Long, maxHeapGrowth: Long): Boolean {
        val deadline = headerDeadlineNanos
        if (deadline == 0L) return false
        return now - deadline > 0 || usedHeap() - headerBaselineHeap > maxHeapGrowth
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    val isCancelled: Boolean
        get() = cancelled.get()

    val isDeadline: Boolean
        get() = deadlineHit.get()

    fun attach(connection: HttpURLConnection) {
        current.set(connection)
        if (cancelled.get()) quietDisconnect(connection)
    }

    fun detach() {
        current.set(null)
    }

    fun cancel() {
        cancelled.set(true)
        current.get()?.let(::quietDisconnect)
    }

    fun expire() {
        deadlineHit.set(true)
        cancel()
    }
}

private fun quietDisconnect(connection: HttpURLConnection) {
    try {
        connection.disconnect()
    } catch (e: RuntimeException) {
        return
    }
}

data class Timeouts(
    val connectMs: Int = Limits.CONNECT_TIMEOUT_MS,
    val readMs: Int = Limits.READ_TIMEOUT_MS,
    val totalMs: Long = Limits.TOTAL_TIMEOUT_MS,
    val headerMs: Long = connectMs.toLong() + readMs.toLong(),
    val maxHeaderHeapGrowth: Long = Limits.MAX_HEADER_HEAP_GROWTH,
)

object HttpEngine {
    private const val BUFFER = 8_192
    private const val WATCH_INTERVAL_MS = 50L
    private const val SYNTHETIC_HEADER_PREFIX = "X-Android-"

    private class Stopped : IOException()

    fun execute(
        plan: RequestPlan,
        token: CancelToken,
        timeouts: Timeouts = Timeouts(),
        cap: Int = Limits.RESPONSE_CAP,
    ): Outcome {
        val watchdog = Thread({
            val deadline = System.nanoTime() + timeouts.totalMs * 1_000_000
            try {
                while (true) {
                    val now = System.nanoTime()
                    if (now - deadline > 0 || token.headerPhaseOverrun(now, timeouts.maxHeaderHeapGrowth)) {
                        token.expire()
                        break
                    }
                    Thread.sleep(WATCH_INTERVAL_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "reqlab-deadline")
        watchdog.isDaemon = true
        watchdog.start()
        return try {
            run(plan, token, timeouts, cap)
        } catch (e: IOException) {
            if (token.isCancelled) stoppedOutcome(token) else failure(e)
        } catch (e: RuntimeException) {
            if (token.isCancelled) {
                stoppedOutcome(token)
            } else {
                Outcome.Failed(FailureKind.INTERNAL, "The request could not be sent.")
            }
        } finally {
            watchdog.interrupt()
        }
    }

    private fun stoppedOutcome(token: CancelToken) = if (token.isDeadline) Outcome.DeadlineExceeded else Outcome.Cancelled

    private fun run(plan: RequestPlan, token: CancelToken, timeouts: Timeouts, cap: Int): Outcome {
        val started = System.nanoTime()
        var hop = HopRequest(plan.method, plan.url, plan.headers, plan.body)
        val hops = ArrayList<RedirectHop>()
        val notes = ArrayList<String>()
        while (true) {
            if (token.isCancelled) throw Stopped()
            val connection = open(hop, timeouts)
            token.attach(connection)
            try {
                token.enterHeaderPhase(System.nanoTime() + timeouts.headerMs * 1_000_000)
                send(connection, hop)
                val status = connection.responseCode
                token.leaveHeaderPhase()
                if (status < 0) return Outcome.Failed(FailureKind.PROTOCOL, "The server did not answer with valid HTTP.")
                if (plan.followRedirects && Redirects.isRedirect(status)) {
                    val decision = if (hops.size >= Limits.MAX_REDIRECTS) {
                        RedirectDecision.Stop("Stopped after ${Limits.MAX_REDIRECTS} redirects.")
                    } else {
                        Redirects.next(hop, status, connection.getHeaderField("Location"))
                    }
                    when (decision) {
                        is RedirectDecision.Follow -> {
                            hops.add(RedirectHop(status, hop.url, decision.next.url, decision.droppedHeaders))
                            if (decision.downgraded) notes.add("A redirect moved this request from https to http.")
                            hop = decision.next
                            continue
                        }
                        is RedirectDecision.Stop -> notes.add(decision.reason)
                    }
                }
                val body = readBody(connection, hop.method, status, token, cap)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                return Outcome.Done(
                    RawResponse(
                        status = status,
                        message = connection.responseMessage,
                        headers = responseHeaders(connection),
                        body = body.first,
                        truncated = body.second,
                        elapsedMs = elapsed,
                        finalUrl = hop.url,
                        hops = hops,
                        notes = notes,
                    ),
                )
            } finally {
                token.leaveHeaderPhase()
                token.detach()
                quietDisconnect(connection)
            }
        }
    }

    private fun open(hop: HopRequest, timeouts: Timeouts): HttpURLConnection {
        val url = try {
            URL(hop.url)
        } catch (e: MalformedURLException) {
            throw ProtocolException("bad url")
        }
        if (url.protocol != "http" && url.protocol != "https") throw ProtocolException("bad scheme")
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = timeouts.connectMs
        connection.readTimeout = timeouts.readMs
        connection.requestMethod = hop.method.name
        hop.headers.forEach { connection.addRequestProperty(it.key, it.value) }
        return connection
    }

    private fun send(connection: HttpURLConnection, hop: HopRequest) {
        val body = hop.body
        val required = hop.method == HttpMethod.POST || hop.method == HttpMethod.PUT || hop.method == HttpMethod.PATCH
        val hasBody = body != null && body.isNotEmpty() && hop.method.allowsBody
        if (required || hasBody) {
            connection.doOutput = true
            connection.outputStream.use { stream -> stream.write(body ?: ByteArray(0)) }
        }
    }

    private fun readBody(
        connection: HttpURLConnection,
        method: HttpMethod,
        status: Int,
        token: CancelToken,
        cap: Int,
    ): Pair<ByteArray, Boolean> {
        if (method == HttpMethod.HEAD) return ByteArray(0) to false
        val stream: InputStream? = try {
            if (status >= 400) connection.errorStream else connection.inputStream
        } catch (e: IOException) {
            if (token.isCancelled) throw Stopped()
            null
        }
        if (stream == null) return ByteArray(0) to false
        return stream.use { readCapped(it, cap, token) }
    }

    internal fun readCapped(stream: InputStream, cap: Int, token: CancelToken): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(minOf(cap, BUFFER).coerceAtLeast(16))
        val buffer = ByteArray(BUFFER)
        while (true) {
            if (token.isCancelled) throw Stopped()
            val count = stream.read(buffer)
            if (count < 0) return out.toByteArray() to false
            val room = cap - out.size()
            if (count > room) {
                out.write(buffer, 0, room)
                return out.toByteArray() to true
            }
            out.write(buffer, 0, count)
        }
    }

    private fun responseHeaders(connection: HttpURLConnection): List<Kv> {
        val out = ArrayList<Kv>()
        connection.headerFields.forEach { (name, values) ->
            if (name != null && !name.startsWith(SYNTHETIC_HEADER_PREFIX, ignoreCase = true)) {
                values.forEach { value -> out.add(Kv(name, value ?: "")) }
            }
        }
        return out
    }

    private fun failure(error: IOException): Outcome.Failed = when (error) {
        is SSLHandshakeException -> Outcome.Failed(FailureKind.TLS, "TLS validation failed: the server certificate is not trusted by this phone.")
        is SSLPeerUnverifiedException -> Outcome.Failed(FailureKind.TLS, "TLS validation failed: the certificate does not match this host.")
        is SSLException -> Outcome.Failed(FailureKind.TLS, "The TLS connection failed.")
        is UnknownHostException -> Outcome.Failed(FailureKind.HOST_NOT_FOUND, "The host name could not be resolved.")
        is SocketTimeoutException -> Outcome.Failed(FailureKind.TIMEOUT, "The server did not answer in time.")
        is ConnectException, is NoRouteToHostException -> Outcome.Failed(FailureKind.CONNECT, "Could not connect to the server. Check the address, the port and the network.")
        is ProtocolException -> Outcome.Failed(FailureKind.PROTOCOL, "The server answer broke the HTTP protocol, or the request could not be formed.")
        else -> Outcome.Failed(FailureKind.NETWORK, "The network connection failed.")
    }
}
