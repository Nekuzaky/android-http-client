package dev.nekuz.reqlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderRulesTest {
    @Test
    fun acceptsNormalHeaders() {
        assertNull(HeaderRules.nameProblem("Content-Type"))
        assertNull(HeaderRules.nameProblem("X-Custom_1.a"))
        assertNull(HeaderRules.valueProblem("A", "application/json; charset=utf-8"))
        assertNull(HeaderRules.valueProblem("A", ""))
    }

    @Test
    fun refusesLineBreaksInNames() {
        listOf("A\r\nB", "A\nB", "A\rB", "\r\n").forEach {
            assertTrue(HeaderRules.nameProblem(it)!!.contains("line break"))
        }
    }

    @Test
    fun refusesLineBreaksInValues() {
        listOf("a\r\nX-Evil: 1", "a\nb", "a\rb", "\n", "x\r\n\r\n<html>").forEach {
            assertTrue(HeaderRules.valueProblem("A", it)!!.contains("line break"))
        }
    }

    @Test
    fun refusesBadNameCharacters() {
        listOf("A B", "A:B", "A(B)", "é", "A\u0000", "A/B", "A\tB", "A@B").forEach {
            assertNotNull(it, HeaderRules.nameProblem(it))
        }
    }

    @Test
    fun refusesEmptyNameAndOverlong() {
        assertNotNull(HeaderRules.nameProblem(""))
        assertNotNull(HeaderRules.nameProblem("A".repeat(Limits.MAX_KEY + 1)))
        assertNotNull(HeaderRules.valueProblem("A", "a".repeat(Limits.MAX_HEADER_VALUE + 1)))
    }

    @Test
    fun refusesControlAndNonAsciiValues() {
        listOf("a\u0000b", "a\tb", "a\u001Bb", "a\u007Fb", "é", "\u202E", "\u0085", "\u2028").forEach {
            assertNotNull(it, HeaderRules.valueProblem("A", it))
        }
    }

    @Test
    fun refusesManagedHeaders() {
        assertNotNull(HeaderRules.nameProblem("Content-Length"))
        assertNotNull(HeaderRules.nameProblem("transfer-encoding"))
    }

    @Test
    fun problemTextCleansTheName() {
        val problem = HeaderRules.nameProblem("A\u202EB C")!!
        assertFalse(problem.contains('\u202E'))
    }
}

class SensitiveTest {
    @Test
    fun recognisesSecrets() {
        listOf(
            "Authorization", "authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "api_key", "apikey",
            "X-Auth-Token", "token", "access_token", "client_secret", "password", "passwd", "pwd", "PHPSESSID".replace("PHPSESSID", "session_id"),
            "X-CSRF-Token", "jwt", "sig", "signature", "key", "auth", "oauth", "x-auth", "code", "private_key",
        ).forEach { assertTrue(it, SensitiveNames.isSensitive(it)) }
    }

    @Test
    fun leavesOrdinaryNames() {
        listOf("Accept", "Content-Type", "User-Agent", "author", "authors", "page", "q", "monkey", "base_url", "keep", "").forEach {
            assertFalse(it, SensitiveNames.isSensitive(it))
        }
    }

    @Test
    fun maskReplacesLongestFirst() {
        assertEquals("a *** b", Redactor.mask("a secret-long b", listOf("secret", "secret-long")))
        assertEquals("abc", Redactor.mask("abc", listOf("abc")))
        assertEquals("xyz", Redactor.mask("xyz", emptyList()))
        assertEquals("xyz", Redactor.mask("xyz", listOf("")))
    }

    @Test
    fun urlRedactionMasksSensitiveQueryOnly() {
        assertEquals("http://a/?token=***&page=2", Redactor.url("http://a/?token=abc&page=2"))
        assertEquals("http://a/?api%5Fkey=***", Redactor.url("http://a/?api%5Fkey=abc"))
        assertEquals("http://a/?x", Redactor.url("http://a/?x"))
        assertEquals("http://a/", Redactor.url("http://a/"))
        assertEquals("http://a/?=1&&", Redactor.url("http://a/?=1&&"))
        assertEquals("http://a/?password=***#f", Redactor.url("http://a/?password=p#f"))
    }

    @Test
    fun queryValuesFindsSecrets() {
        assertEquals(listOf("abc"), Redactor.queryValues("http://a/?token=abc&page=2"))
        assertTrue(Redactor.queryValues("http://a/?page=2").isEmpty())
        assertTrue(Redactor.queryValues("http://a/?token=").isEmpty())
    }
}

class VariablesTest {
    private val vars = mapOf("base_url" to "http://h:8000", "a" to "1", "b.c-d" to "2", "loop" to "{{loop}}{{a}}", "empty" to "")

    @Test
    fun substitutesKnownVariables() {
        val result = Variables.substitute("{{base_url}}/x?a={{a}}", vars)
        assertEquals("http://h:8000/x?a=1", result.text)
        assertEquals(setOf("base_url", "a"), result.used)
        assertTrue(result.unknown.isEmpty())
        assertFalse(result.overflow)
    }

    @Test
    fun allowsSpacesInsideBraces() {
        assertEquals("1", Variables.substitute("{{ a }}", vars).text)
    }

    @Test
    fun reportsUnknownAndLeavesThem() {
        val result = Variables.substitute("x{{nope}}y", vars)
        assertEquals("x{{nope}}y", result.text)
        assertEquals(setOf("nope"), result.unknown)
    }

