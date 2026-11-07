package dev.nekuz.reqlab

object JsonPretty {
    const val MAX_DEPTH = 200
    const val MAX_OUTPUT = 6_000_000
    private const val INDENT = "  "

    fun isValid(text: String): Boolean = Scanner(text, null).run()

    fun format(text: String): String? {
        val out = StringBuilder(minOf(text.length + text.length / 4, MAX_OUTPUT))
        return if (Scanner(text, out).run()) out.toString() else null
    }

    private class Scanner(private val text: String, private val out: StringBuilder?) {
        private var pos = if (text.startsWith("\uFEFF")) 1 else 0
        private val stack = BooleanArray(MAX_DEPTH)
        private var depth = 0

        fun run(): Boolean {
            var expectValue = true
            var expectKey = false
            while (true) {
                skipSpace()
                if (expectKey) {
                    if (!string()) return false
                    skipSpace()
                    if (peek() != ':') return false
                    pos++
                    if (!write(": ")) return false
                    expectKey = false
                    expectValue = true
                    continue
                }
                if (expectValue) {
                    val c = peek() ?: return false
                    when (c) {
                        '{', '[' -> {
                            if (depth >= MAX_DEPTH) return false
                            val isObject = c == '{'
                            stack[depth++] = isObject
                            pos++
                            if (!write(c.toString())) return false
                            skipSpace()
                            if (peek() == (if (isObject) '}' else ']')) {
                                pos++
                                depth--
                                if (!write(if (isObject) "}" else "]")) return false
                                expectValue = false
                            } else {
                                if (!newline()) return false
                                expectKey = isObject
                                expectValue = !isObject
                            }
                        }
                        '"' -> {
                            if (!string()) return false
                            expectValue = false
                        }
                        't' -> {
                            if (!literal("true")) return false
                            expectValue = false
                        }
                        'f' -> {
                            if (!literal("false")) return false
                            expectValue = false
                        }
                        'n' -> {
                            if (!literal("null")) return false
                            expectValue = false
                        }
                        else -> {
                            if (!number()) return false
                            expectValue = false
                        }
                    }
                    continue
                }
                if (depth == 0) return pos == text.length
                val isObject = stack[depth - 1]
                when (peek()) {
                    ',' -> {
                        pos++
                        if (!write(",") || !newline()) return false
                        expectKey = isObject
                        expectValue = !isObject
                    }
                    (if (isObject) '}' else ']') -> {
                        pos++
                        depth--
                        if (!newline() || !write(if (isObject) "}" else "]")) return false
                    }
                    else -> return false
                }
            }
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun skipSpace() {
            while (pos < text.length) {
                val c = text[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return
            }
        }

        private fun write(piece: String): Boolean {
            if (out == null) return true
            if (out.length + piece.length > MAX_OUTPUT) return false
            out.append(piece)
            return true
        }

        private fun newline(): Boolean {
            if (out == null) return true
            if (out.length + 1 + depth * INDENT.length > MAX_OUTPUT) return false
            out.append('\n')
            repeat(depth) { out.append(INDENT) }
            return true
        }

        private fun literal(word: String): Boolean {
            if (!text.startsWith(word, pos)) return false
            pos += word.length
            return write(word)
        }

        private fun string(): Boolean {
            if (peek() != '"') return false
            val start = pos
            pos++
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c == '"' -> {
                        pos++
                        return writeRange(start, pos)
                    }
                    c == '\\' -> {
                        pos++
                        val escape = peek() ?: return false
                        when (escape) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> pos++
                            'u' -> {
                                if (pos + 4 >= text.length) return false
                                for (offset in 1..4) {
                                    val digit = text[pos + offset]
                                    if (digit.code >= 0x80 || Character.digit(digit, 16) < 0) return false
                                }
                                pos += 5
                            }
                            else -> return false
                        }
                    }
                    c.code < 0x20 -> return false
                    else -> pos++
                }
            }
            return false
        }

        private fun number(): Boolean {
            val start = pos
            if (peek() == '-') pos++
            val first = peek() ?: return false
            if (first == '0') {
                pos++
            } else if (first in '1'..'9') {
                digits()
            } else {
                return false
            }
            if (peek() == '.') {
                pos++
                if (!digits()) return false
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (!digits()) return false
            }
            return writeRange(start, pos)
        }

        private fun digits(): Boolean {
            val start = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            return pos > start
        }

        private fun writeRange(start: Int, end: Int): Boolean {
            if (out == null) return true
            if (out.length + end - start > MAX_OUTPUT) return false
            out.append(text, start, end)
            return true
        }
    }
}
