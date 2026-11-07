package dev.nekuz.reqlab

data class Substituted(
    val text: String,
    val used: Set<String>,
    val unknown: Set<String>,
    val overflow: Boolean,
)

object Variables {
    const val MAX_NAME_LENGTH = 64
    private const val SEARCH_WINDOW = MAX_NAME_LENGTH + 8
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,63}")

    fun isValidName(name: String): Boolean = NAME.matches(name)

    fun toMap(rows: List<Kv>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        rows.forEach { row ->
            val name = row.key.trim()
            if (isValidName(name)) map[name] = row.value
        }
        return map
    }

    fun invalidNames(rows: List<Kv>): List<String> =
        rows.map { it.key.trim() }.filter { it.isNotEmpty() && !isValidName(it) }

    fun substitute(template: String, vars: Map<String, String>, maxLength: Int = Limits.MAX_RESOLVED): Substituted {
        val out = StringBuilder(minOf(template.length, maxLength))
        val used = LinkedHashSet<String>()
        val unknown = LinkedHashSet<String>()
        var index = 0
        while (index < template.length) {
            val open = template.indexOf("{{", index)
            val literalEnd = if (open < 0) template.length else open
            if (!appendBounded(out, template, index, literalEnd, maxLength)) {
                return Substituted(out.toString(), used, unknown, true)
            }
            if (open < 0) break
            val close = template.indexOf("}}", open + 2)
            val name = if (close < 0) null else nameBetween(template, open, close)
            if (name == null) {
                if (!appendBounded(out, template, open, open + 1, maxLength)) {
                    return Substituted(out.toString(), used, unknown, true)
                }
                index = open + 1
                continue
            }
            val value = vars[name]
            val fits = if (value == null) {
                unknown.add(name)
                appendBounded(out, template, open, close + 2, maxLength)
            } else {
                used.add(name)
                appendBounded(out, value, 0, value.length, maxLength)
            }
            if (!fits) return Substituted(out.toString(), used, unknown, true)
            index = close + 2
        }
        return Substituted(out.toString(), used, unknown, false)
    }

    private fun nameBetween(template: String, open: Int, close: Int): String? {
        if (close - open - 2 > SEARCH_WINDOW) return null
        val name = template.substring(open + 2, close).trim()
        return if (isValidName(name)) name else null
    }

    private fun appendBounded(out: StringBuilder, source: String, start: Int, end: Int, maxLength: Int): Boolean {
        if (out.length + (end - start) > maxLength) return false
        out.append(source, start, end)
        return true
    }
}
