package dev.nekuz.reqlab

import java.util.Locale

object SensitiveNames {
    private val EXACT = setOf("cookie", "set-cookie", "sig", "sid", "pass", "code")
    private val FRAGMENTS = listOf(
        "authoriz", "authent", "token", "secret", "password", "passwd", "pwd", "apikey", "api-key", "api_key",
        "session", "signature", "credential", "jwt", "bearer", "cookie", "csrf", "xsrf", "private",
    )
    private val AUTH_WORD = Regex("(^|[-_.])o?auth([-_.]|$)")
    private val KEY_WORD = Regex("(^|[-_.])keys?([-_.]|$)")
    private val CAMEL_KEY = Regex("[a-z0-9]Keys?($|[A-Z_.-])")

    fun isSensitive(name: String): Boolean {
        val trimmed = name.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.isEmpty()) return false
        if (lower in EXACT) return true
        if (FRAGMENTS.any { lower.contains(it) }) return true
        return AUTH_WORD.containsMatchIn(lower) || KEY_WORD.containsMatchIn(lower) || CAMEL_KEY.containsMatchIn(trimmed)
    }
}

object Redactor {
    const val MASK = "***"
    const val MIN_FREE_TEXT = 4

    fun mask(text: String, secrets: List<String>): String {
        var result = text
        secrets.filter { it.length >= MIN_FREE_TEXT }.distinct().sortedByDescending { it.length }.forEach {
            result = result.replace(it, MASK)
        }
        return result
    }

    fun maskUrl(url: String, secrets: List<String>): String {
        val redacted = url(url)
        val tailStart = tailStart(redacted)
        return redacted.substring(0, tailStart) + mask(redacted.substring(tailStart), secrets)
    }

    fun url(url: String): String {
        val mark = url.indexOf('?')
        if (mark < 0) return url
        val head = url.substring(0, mark)
        val hash = url.indexOf('#', mark)
        val query = if (hash < 0) url.substring(mark + 1) else url.substring(mark + 1, hash)
        val tail = if (hash < 0) "" else url.substring(hash)
        val parts = query.split('&').map { part ->
            val equals = part.indexOf('=')
            val name = if (equals < 0) part else part.substring(0, equals)
            if (equals >= 0 && SensitiveNames.isSensitive(Urls.decodeLenient(name))) "$name=$MASK" else part
        }
        return head + "?" + parts.joinToString("&") + tail
    }

    fun queryValues(url: String): List<String> {
        val mark = url.indexOf('?')
        if (mark < 0) return emptyList()
        val hash = url.indexOf('#', mark)
        val query = if (hash < 0) url.substring(mark + 1) else url.substring(mark + 1, hash)
        return query.split('&').mapNotNull { part ->
            val equals = part.indexOf('=')
            if (equals < 0) return@mapNotNull null
            val value = part.substring(equals + 1)
            if (value.isNotEmpty() && SensitiveNames.isSensitive(Urls.decodeLenient(part.substring(0, equals)))) value else null
        }
    }

    private fun tailStart(url: String): Int {
        val scheme = url.indexOf("://")
        val from = if (scheme < 0) 0 else scheme + 3
        for (index in from until url.length) {
            val c = url[index]
            if (c == '/' || c == '?' || c == '#') return index
        }
        return url.length
    }
}
