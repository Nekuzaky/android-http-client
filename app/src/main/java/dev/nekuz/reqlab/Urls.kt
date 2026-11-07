package dev.nekuz.reqlab

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.util.Locale

data class ParsedUrl(
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
) {
    val effectivePort: Int
        get() = port ?: if (scheme == "https") 443 else 80

    val origin: String
        get() = "$scheme://$host:$effectivePort"

    val isCleartext: Boolean
        get() = scheme == "http"

    val authority: String
        get() = if (port == null) host else "$host:$port"

    fun withExtraQuery(extra: String): ParsedUrl {
        if (extra.isEmpty()) return this
        val merged = if (query.isNullOrEmpty()) extra else "$query&$extra"
        return copy(query = merged)
    }

    override fun toString(): String = buildString {
        append(scheme).append("://").append(authority).append(path)
        if (query != null) append('?').append(query)
    }
}

sealed class UrlResult {
    data class Ok(val url: ParsedUrl) : UrlResult()

    data class Bad(val reason: String) : UrlResult()
}

object Urls {
    private const val HEX = "0123456789ABCDEF"
    private const val MAX_HOST = 253
    private const val UNRESERVED_EXTRA = "-._~"
    private const val SUB_DELIMS = "!$&'()*+,;="
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val HOST = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)*\\.?")
    private val DIGITS = Regex("[0-9]{1,5}")

    fun parse(raw: String): UrlResult {
        if (raw.length > Limits.MAX_URL) return UrlResult.Bad("The URL is too long.")
        if (hasControl(raw)) return UrlResult.Bad("The URL contains a line break or control character.")
        val text = raw.trim(' ')
        if (text.isEmpty()) return UrlResult.Bad("Enter a URL.")
        if (text.contains('\\')) return UrlResult.Bad("The URL contains a backslash.")
        val separator = text.indexOf("://")
        if (separator <= 0) return UrlResult.Bad("The URL must start with http:// or https://.")
        val scheme = text.substring(0, separator).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return UrlResult.Bad("Only http and https URLs are allowed.")
        val rest = text.substring(separator + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        if (authority.contains('@')) {
            return UrlResult.Bad("Credentials in the URL are not allowed. Use the Auth section.")
        }
        val hostPort = hostAndPort(authority) ?: return UrlResult.Bad("The URL host or port is not valid.")
        var tail = rest.substring(authorityEnd)
        val hash = tail.indexOf('#')
        if (hash >= 0) tail = tail.substring(0, hash)
        val mark = tail.indexOf('?')
        val rawPath = if (mark < 0) tail else tail.substring(0, mark)
        val rawQuery = if (mark < 0) null else tail.substring(mark + 1)
        val path = encodeLoose(rawPath, "/:@").ifEmpty { "/" }
        val query = rawQuery?.let { encodeLoose(it, "/:@?") }
        val port = hostPort.second?.takeIf { it != defaultPort(scheme) }
        val url = ParsedUrl(scheme, hostPort.first, port, path, query)
        if (url.toString().length > Limits.MAX_URL) return UrlResult.Bad("The URL is too long.")
        return UrlResult.Ok(url)
    }

    fun resolve(base: ParsedUrl, location: String): UrlResult {
        if (location.length > Limits.MAX_URL) return UrlResult.Bad("The redirect target is too long.")
        if (hasControl(location)) return UrlResult.Bad("The redirect target contains a control character.")
        val text = location.trim(' ')
        if (text.contains('\\')) return UrlResult.Bad("The redirect target contains a backslash.")
        val prefix = base.scheme + "://" + base.authority
        val target = when {
            text.isEmpty() || text.startsWith("#") -> base.toString()
            SCHEME.containsMatchIn(text) -> text
            text.startsWith("//") -> base.scheme + ":" + text
            text.startsWith("/") -> prefix + normalizePathPart(text)
            text.startsWith("?") -> prefix + base.path + text
            else -> prefix + normalizePathPart(base.path.substringBeforeLast('/', "") + "/" + text)
        }
        return parse(target)
    }

    fun encodeComponent(text: String): String {
        val out = StringBuilder(text.length + 8)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isUnreserved(codePoint)) out.appendCodePoint(codePoint) else appendEncoded(out, codePoint)
        }
        return out.toString()
    }

    fun encodeForm(text: String): String {
        val out = StringBuilder(text.length + 8)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint == ' '.code -> out.append('+')
                isUnreserved(codePoint) && codePoint != '~'.code -> out.appendCodePoint(codePoint)
                else -> appendEncoded(out, codePoint)
            }
        }
        return out.toString()
    }

    fun decodeLenient(text: String): String {
        if (!text.contains('%') && !text.contains('+')) return text
        val bytes = ByteArrayOutputStream(text.length)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c == '%' && isEscape(text, index)) {
                bytes.write(Character.digit(text[index + 1], 16) * 16 + Character.digit(text[index + 2], 16))
                index += 3
            } else if (c == '+') {
                bytes.write(' '.code)
                index++
            } else {
                val codePoint = text.codePointAt(index)
                index += Character.charCount(codePoint)
                val piece = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                bytes.write(piece, 0, piece.size)
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    internal fun isIpv6(text: String): Boolean {
        if (text.length < 2 || text.length > 45) return false
        if (!text.contains(':')) return false
        if (text.any { !(it.isHexAscii() || it == ':' || it == '.') }) return false
        val doubled = text.indexOf("::")
        if (doubled >= 0 && text.indexOf("::", doubled + 1) >= 0) return false
        val hasCompression = doubled >= 0
        val pieces = if (hasCompression) {
            val left = text.substring(0, doubled)
            val right = text.substring(doubled + 2)
            (if (left.isEmpty()) emptyList() else left.split(':')) + (if (right.isEmpty()) emptyList() else right.split(':'))
        } else {
            text.split(':')
        }
        var groups = 0
        pieces.forEachIndexed { position, piece ->
            if (piece.contains('.')) {
                if (position != pieces.lastIndex || !isIpv4(piece)) return false
                groups += 2
            } else {
                if (piece.isEmpty() || piece.length > 4) return false
                groups += 1
            }
        }
        return if (hasCompression) groups <= 7 else groups == 8
    }

    private fun hasControl(text: String) = text.any { it.code < 0x20 || it.code == 0x7F }

    private fun normalizePathPart(pathAndRest: String): String {
        val cut = pathAndRest.indexOfFirst { it == '?' || it == '#' }
        val path = if (cut < 0) pathAndRest else pathAndRest.substring(0, cut)
        val rest = if (cut < 0) "" else pathAndRest.substring(cut)
        val output = ArrayList<String>()
        val segments = path.split('/')
        segments.forEachIndexed { position, segment ->
            val last = position == segments.lastIndex
            when (segment) {
                "." -> if (last) output.add("")
                ".." -> {
                    if (output.size > 1) output.removeAt(output.lastIndex)
                    if (last) output.add("")
                }
                else -> output.add(segment)
            }
        }
        val joined = output.joinToString("/")
        return (if (joined.startsWith("/")) joined else "/$joined") + rest
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    private fun hostAndPort(authority: String): Pair<String, Int?>? {
        if (authority.isEmpty()) return null
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val literal = authority.substring(1, close)
            if (!isIpv6(literal)) return null
            val host = "[" + literal.lowercase(Locale.ROOT) + "]"
            val after = authority.substring(close + 1)
            if (after.isEmpty()) return host to null
            if (!after.startsWith(":")) return null
            val port = port(after.substring(1)) ?: return null
            return host to port
        }
        val colon = authority.indexOf(':')
        if (colon != authority.lastIndexOf(':')) return null
        val rawHost = if (colon < 0) authority else authority.substring(0, colon)
        val host = normalizeHost(rawHost) ?: return null
        if (colon < 0) return host to null
        val port = port(authority.substring(colon + 1)) ?: return null
        return host to port
    }

    private fun normalizeHost(raw: String): String? {
        if (raw.isEmpty() || raw.length > MAX_HOST) return null
        val ascii = if (raw.all { it.code < 0x80 }) {
            raw
        } else {
            try {
                IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES)
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        val lower = ascii.lowercase(Locale.ROOT)
        if (lower.length > MAX_HOST || !HOST.matches(lower)) return null
        return lower
    }

    private fun port(text: String): Int? {
        if (!DIGITS.matches(text)) return null
        val value = text.toInt()
        return if (value in 1..65_535) value else null
    }

    private fun isIpv4(text: String): Boolean {
        val parts = text.split('.')
        return parts.size == 4 && parts.all { part ->
            part.length in 1..3 && part.all { it in '0'..'9' } && part.toInt() <= 255
        }
    }

    private fun Char.isHexAscii() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun encodeLoose(text: String, extra: String): String {
        val out = StringBuilder(text.length + 8)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint == '%'.code && isEscape(text, index)) {
                out.append(text, index, index + 3)
                index += 3
                continue
            }
            index += Character.charCount(codePoint)
            val allowed = isUnreserved(codePoint) ||
                (codePoint < 0x80 && (SUB_DELIMS.indexOf(codePoint.toChar()) >= 0 || extra.indexOf(codePoint.toChar()) >= 0))
            if (allowed) out.appendCodePoint(codePoint) else appendEncoded(out, codePoint)
        }
        return out.toString()
    }

    private fun isEscape(text: String, index: Int): Boolean {
        if (index + 2 >= text.length) return false
        return text[index + 1].isHexAscii() && text[index + 2].isHexAscii()
    }

    private fun isUnreserved(codePoint: Int): Boolean {
        if (codePoint >= 0x80) return false
        val c = codePoint.toChar()
        return c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || UNRESERVED_EXTRA.indexOf(c) >= 0
    }

    private fun appendEncoded(out: StringBuilder, codePoint: Int) {
        val safe = if (codePoint in 0xD800..0xDFFF) 0xFFFD else codePoint
        String(Character.toChars(safe)).toByteArray(Charsets.UTF_8).forEach { byte ->
            val value = byte.toInt() and 0xFF
            out.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
    }
}
