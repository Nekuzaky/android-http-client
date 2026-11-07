package dev.nekuz.reqlab

import java.util.Locale

object HeaderRules {
    private const val TOKEN_EXTRA = "!#$%&'*+-.^_`|~"
    private val MANAGED = setOf("content-length", "transfer-encoding")

    fun nameProblem(name: String): String? {
        if (name.isEmpty()) return "A header has a value but no name."
        if (hasLineBreak(name)) {
            return "A header name contains a line break. Line breaks could inject extra headers, so the request is refused."
        }
        val shown = ForeignText.clean(name, 64)
        if (name.length > Limits.MAX_KEY) return "The header name $shown is too long."
        if (!name.all(::isTokenChar)) {
            return "The header name $shown has characters that are not allowed. Use letters, digits and - only."
        }
        if (name.lowercase(Locale.ROOT) in MANAGED) {
            return "The header $shown is set automatically. Remove it from the headers."
        }
        return null
    }

    fun valueProblem(name: String, value: String): String? {
        val shown = ForeignText.clean(name, 64)
        if (hasLineBreak(value)) {
            return "The value of header $shown contains a line break. Line breaks could inject extra headers, so the request is refused."
        }
        if (value.length > Limits.MAX_HEADER_VALUE) return "The value of header $shown is too long."
        if (value.any { it.code < 0x20 || it.code == 0x7F }) {
            return "The value of header $shown contains a control character."
        }
        if (value.any { it.code > 0x7E }) {
            return "The value of header $shown has non-ASCII characters. Percent-encode them first."
        }
        return null
    }

    fun hasLineBreak(text: String) = text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0

    fun isTokenChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || TOKEN_EXTRA.indexOf(c) >= 0
}
