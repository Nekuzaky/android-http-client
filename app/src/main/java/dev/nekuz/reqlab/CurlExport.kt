package dev.nekuz.reqlab

object ShellQuote {
    private const val HEX = "0123456789abcdef"

    fun quote(text: String): String? {
        if (text.indexOf('\u0000') >= 0) return null
        if (!needsEscapes(text)) return "'" + text.replace("'", "'\\''") + "'"
        val out = StringBuilder("$'")
        var index = 0
        while (index < text.length) {
            val raw = text.codePointAt(index)
            index += Character.charCount(raw)
            val codePoint = if (raw in 0xD800..0xDFFF) 0xFFFD else raw
            when {
                codePoint == '\\'.code -> out.append("\\\\")
                codePoint == '\''.code -> out.append("\\'")
                codePoint == '\n'.code -> out.append("\\n")
                codePoint == '\r'.code -> out.append("\\r")
                codePoint == '\t'.code -> out.append("\\t")
                codePoint == '!'.code -> out.append("\\x21")
                isUnsafe(codePoint) -> String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).forEach { byte ->
                    val value = byte.toInt() and 0xFF
                    out.append("\\x").append(HEX[value shr 4]).append(HEX[value and 0x0F])
                }
                else -> out.appendCodePoint(codePoint)
            }
        }
        return out.append('\'').toString()
    }

    private fun needsEscapes(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint == '\n'.code) continue
            if (isUnsafe(codePoint) || codePoint in 0xD800..0xDFFF) return true
        }
        return false
    }

    private fun isUnsafe(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }
}

object CurlExport {
    private const val CONTINUATION = " \\\n  "

    fun omitsLocation(plan: RequestPlan): Boolean =
        plan.followRedirects && plan.headers.any { !Redirects.forwardsAcrossOrigins(it.key) && !it.key.equals("Content-Type", ignoreCase = true) }

    fun command(plan: RequestPlan, maskSecrets: Boolean): String? {
        val parts = ArrayList<String>()
        parts.add("curl --globoff")
        when (plan.method) {
            HttpMethod.GET -> Unit
            HttpMethod.HEAD -> parts.add("--head")
            else -> parts.add("-X " + plan.method.name)
        }
        if (plan.followRedirects && !omitsLocation(plan)) parts.add("--location")
        val url = if (maskSecrets) plan.maskedUrl else plan.url
        parts.add("--url " + (ShellQuote.quote(url) ?: return null))
        (if (maskSecrets) plan.maskedHeaders else plan.headers).forEach { header ->
            val argument = if (header.value.isEmpty()) header.key + ";" else header.key + ": " + header.value
            parts.add("-H " + (ShellQuote.quote(argument) ?: return null))
        }
        val body = plan.body
        if (body != null && body.isNotEmpty()) {
            val text = if (maskSecrets) plan.maskedBody else String(body, Charsets.UTF_8)
            parts.add("--data-raw " + (ShellQuote.quote(text) ?: return null))
        }
        return parts.joinToString(CONTINUATION)
    }
}
