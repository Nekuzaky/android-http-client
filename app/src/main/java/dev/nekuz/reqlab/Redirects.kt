package dev.nekuz.reqlab

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

class HopRequest(
    val method: HttpMethod,
    val url: String,
    val headers: List<Kv>,
    val body: ByteArray?,
)

sealed class RedirectDecision {
    class Follow(val next: HopRequest, val droppedHeaders: List<String>, val downgraded: Boolean) : RedirectDecision()

    data class Stop(val reason: String) : RedirectDecision()
}

object HostClass {
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".internal", ".intranet", ".home.arpa")

    fun isPrivate(host: String): Boolean {
        val name = host.lowercase(Locale.ROOT).trimEnd('.')
        if (name.startsWith("[")) return isPrivateIpv6(name.removePrefix("[").removeSuffix("]"))
        looseIpv4(name)?.let { return isPrivateIpv4(it) }
        if (name == "localhost" || !name.contains('.')) return true
        return LOCAL_SUFFIXES.any { name.endsWith(it) }
    }

    internal fun looseIpv4(host: String): Long? {
        val parts = host.split('.')
        if (parts.size !in 1..4) return null
        val numbers = parts.map { number(it) ?: return null }
        var value = 0L
        numbers.forEachIndexed { index, part ->
            if (index < numbers.lastIndex) {
                if (part > 255) return null
                value += part shl (8 * (3 - index))
            } else {
                val room = 8 * (4 - index)
                if (part >= (1L shl room)) return null
                value += part
            }
        }
        return value
    }

    private fun number(text: String): Long? {
        if (text.isEmpty() || text.length > 12) return null
        return when {
            text.startsWith("0x") -> if (text.length == 2) 0L else text.substring(2).toLongOrNull(16)
            text.length > 1 && text.startsWith("0") -> text.substring(1).toLongOrNull(8)
            else -> text.toLongOrNull(10)
        }?.takeIf { it >= 0 }
    }

    private fun isPrivateIpv4(value: Long): Boolean {
        val a = (value shr 24).toInt() and 0xFF
        val b = (value shr 16).toInt() and 0xFF
        return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }

    private fun isPrivateIpv6(literal: String): Boolean {
        val address = try {
            InetAddress.getByName(literal)
        } catch (e: UnknownHostException) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            var value = 0L
            bytes.forEach { value = (value shl 8) + (it.toInt() and 0xFF) }
            return isPrivateIpv4(value)
        }
        val first = bytes[0].toInt() and 0xFF
        return address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || first == 0xFC || first == 0xFD
    }
}

object Redirects {
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val BODY_HEADERS = setOf("content-type", "content-encoding", "content-language")
    private val CROSS_ORIGIN_ALLOWED = setOf(
        "accept", "accept-encoding", "accept-language", "user-agent", "cache-control", "pragma",
    )
    private val SAFE_METHODS = setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)

    fun isRedirect(status: Int) = status in REDIRECT_CODES

    fun next(current: HopRequest, status: Int, location: String?): RedirectDecision {
        if (location.isNullOrEmpty()) return RedirectDecision.Stop("The redirect has no Location header.")
        val base = (Urls.parse(current.url) as? UrlResult.Ok)?.url
            ?: return RedirectDecision.Stop("The current URL could not be parsed.")
        val target = when (val resolved = Urls.resolve(base, location)) {
            is UrlResult.Ok -> resolved.url
            is UrlResult.Bad -> return RedirectDecision.Stop("Redirect refused: " + resolved.reason)
        }
        val crossOrigin = target.origin != base.origin
        val rewriteToGet = (status == 303 && current.method != HttpMethod.HEAD) ||
            ((status == 301 || status == 302) && current.method == HttpMethod.POST)
        val method = if (rewriteToGet) HttpMethod.GET else current.method
        val body = if (rewriteToGet) null else current.body
        if (crossOrigin && !HostClass.isPrivate(base.host) && HostClass.isPrivate(target.host)) {
            return RedirectDecision.Stop("Redirect not followed: a public server pointed this request at a private address.")
        }
        if (crossOrigin && body != null && body.isNotEmpty()) {
            return RedirectDecision.Stop("Redirect not followed: it would resend the request body to another origin.")
        }
        if (crossOrigin && method !in SAFE_METHODS) {
            return RedirectDecision.Stop("Redirect not followed: it would repeat ${method.name} on another origin.")
        }
        val dropped = ArrayList<String>()
        val headers = current.headers.filter { header ->
            val lower = header.key.lowercase(Locale.ROOT)
            val crossDrop = crossOrigin && lower !in CROSS_ORIGIN_ALLOWED
            if (crossDrop && lower != "host") dropped.add(header.key)
            !crossDrop && !(rewriteToGet && lower in BODY_HEADERS)
        }
        return RedirectDecision.Follow(
            HopRequest(method, target.toString(), headers, body),
            dropped,
            base.scheme == "https" && target.scheme == "http",
        )
    }

    fun forwardsAcrossOrigins(headerName: String): Boolean = headerName.lowercase(Locale.ROOT) in CROSS_ORIGIN_ALLOWED
}
