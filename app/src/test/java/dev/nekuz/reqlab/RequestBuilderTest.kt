package dev.nekuz.reqlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RequestBuilderTest {
    private val base = RequestSpec(url = "http://h.test/")

    private fun ready(spec: RequestSpec, vars: Map<String, String> = emptyMap()) =
        (RequestBuilder.build(spec, vars) as BuildResult.Ready).plan

    private fun problems(spec: RequestSpec, vars: Map<String, String> = emptyMap()) =
        (RequestBuilder.build(spec, vars) as BuildResult.Rejected).problems

    private fun RequestPlan.header(name: String) = headers.firstOrNull { it.key.equals(name, true) }?.value

    @Test
    fun buildsSimpleGet() {
        val plan = ready(base)
        assertEquals(HttpMethod.GET, plan.method)
        assertEquals("http://h.test/", plan.url)
        assertNull(plan.body)
        assertTrue(plan.warnings.isEmpty())
        assertFalse(plan.carriesAuth)
    }

    @Test
    fun appendsEncodedParams() {
        val plan = ready(base.copy(url = "http://h.test/?x=1", params = listOf(Kv("a b", "c&d=e"), Kv("", ""))))
        assertEquals("http://h.test/?x=1&a%20b=c%26d%3De", plan.url)
    }

    @Test
    fun paramWithoutNameIsRefused() {
        assertTrue(problems(base.copy(params = listOf(Kv("", "v"))))[0].contains("no name"))
    }

    @Test
    fun paramCannotInjectIntoUrl() {
        val plan = ready(base.copy(params = listOf(Kv("a", "1\r\nX: y#z"))))
        assertEquals("http://h.test/?a=1%0D%0AX%3A%20y%23z", plan.url)
    }

    @Test
    fun refusesBadUrlsAndSchemes() {
        listOf("", "file:///x", "javascript:alert(1)", "http://a@b/", "http://h\r\n/").forEach {
            assertTrue(it, RequestBuilder.build(RequestSpec(url = it), emptyMap()) is BuildResult.Rejected)
        }
    }

    @Test
    fun variableCannotChangeSchemeUnnoticed() {
        val vars = mapOf("base" to "file:///etc/passwd")
        assertTrue(problems(RequestSpec(url = "{{base}}"), vars)[0].contains("http and https"))
    }

    @Test
    fun variableWithCrlfInHeaderIsRefusedAfterSubstitution() {
        val vars = mapOf("evil" to "x\r\nX-Injected: 1")
        val found = problems(base.copy(headers = listOf(Kv("A", "{{evil}}"))), vars)
        assertTrue(found[0].contains("line break"))
    }

    @Test
    fun variableWithCrlfInHeaderNameIsRefused() {
        val vars = mapOf("evil" to "A\r\nB")
        assertTrue(problems(base.copy(headers = listOf(Kv("{{evil}}", "1"))), vars)[0].contains("line break"))
    }

    @Test
    fun crlfInUrlVariableIsRefused() {
        val vars = mapOf("evil" to "http://h/\r\nX: y")
        assertTrue(problems(RequestSpec(url = "{{evil}}"), vars)[0].contains("line break"))
    }

    @Test
    fun crlfInBearerAndBasicIsRefused() {
        val bearer = base.copy(auth = Auth(AuthType.BEARER, token = "a\r\nX: y"))
        assertTrue(problems(bearer)[0].contains("line break"))
        val basic = base.copy(auth = Auth(AuthType.BASIC, username = "u", password = "p\nq"))
        assertTrue(problems(basic)[0].contains("line break"))
        val viaVar = base.copy(auth = Auth(AuthType.BEARER, token = "{{t}}"))
        assertTrue(problems(viaVar, mapOf("t" to "a\nb"))[0].contains("line break"))
    }

    @Test
    fun allProblemsAreReported() {
        val spec = RequestSpec(url = "ftp://x", headers = listOf(Kv("A B", "1"), Kv("C", "x\ny")))
        assertEquals(3, problems(spec).size)
    }

    @Test
    fun unknownVariablesBlockUrlHeadersAndAuth() {
        assertTrue(problems(RequestSpec(url = "{{nope}}/x"))[0].contains("nope"))
        assertTrue(problems(base.copy(headers = listOf(Kv("A", "{{nope}}"))))[0].contains("nope"))
        assertTrue(problems(base.copy(auth = Auth(AuthType.BEARER, token = "{{nope}}")))[0].contains("nope"))
        assertTrue(problems(base.copy(params = listOf(Kv("a", "{{nope}}"))))[0].contains("nope"))
    }

    @Test
    fun unknownVariableInBodyOnlyWarns() {
        val plan = ready(base.copy(method = HttpMethod.POST, bodyText = "{{nope}}"))
        assertEquals("{{nope}}", String(plan.body!!))
        assertTrue(plan.warnings.any { it.contains("nope") })
    }

    @Test
    fun usedVariablesAreReported() {
        val plan = ready(RequestSpec(url = "{{u}}/x"), mapOf("u" to "http://h.test", "other" to "1"))
        assertEquals(setOf("u"), plan.usedVariables)
        assertEquals("http://h.test/x", plan.url)
    }

    @Test
    fun bearerHelper() {
        val plan = ready(base.copy(url = "https://h.test/", auth = Auth(AuthType.BEARER, token = " abc ")))
        assertEquals("Bearer abc", plan.header("Authorization"))
        assertTrue(plan.carriesAuth)
        assertFalse(plan.cleartextAuth)
    }

    @Test
    fun basicHelper() {
        val plan = ready(base.copy(url = "https://h.test/", auth = Auth(AuthType.BASIC, username = "üser", password = "p:w")))
        val expected = Base64.getEncoder().encodeToString("üser:p:w".toByteArray())
        assertEquals("Basic $expected", plan.header("Authorization"))
    }

    @Test
    fun basicUsernameCannotHoldColon() {
        assertTrue(problems(base.copy(auth = Auth(AuthType.BASIC, username = "a:b", password = "c")))[0].contains("colon"))
    }

    @Test
    fun emptyAuthHelpersAreRefused() {
        assertTrue(problems(base.copy(auth = Auth(AuthType.BEARER)))[0].contains("token"))
        assertTrue(problems(base.copy(auth = Auth(AuthType.BASIC)))[0].contains("username"))
    }

    @Test
    fun helperReplacesManualAuthorization() {
        val plan = ready(base.copy(headers = listOf(Kv("authorization", "x")), auth = Auth(AuthType.BEARER, token = "t")))
        assertEquals(1, plan.headers.count { it.key.equals("Authorization", true) })
        assertEquals("Bearer t", plan.header("Authorization"))
        assertTrue(plan.warnings.any { it.contains("replaces") })
    }

    @Test
    fun cleartextAuthWarnsForEveryKind() {
        listOf(
            base.copy(auth = Auth(AuthType.BEARER, token = "t")),
            base.copy(auth = Auth(AuthType.BASIC, username = "u", password = "p")),
            base.copy(headers = listOf(Kv("Authorization", "Bearer t"))),
            base.copy(headers = listOf(Kv("Cookie", "a=b"))),
            base.copy(headers = listOf(Kv("X-Api-Key", "k"))),
            base.copy(params = listOf(Kv("token", "t"))),
            base.copy(url = "http://h.test/?api_key=k"),
        ).forEach { spec ->
            val plan = ready(spec)
            assertTrue(plan.cleartextAuth)
            assertEquals(RequestBuilder.CLEARTEXT_AUTH_WARNING, plan.warnings[0])
        }
    }

    @Test
    fun cleartextWarningFollowsSubstitutedUrl() {
        val plan = ready(
            RequestSpec(url = "{{u}}/x", auth = Auth(AuthType.BEARER, token = "t")),
            mapOf("u" to "http://h.test"),
        )
        assertTrue(plan.cleartextAuth)
        val secure = ready(
            RequestSpec(url = "{{u}}/x", auth = Auth(AuthType.BEARER, token = "t")),
            mapOf("u" to "https://h.test"),
        )
        assertFalse(secure.cleartextAuth)
        assertTrue(secure.warnings.isEmpty())
    }

    @Test
    fun cleartextWithoutAuthDoesNotWarn() {
        val plan = ready(base.copy(headers = listOf(Kv("Accept", "*/*"))))
        assertFalse(plan.cleartextAuth)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun liveCheckWarnsEvenWhileTokenIsEmpty() {
        val result = RequestBuilder.build(base.copy(auth = Auth(AuthType.BEARER)), emptyMap(), includeBody = false)
        assertTrue((result as BuildResult.Rejected).warnings.contains(RequestBuilder.CLEARTEXT_AUTH_WARNING))
    }

    @Test
    fun rawBodyAddsContentType() {
        val plan = ready(base.copy(method = HttpMethod.POST, bodyText = "hello"))
        assertArrayEquals("hello".toByteArray(), plan.body)
        assertEquals("text/plain; charset=utf-8", plan.header("Content-Type"))
    }

    @Test
    fun jsonBodyWarnsOnlyWhenInvalid() {
        val good = ready(base.copy(method = HttpMethod.POST, bodyType = BodyType.JSON, bodyText = "{\"a\":{{v}}}"), mapOf("v" to "1"))
        assertEquals("application/json", good.header("Content-Type"))
        assertTrue(good.warnings.isEmpty())
        val bad = ready(base.copy(method = HttpMethod.POST, bodyType = BodyType.JSON, bodyText = "{oops"))
        assertTrue(bad.warnings.any { it.contains("does not parse") })
        assertArrayEquals("{oops".toByteArray(), bad.body)
    }

    @Test
    fun formBodyIsEncoded() {
        val plan = ready(
            base.copy(method = HttpMethod.POST, bodyType = BodyType.FORM, formFields = listOf(Kv("a b", "c&d"), Kv("é", "{{v}}"), Kv("", ""))),
            mapOf("v" to "1 2"),
        )
        assertEquals("a+b=c%26d&%C3%A9=1+2", String(plan.body!!))
        assertEquals("application/x-www-form-urlencoded", plan.header("Content-Type"))
    }

    @Test
    fun formFieldWithoutNameIsRefused() {
        assertTrue(problems(base.copy(method = HttpMethod.POST, bodyType = BodyType.FORM, formFields = listOf(Kv("", "v"))))[0].contains("no name"))
    }

    @Test
    fun userContentTypeIsKept() {
        val plan = ready(base.copy(method = HttpMethod.POST, bodyText = "x", headers = listOf(Kv("content-type", "text/xml"))))
        assertEquals("text/xml", plan.header("Content-Type"))
        assertEquals(1, plan.headers.size)
    }

    @Test
    fun bodyIgnoredForMethodsWithoutBody() {
        listOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS).forEach { method ->
            val plan = ready(base.copy(method = method, bodyText = "x"))
            assertNull(plan.body)
            assertNull(plan.header("Content-Type"))
            assertTrue(plan.warnings.any { it.contains("ignored") })
        }
    }

    @Test
    fun emptyBodyHasNoContentType() {
        val plan = ready(base.copy(method = HttpMethod.POST))
        assertNotNull(plan.body)
        assertEquals(0, plan.body!!.size)
        assertNull(plan.header("Content-Type"))
    }

    @Test
    fun patchPlanKeepsMethodAndBody() {
        val plan = ready(base.copy(method = HttpMethod.PATCH, bodyText = "x"))
        assertEquals(HttpMethod.PATCH, plan.method)
        assertArrayEquals("x".toByteArray(), plan.body)
    }

    @Test
    fun oversizedBodyIsRefused() {
        val spec = base.copy(method = HttpMethod.POST, bodyText = "{{v}}{{v}}")
        val vars = mapOf("v" to "é".repeat(Limits.MAX_BODY_BYTES / 2))
        assertTrue(problems(spec, vars).any { it.contains("2 MiB") })
    }

    @Test
    fun substitutionBombIsRefused() {
        val spec = base.copy(method = HttpMethod.POST, bodyText = "{{v}}".repeat(10_000))
        val vars = mapOf("v" to "x".repeat(Limits.MAX_VALUE))
        assertTrue(problems(spec, vars).any { it.contains("too large") })
    }

    @Test
    fun overlongUrlFromParamsIsRefused() {
        val spec = base.copy(params = listOf(Kv("a", "é".repeat(2000))))
        assertTrue(problems(spec).any { it.contains("too long") })
    }

    @Test
    fun followFlagIsCarried() {
        assertTrue(ready(base.copy(followRedirects = true)).followRedirects)
        assertFalse(ready(base).followRedirects)
    }

    @Test
    fun secretsIncludeAuthAndSensitiveVariables() {
        val plan = ready(
            base.copy(
                headers = listOf(Kv("X-Custom", "{{hidden}}"), Kv("X-Api-Key", "{{plain}}")),
                auth = Auth(AuthType.BEARER, token = "tok"),
                url = "http://h.test/{{my_token}}",
            ),
            mapOf("hidden" to "visible-value", "plain" to "named-plain", "my_token" to "vvv"),
        )
        assertTrue(plan.secrets.contains("tok"))
        assertTrue(plan.secrets.contains("named-plain"))
        assertTrue(plan.secrets.contains("vvv"))
        assertFalse(plan.secrets.contains("visible-value"))
    }

    @Test
    fun previewMasksSecretsAndShowsSubstitution() {
        val plan = ready(
            RequestSpec(
                method = HttpMethod.POST,
                url = "{{base}}/{{token}}?password=pw1",
                headers = listOf(Kv("Cookie", "sid=abc"), Kv("Accept", "*/*"), Kv("X-Note", "{{token}}")),
                auth = Auth(AuthType.BEARER, token = "secret-token"),
                bodyText = "body {{token}} secret-token",
            ),
            mapOf("base" to "http://h.test", "token" to "tkvalue"),
        )
        val preview = PreviewText.of(plan)
        assertTrue(preview, preview.contains("POST http://h.test/***?password=***"))
        assertTrue(preview, preview.contains("Variables used: base, token"))
        assertTrue(preview, preview.contains("Accept: */*"))
        assertTrue(preview, preview.contains("Cookie: ***"))
        assertTrue(preview, preview.contains("Authorization: ***"))
        assertTrue(preview, preview.contains("X-Note: ***"))
        assertTrue(preview, preview.contains("body *** ***"))
        assertTrue(preview, preview.contains(RequestBuilder.CLEARTEXT_AUTH_WARNING))
        listOf("secret-token", "tkvalue", "abc", "pw1").forEach { assertFalse(it, preview.contains(it)) }
    }

    @Test
    fun previewCutsLongBodies() {
        val plan = ready(base.copy(method = HttpMethod.POST, bodyText = "x".repeat(60_000)))
        val preview = PreviewText.of(plan)
        assertTrue(preview.contains("(preview cut)"))
        assertTrue(preview.length < 6_000)
    }

    @Test
    fun previewStripsHostileCharacters() {
        val plan = ready(base.copy(method = HttpMethod.POST, bodyText = "a\u202Eb\u0000c"))
        val preview = PreviewText.of(plan)
        assertTrue(preview.contains("abc"))
        assertFalse(preview.contains('\u202E'))
    }

    @Test
    fun textFormats() {
        assertEquals("512 B", Text.size(512))
        assertEquals("1.5 KiB", Text.size(1536))
        assertEquals("2.00 MiB", Text.size(2_097_152))
        assertEquals("12 ms", Text.millis(12))
        assertEquals("1.50 s", Text.millis(1500))
    }
}
