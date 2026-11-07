package dev.nekuz.reqlab

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class LoopbackServer(private val handler: (Recorded, Socket) -> Unit) {
    data class Recorded(val requestLine: String, val headers: List<String>, val body: String) {
        val method get() = requestLine.substringBefore(' ')
        val path get() = requestLine.split(' ')[1]

        fun header(name: String) = headers.firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
    }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val received = CopyOnWriteArrayList<Recorded>()
    val port: Int get() = server.localPort
    val base: String get() = "http://127.0.0.1:$port"

    init {
        Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = socket.getInputStream()
                val head = readHead(input)
                val lines = head.split("\r\n").filter { it.isNotEmpty() }
                val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(body, read, length - read)
                    if (count < 0) break
                    read += count
                }
                val record = Recorded(lines.first(), lines.drop(1), String(body))
                received.add(record)
                handler(record, socket)
            }
        } catch (e: IOException) {
            return
        }
    }

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) break
            out.write(value)
            if (out.size() >= 4) {
                val bytes = out.toByteArray()
                if (String(bytes, bytes.size - 4, 4) == "\r\n\r\n") break
            }
        }
        return out.toString()
    }

    fun close() {
        server.close()
    }
}

class HttpEngineTest {
    private val servers = ArrayList<LoopbackServer>()
    private val fast = Timeouts(connectMs = 3_000, readMs = 3_000, totalMs = 20_000)

    private fun server(handler: (LoopbackServer.Recorded, Socket) -> Unit) =
        LoopbackServer(handler).also { servers.add(it) }

    @After
    fun closeAll() {
        servers.forEach { it.close() }
    }