    @Test
    fun isNotRecursive() {
        val result = Variables.substitute("{{loop}}", vars)
        assertEquals("{{loop}}{{a}}", result.text)
        assertEquals(setOf("loop"), result.used)
    }

    @Test
    fun malformedTemplatesStayLiteral() {
        listOf("{{", "}}", "{{}}", "{{ }}", "{{a", "a}}", "{a}", "{{1bad}}", "{{a b}}", "{{a\nb}}", "{{" + "x".repeat(500) + "}}").forEach {
            val result = Variables.substitute(it, vars)
            assertEquals(it, it, result.text)
            assertTrue(result.used.isEmpty())
        }
    }

    @Test
    fun tripleBracesWork() {
        assertEquals("{1}", Variables.substitute("{{{a}}}", vars).text)
    }

    @Test
    fun emptyValueIsUsed() {
        val result = Variables.substitute("[{{empty}}]", vars)
        assertEquals("[]", result.text)
        assertEquals(setOf("empty"), result.used)
    }

    @Test
    fun overflowStopsExpansion() {
        val big = mapOf("x" to "y".repeat(1000))
        val result = Variables.substitute("{{x}}".repeat(1000), big, 5000)
        assertTrue(result.overflow)
        assertTrue(result.text.length <= 5000)
        val literal = Variables.substitute("z".repeat(100), emptyMap(), 10)
        assertTrue(literal.overflow)
        assertTrue(literal.text.length <= 10)
    }

    @Test
    fun hugeTemplateIsLinearEnough() {
        val result = Variables.substitute("{{".repeat(100_000), vars)
        assertEquals(200_000, result.text.length)
    }

    @Test
    fun nameValidation() {
        listOf("a", "_a", "base_url", "a.b", "a-b", "A9").forEach { assertTrue(it, Variables.isValidName(it)) }
        listOf("", "1a", "a b", "a{", "é", "x".repeat(65), "a\n").forEach { assertFalse(it, Variables.isValidName(it)) }
    }

    @Test
    fun rowsToMapFiltersInvalid() {
        val rows = listOf(Kv("good", "1"), Kv("bad name", "2"), Kv(" spaced ", "3"), Kv("good", "4"))
        assertEquals(mapOf("good" to "4", "spaced" to "3"), Variables.toMap(rows))
        assertEquals(listOf("bad name"), Variables.invalidNames(rows))
    }
}

class JsonPrettyTest {
    @Test
    fun formatsObjectsAndArrays() {
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    true,\n    null\n  ]\n}", JsonPretty.format("{\"a\":1,\"b\":[true,null]}"))
    }

    @Test
    fun keepsEmptyContainersTight() {
        assertEquals("{\n  \"a\": {},\n  \"b\": []\n}", JsonPretty.format("{\"a\" : { } , \"b\" : [ ] }"))
    }

    @Test
    fun formatsScalars() {
        assertEquals("1", JsonPretty.format(" 1 "))
        assertEquals("\"x\"", JsonPretty.format("\"x\""))
        assertEquals("null", JsonPretty.format("null"))
        assertEquals("-1.5e+10", JsonPretty.format("-1.5e+10"))
    }

    @Test
    fun keepsNumberTextExactly() {
        assertEquals("[\n  12345678901234567890.123456789\n]", JsonPretty.format("[12345678901234567890.123456789]"))
    }

    @Test
    fun keepsStringEscapes() {
        assertEquals("\"a\\n\\u00e9\\\"\"", JsonPretty.format("\"a\\n\\u00e9\\\"\""))
    }

    @Test
    fun rejectsMalformed() {
        listOf(
            "", " ", "{", "}", "[", "]", "[1,]", "{\"a\":}", "{\"a\"}", "{a:1}", "{'a':1}", "[1 2]", "[1}", "{\"a\":1]",
            "01", "1.", ".5", "1e", "+1", "--1", "tru", "nul", "truex", "\"abc", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"",
            "\"a\nb\"", "[1]x", "1 2", "{\"a\":1,}", "NaN", "Infinity", "<html>", "\"\\u١٢٣٤\"",
        ).forEach {
            assertNull(it, JsonPretty.format(it))
            assertFalse(it, JsonPretty.isValid(it))
        }
    }

    @Test
    fun acceptsBom() {
        assertEquals("1", JsonPretty.format("\uFEFF1"))
    }

    @Test
    fun deepNestingIsRefusedWithoutStackOverflow() {
        assertNull(JsonPretty.format("[".repeat(1_000_000)))
        assertNull(JsonPretty.format("{\"a\":".repeat(100_000)))
        val fine = "[".repeat(JsonPretty.MAX_DEPTH) + "]".repeat(JsonPretty.MAX_DEPTH)
        assertTrue(JsonPretty.isValid(fine))
        val tooDeep = "[".repeat(JsonPretty.MAX_DEPTH + 1) + "]".repeat(JsonPretty.MAX_DEPTH + 1)
        assertFalse(JsonPretty.isValid(tooDeep))
    }

    @Test
    fun outputGrowthIsCapped() {
        val wide = "[" + "[[[[[[[[[[1]]]]]]]]]],".repeat(300_000) + "1]"
        assertNull(JsonPretty.format(wide))
        assertTrue(JsonPretty.isValid(wide))
    }

    @Test
    fun largeValidInputFormats() {
        val text = "[" + "1,".repeat(500_000) + "1]"
        assertNotNull(JsonPretty.format(text))
    }
}