    private fun Socket.respond(status: String, headers: List<String> = emptyList(), body: ByteArray = ByteArray(0)) {
        val head = StringBuilder("HTTP/1.1 $status\r\n")
        headers.forEach { head.append(it).append("\r\n") }
        head.append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        val out = getOutputStream()
        out.write(head.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun plan(spec: RequestSpec, vars: Map<String, String> = emptyMap()) =
        (RequestBuilder.build(spec, vars) as BuildResult.Ready).plan

    private fun run(spec: RequestSpec, cap: Int = Limits.RESPONSE_CAP, timeouts: Timeouts = fast) =
        HttpEngine.execute(plan(spec), CancelToken(), timeouts, cap)

    private fun done(spec: RequestSpec, cap: Int = Limits.RESPONSE_CAP) = (run(spec, cap) as Outcome.Done).response

    @Test
    fun getReturnsStatusHeadersAndBody() {
        val s = server { _, socket -> socket.respond("200 OK", listOf("Content-Type: application/json", "X-Test: 1"), "{\"a\":1}".toByteArray()) }
        val response = done(RequestSpec(url = s.base + "/x?y=1"))
        assertEquals(200, response.status)
        assertEquals("OK", response.message)
        assertEquals("{\"a\":1}", String(response.body))
        assertFalse(response.truncated)
        assertTrue(response.elapsedMs >= 0)
        assertEquals("1", response.headers.first { it.key.equals("X-Test", true) }.value)
        assertEquals("GET /x?y=1 HTTP/1.1", s.received.single().requestLine)
        assertEquals(BodyKind.JSON, ResponseViews.build(response).bodyKind)
    }

    @Test
    fun postSendsBodyAndHeaders() {
        val s = server { _, socket -> socket.respond("201 Created") }
        val response = done(
            RequestSpec(
                method = HttpMethod.POST,
                url = s.base + "/",
                headers = listOf(Kv("X-One", "1"), Kv("X-One", "2")),
                bodyType = BodyType.JSON,
                bodyText = "{\"é\":1}",
                auth = Auth(AuthType.BEARER, token = "tok"),
            ),
        )
        assertEquals(201, response.status)
        val request = s.received.single()
        assertEquals("POST", request.method)
        assertEquals("{\"é\":1}", request.body)
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals("Bearer tok", request.header("Authorization"))
    }

    @Test
    fun putAndDeleteAndOptionsReachTheServer() {
        val s = server { _, socket -> socket.respond("204 No Content") }
        listOf(HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.OPTIONS).forEach { method ->
            assertEquals(204, done(RequestSpec(method = method, url = s.base + "/")).status)
            assertEquals(method.name, s.received.last().method)
        }
    }

    @Test
    fun getNeverTurnsIntoPostWhenBodyIsTyped() {
        val s = server { _, socket -> socket.respond("200 OK") }
        done(RequestSpec(method = HttpMethod.GET, url = s.base + "/", bodyText = "ignored"))
        assertEquals("GET", s.received.single().method)
        assertEquals("", s.received.single().body)
    }

    @Test
    fun headReadsNoBody() {
        val s = server { _, socket ->
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
        val response = done(RequestSpec(method = HttpMethod.HEAD, url = s.base + "/"))
        assertEquals(200, response.status)
        assertEquals(0, response.body.size)
    }

    @Test
    fun patchOnThisJvmFailsCleanlyWithoutCrashing() {
        val s = server { _, socket -> socket.respond("200 OK") }
        val outcome = run(RequestSpec(method = HttpMethod.PATCH, url = s.base + "/", bodyText = "x"))
        assertTrue(outcome is Outcome.Failed || outcome is Outcome.Done)
    }

    @Test
    fun errorStatusBodyIsRead() {
        val s = server { _, socket -> socket.respond("404 Not Found", body = "missing".toByteArray()) }
        val response = done(RequestSpec(url = s.base + "/"))
        assertEquals(404, response.status)
        assertEquals("missing", String(response.body))
    }

    @Test
    fun serverErrorWithEmptyBody() {
        val s = server { _, socket -> socket.respond("500 Boom") }
        val response = done(RequestSpec(url = s.base + "/"))
        assertEquals(500, response.status)
        assertEquals(0, response.body.size)
    }

    @Test
    fun bodyIsCutAtTheCap() {
        val s = server { _, socket -> socket.respond("200 OK", body = ByteArray(100_000) { 'x'.code.toByte() }) }
        val response = done(RequestSpec(url = s.base + "/"), cap = 50_000)
        assertEquals(50_000, response.body.size)
        assertTrue(response.truncated)
    }

    @Test
    fun bodyEqualToCapIsNotMarkedCut() {
        val s = server { _, socket -> socket.respond("200 OK", body = ByteArray(1000)) }
        val response = done(RequestSpec(url = s.base + "/"), cap = 1000)
        assertEquals(1000, response.body.size)
        assertFalse(response.truncated)
    }

    @Test
    fun endlessBodyStopsAtTheCap() {
        val s = server { _, socket ->
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
            val chunk = ByteArray(8192)
            try {
                while (true) out.write(chunk)
            } catch (e: IOException) {
                socket.close()
            }
        }
        val response = done(RequestSpec(url = s.base + "/"), cap = 200_000)
        assertEquals(200_000, response.body.size)
        assertTrue(response.truncated)
    }

    @Test
    fun readCappedHandlesEmptyAndExact() {
        assertEquals(0, HttpEngine.readCapped(ByteArrayInputStream(ByteArray(0)), 10, CancelToken()).first.size)
        val exact = HttpEngine.readCapped(ByteArrayInputStream(ByteArray(10)), 10, CancelToken())
        assertEquals(10, exact.first.size)
        assertFalse(exact.second)
        val over = HttpEngine.readCapped(ByteArrayInputStream(ByteArray(11)), 10, CancelToken())
        assertEquals(10, over.first.size)
        assertTrue(over.second)
    }

    @Test
    fun redirectIsNotFollowedByDefault() {
        val s = server { _, socket -> socket.respond("302 Found", listOf("Location: /next")) }
        val response = done(RequestSpec(url = s.base + "/"))
        assertEquals(302, response.status)
        assertEquals("/next", response.headers.first { it.key.equals("Location", true) }.value)
        assertEquals(1, s.received.size)
        assertTrue(response.hops.isEmpty())
    }

    @Test
    fun sameOriginRedirectKeepsAuth() {
        val s = server { request, socket ->
            if (request.path == "/") socket.respond("302 Found", listOf("Location: /next")) else socket.respond("200 OK", body = "done".toByteArray())
        }
        val response = done(RequestSpec(url = s.base + "/", followRedirects = true, auth = Auth(AuthType.BEARER, token = "tok")))
        assertEquals(200, response.status)
        assertEquals("done", String(response.body))
        assertEquals(1, response.hops.size)
        assertEquals("Bearer tok", s.received[1].header("Authorization"))
        assertTrue(response.hops[0].droppedHeaders.isEmpty())
        assertEquals(s.base + "/next", response.finalUrl)
    }

    @Test
    fun crossOriginRedirectDropsAuth() {
        val other = server { _, socket -> socket.respond("200 OK", body = "other".toByteArray()) }
        val first = server { _, socket -> socket.respond("302 Found", listOf("Location: ${other.base}/landing")) }
        val response = done(
            RequestSpec(
                url = first.base + "/",
                followRedirects = true,
                headers = listOf(Kv("Cookie", "a=b"), Kv("X-Api-Key", "k"), Kv("Accept", "text/plain")),
                auth = Auth(AuthType.BASIC, username = "u", password = "p"),
            ),
        )
        assertEquals("other", String(response.body))
        val first1 = first.received.single()
        assertTrue(first1.header("Authorization")!!.startsWith("Basic "))
        val second = other.received.single()
        assertNull(second.header("Authorization"))
        assertNull(second.header("Cookie"))
        assertNull(second.header("X-Api-Key"))
        assertEquals("text/plain", second.header("Accept"))
        assertEquals(setOf("Authorization", "Cookie", "X-Api-Key"), response.hops[0].droppedHeaders.toSet())
    }

    @Test
    fun crossOriginTemporaryRedirectDoesNotResendBody() {
        val other = server { _, socket -> socket.respond("200 OK") }
        val first = server { _, socket -> socket.respond("307 Temporary Redirect", listOf("Location: ${other.base}/x")) }
        val response = done(RequestSpec(method = HttpMethod.POST, url = first.base + "/", bodyText = "secret-body", followRedirects = true))
        assertEquals(307, response.status)
        assertTrue(other.received.isEmpty())
        assertTrue(response.notes.any { it.contains("another origin") })
    }

    @Test
    fun postBecomesGetAfter302() {
        val s = server { request, socket ->
            if (request.path == "/") socket.respond("302 Found", listOf("Location: /next")) else socket.respond("200 OK")
        }
        done(RequestSpec(method = HttpMethod.POST, url = s.base + "/", bodyText = "x", followRedirects = true))
        assertEquals("POST", s.received[0].method)
        assertEquals("GET", s.received[1].method)
        assertEquals("", s.received[1].body)
        assertNull(s.received[1].header("Content-Type"))
    }

    @Test
    fun redirectToForbiddenSchemeStops() {
        listOf("file:///etc/passwd", "javascript:alert(1)", "ftp://x/").forEach { target ->
            val s = server { _, socket -> socket.respond("302 Found", listOf("Location: $target")) }
            val response = done(RequestSpec(url = s.base + "/", followRedirects = true))
            assertEquals(302, response.status)
            assertEquals(1, s.received.size)
            assertTrue(response.notes.any { it.startsWith("Redirect refused") })
        }
    }

    @Test
    fun redirectLoopIsBounded() {
        val s = server { _, socket -> socket.respond("302 Found", listOf("Location: /again")) }
        val response = done(RequestSpec(url = s.base + "/", followRedirects = true))
        assertEquals(302, response.status)
        assertEquals(Limits.MAX_REDIRECTS, response.hops.size)
        assertEquals(Limits.MAX_REDIRECTS + 1, s.received.size)
        assertTrue(response.notes.any { it.contains("Stopped after") })
    }

    @Test
    fun redirectWithoutLocationStops() {
        val s = server { _, socket -> socket.respond("301 Moved") }
        val response = done(RequestSpec(url = s.base + "/", followRedirects = true))
        assertEquals(301, response.status)
        assertTrue(response.notes.any { it.contains("no Location") })
    }

    @Test
    fun readTimeoutIsReported() {
        val s = server { _, _ -> Thread.sleep(3_000) }
        val outcome = run(RequestSpec(url = s.base + "/"), timeouts = Timeouts(1_000, 200, 20_000))
        assertEquals(FailureKind.TIMEOUT, (outcome as Outcome.Failed).kind)
    }

    @Test
    fun refusedConnectionIsReported() {
        val unused = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = unused.localPort
        unused.close()
        val outcome = run(RequestSpec(url = "http://127.0.0.1:$port/"))
        assertEquals(FailureKind.CONNECT, (outcome as Outcome.Failed).kind)
    }

    @Test
    fun cancelStopsABlockedRequest() {
        val s = server { _, _ -> Thread.sleep(10_000) }
        val token = CancelToken()
        val started = System.currentTimeMillis()
        Thread {
            Thread.sleep(200)
            token.cancel()
        }.start()
        val outcome = HttpEngine.execute(plan(RequestSpec(url = s.base + "/")), token, Timeouts(3_000, 8_000, 20_000))
        assertTrue(s.port > 0)
        assertEquals(Outcome.Cancelled, outcome)
        assertTrue(System.currentTimeMillis() - started < 7_000)
    }

    @Test
    fun cancelBeforeStartSendsNothing() {
        val s = server { _, socket -> socket.respond("200 OK") }
        val token = CancelToken()
        token.cancel()
        assertEquals(Outcome.Cancelled, HttpEngine.execute(plan(RequestSpec(url = s.base + "/")), token, fast))
        assertTrue(s.received.isEmpty())
    }

    @Test
    fun cancelDuringBodyStops() {
        val sent = AtomicInteger()
        val s = server { _, socket ->
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
            try {
                while (true) {
                    out.write(ByteArray(1024))
                    out.flush()
                    sent.incrementAndGet()
                    Thread.sleep(20)
                }
            } catch (e: SocketException) {
                socket.close()
            }
        }
        val token = CancelToken()
        Thread {
            Thread.sleep(300)
            token.cancel()
        }.start()
        assertEquals(Outcome.Cancelled, HttpEngine.execute(plan(RequestSpec(url = s.base + "/")), token, fast))
        assertTrue(sent.get() > 0)
    }

    @Test
    fun overallDeadlineStopsADrippingServer() {
        val s = server { _, socket ->
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
            try {
                while (true) {
                    out.write('x'.code)
                    out.flush()
                    Thread.sleep(50)
                }
            } catch (e: SocketException) {
                socket.close()
            }
        }
        val started = System.currentTimeMillis()
        val outcome = HttpEngine.execute(plan(RequestSpec(url = s.base + "/")), CancelToken(), Timeouts(2_000, 2_000, 500))
        assertEquals(Outcome.DeadlineExceeded, outcome)
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun garbageServerDoesNotCrash() {
        val s = server { _, socket ->
            socket.getOutputStream().write(byteArrayOf(0, 1, 2, 3, 4, 5, 0xFF.toByte(), 13, 10, 13, 10))
            socket.getOutputStream().flush()
        }
        val outcome = run(RequestSpec(url = s.base + "/"))
        assertTrue(outcome is Outcome.Failed || outcome is Outcome.Done)
    }

    @Test
    fun abruptCloseDoesNotCrash() {
        val s = server { _, socket -> socket.close() }
        assertTrue(run(RequestSpec(url = s.base + "/")) is Outcome.Failed)
    }

    @Test
    fun hostileResponseHeadersSurviveTheView() {
        val s = server { _, socket ->
            socket.respond("200 \u001B[31mOK", List(300) { "X-$it: \u001B[31mv" }, "<script>x</script>".toByteArray())
        }
        val view = ResponseViews.build(done(RequestSpec(url = s.base + "/")))
        assertFalse(view.statusLine.contains('\u001B'))
        assertTrue(view.headers.none { it.value.contains('\u001B') })
        assertEquals(BodyKind.TEXT, view.bodyKind)
        assertEquals("<script>x</script>", view.bodyChunks.joinToString(""))
    }

    @Test
    fun syntheticPlatformHeadersAreHidden() {
        val s = server { _, socket -> socket.respond("200 OK", listOf("X-Android-Sent-Millis: 1", "X-Real: 2")) }
        val names = done(RequestSpec(url = s.base + "/")).headers.map { it.key.lowercase() }
        assertFalse(names.contains("x-android-sent-millis"))
        assertTrue(names.contains("x-real"))
    }

    @Test
    fun brokenPlanUrlFailsCleanly() {
        val broken = RequestPlan(HttpMethod.GET, "file:///etc/passwd", emptyList(), null, false, emptyList(), emptySet(), emptyList(), false, false)
        assertTrue(HttpEngine.execute(broken, CancelToken(), fast) is Outcome.Failed)
        val nonsense = RequestPlan(HttpMethod.GET, "nonsense", emptyList(), null, false, emptyList(), emptySet(), emptyList(), false, false)
        assertTrue(HttpEngine.execute(nonsense, CancelToken(), fast) is Outcome.Failed)
    }

    @Test
    fun plainServerOnHttpsIsATlsFailure() {
        val listener = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        Thread {
            try {
                listener.accept().use { socket ->
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    socket.getInputStream().read()
                }
            } catch (e: IOException) {
                listener.close()
            }
        }.apply { isDaemon = true }.start()
        val outcome = run(RequestSpec(url = "https://127.0.0.1:${listener.localPort}/"))
        listener.close()
        assertEquals(FailureKind.TLS, (outcome as Outcome.Failed).kind)
    }

    @Test
    fun selfSignedCertificateIsRefusedAndNothingIsSent() {
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(Base64.getDecoder().decode(SELF_SIGNED_P12)), "testpass".toCharArray())
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, "testpass".toCharArray())
        val context = SSLContext.getInstance("TLS")
        context.init(keys.keyManagers, null, null)
        val listener = context.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val requestSeen = AtomicInteger()
        Thread {
            try {
                listener.accept().use { socket ->
                    if (socket.getInputStream().read() >= 0) requestSeen.incrementAndGet()
                }
            } catch (e: IOException) {
                listener.close()
            }
        }.apply { isDaemon = true }.start()
        val outcome = run(
            RequestSpec(
                url = "https://127.0.0.1:${listener.localPort}/",
                auth = Auth(AuthType.BEARER, token = "must-not-leak"),
            ),
        )
        listener.close()
        assertEquals(FailureKind.TLS, (outcome as Outcome.Failed).kind)
        assertEquals(0, requestSeen.get())
    }

    private companion object {
        const val SELF_SIGNED_P12 =
            "MIID5gIBAzCCA5AGCSqGSIb3DQEHAaCCA4EEggN9MIIDeTCCASAGCSqGSIb3DQEHAaCCAREEggENMIIBCTCCAQUGCyqGSIb3DQEMCgECoIG9MIG6" +
                "MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBQAbG4EWnGovCf8tI2A38w/ABMuGgICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQME" +
                "ASoEEDrslhn2Ac2it7DFp0Qo/QAEUClqDf2FQtCpmeXKSbOKnEHEzn1aBXMbBD67vZ8KVumBBv6Ie25FnK6OFd2kV725Je6TtD8LOx3Pd2HxEG2T" +
                "3XrvqPVPIuteCjtmerpCYozQMTYwEQYJKoZIhvcNAQkUMQQeAgB0MCEGCSqGSIb3DQEJFTEUBBJUaW1lIDE3OTQwMTUyMzYzMDIwggJRBgkqhkiG" +
                "9w0BBwagggJCMIICPgIBADCCAjcGCSqGSIb3DQEHATBmBgkqhkiG9w0BBQ0wWTA4BgkqhkiG9w0BBQwwKwQULBOppQk+nIIzefpLKUMjRG8rYzUC" +
                "AicQAgEgMAwGCCqGSIb3DQIJBQAwHQYJYIZIAWUDBAEqBBD5FJ9bEVX5zKRjh9tV0GpSgIIBwNHTlskD52U1zKoTbtwOW3PNZl/LLVXISnXbwSoO" +
                "L0n3eTpIhoiXLGnKvPsMMH4iZGJZe9UCwLOEQWfHgqKRtV0/2IXFti+dku0sRSsH+jA60xNjGdmRwqRfuicabl40NaJgyyRL7F4n4acYwBjwcXMH" +
                "bPxIhlAOUBmUnxIoZOyFFG3tgWgmu84lD6TcZUp2hNNbITxumRdagEs07UjTgUjdUfO675SdyuS+7JRLPGPu8eBw4IsLRtaqot5LJI3GUAoierrP" +
                "8Xpn6RFtuSUEO/lAQP+Fgjmm1HT/KnRBmmNFfUuSvhGhK6oMDrLUjuWuBMhjnutJ8yeac0izLkSIMMQ65EYfLCE0TlaGF4PaA3mUVlz4WTxHW3TF" +
                "n6wdsr1MNESKzOW6lJEbFxaxarUNAfcLuIe29Oz13beUwknM8O0+BdHz+8k7XMogqDriuhcM5fYiToWI1VJObtjp0AbE9pH5HBPVsl/MIggRJ3+V" +
                "oBFVc82cXv1pB+4bIbHHMVk/1TaaysEXKFPpzMV9b2hda9wPMfb0jn9mPCNUCDGefaHKlgjddGZD6Bxahw/ddssrla5yE/kZHEY7Z4e22RtSVG4w" +
                "TTAxMA0GCWCGSAFlAwQCAQUABCA47caRBqhmcx1y8nRaJpg7Grp5ZcuDVZW0cloMXomlcgQUhrCG1PzfCTDBG7gTojeiDXEWaEgCAicQ"
    }
}
