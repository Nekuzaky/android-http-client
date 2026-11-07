package dev.nekuz.reqlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UrlsTest {
    private fun ok(raw: String): ParsedUrl = when (val result = Urls.parse(raw)) {
        is UrlResult.Ok -> result.url
        is UrlResult.Bad -> {
            fail("$raw refused: ${result.reason}")
            throw AssertionError()
        }
    }

    private fun assertBad(raw: String) {
        assertTrue("accepted: $raw", Urls.parse(raw) is UrlResult.Bad)
    }

    @Test
    fun acceptsCommonDevUrls() {
        assertEquals("http://192.168.1.10:8000/api", ok("http://192.168.1.10:8000/api").toString())
        assertEquals("http://localhost:3000/", ok("http://localhost:3000").toString())
        assertEquals("https://api.example.com/v1?a=1&b=2", ok("https://api.example.com/v1?a=1&b=2").toString())
        assertEquals("http://my_app.local/x", ok("http://my_app.local/x").toString())
        assertEquals("http://myserver/", ok("http://myserver").toString())
    }

    @Test
    fun normalizesCaseAndDefaultPort() {
        assertEquals("http://example.com/Path", ok("HTTP://EXAMPLE.com:80/Path").toString())
        assertEquals("https://example.com/", ok("https://example.com:443").toString())
        assertEquals("http://example.com:443/", ok("http://example.com:443").toString())
    }

    @Test
    fun trimsSurroundingSpaces() {
        assertEquals("http://a.test/", ok("  http://a.test  ").toString())
    }

    @Test
    fun refusesOtherSchemes() {
        listOf(
            "file:///etc/passwd", "ftp://x/y", "javascript:alert(1)", "javascript://x/%0aalert(1)",
            "content://x/y", "data:text/html,<b>", "intent://x", "gopher://x", "ws://x", "jar:http://x!/y",
        ).forEach(::assertBad)
    }

    @Test
    fun refusesMissingParts() {
        listOf("", "   ", "http://", "https:///path", "http://:80", "//host/path", "host/path", "http:/x", "://x").forEach(::assertBad)
    }

    @Test
    fun refusesLineBreaksAndControls() {
        listOf(
            "http://a.test/\r\nHost: evil", "http://a.test\n", "\nhttp://a.test", "http://a.test/\u0000",
            "http://a\tb/", "http://a.test/\u007F", "http://a.test/%0a\r",
        ).forEach(::assertBad)
    }

    @Test
    fun refusesCredentialsInAuthority() {
        listOf("http://user:pass@host/", "http://good.com@evil.com/", "https://a@b:8080/", "http://@host/").forEach(::assertBad)
    }

    @Test
    fun atSignInPathIsFine() {
        assertEquals("http://a.test/u@x", ok("http://a.test/u@x").toString())
    }

    @Test
    fun refusesBackslash() {
        listOf("http://good.com\\@evil.com/", "http:\\\\evil.com", "http://a.test/\\x").forEach(::assertBad)
    }

    @Test
    fun refusesBadPorts() {
        listOf("http://h:0/", "http://h:65536/", "http://h:abc/", "http://h:/", "http://h:99999999999/", "http://h:-1/", "http://h:1:2/").forEach(::assertBad)
    }

    @Test
    fun acceptsPortBoundaries() {
        assertEquals(1, ok("http://h:1/").port)
        assertEquals(65_535, ok("http://h:65535/").port)
    }

    @Test
    fun refusesBadHosts() {
        listOf("http://a b/", "http://a..b/", "http://.a/", "http://a%2eb/", "http://a,b/", "http://<x>/", "http://a\"b/").forEach(::assertBad)
    }

    @Test
    fun refusesOverlongHost() {
        assertBad("http://" + "a".repeat(254) + "/")
    }

    @Test
    fun refusesOverlongUrl() {
        assertBad("http://a.test/" + "x".repeat(Limits.MAX_URL))
    }

    @Test
    fun ipv6Literals() {
        assertEquals("http://[::1]:8080/x", ok("http://[::1]:8080/x").toString())
        assertEquals("http://[2001:db8::1]/", ok("http://[2001:DB8::1]").toString())
        assertEquals(80, ok("http://[::ffff:192.168.0.1]/").effectivePort)
        listOf("http://[::1/", "http://[zz::1]/", "http://[1:2:3]/", "http://[::1]x/", "http://[]/", "http://[1::2::3]/", "http://[fe80::1%25en0]/").forEach(::assertBad)
    }

    @Test
    fun ipv6Validator() {
        assertTrue(Urls.isIpv6("::"))
        assertTrue(Urls.isIpv6("1:2:3:4:5:6:7:8"))
        assertTrue(Urls.isIpv6("::ffff:1.2.3.4"))
        assertFalse(Urls.isIpv6("1:2:3:4:5:6:7:8:9"))
        assertFalse(Urls.isIpv6("1:2:3:4:5:6:7"))
        assertFalse(Urls.isIpv6("12345::"))
        assertFalse(Urls.isIpv6(":::"))
        assertFalse(Urls.isIpv6("::1:"))
        assertFalse(Urls.isIpv6("::999.1.1.1"))
        assertFalse(Urls.isIpv6("1.2.3.4"))
    }

    @Test
    fun internationalHostBecomesPunycode() {
        assertEquals("http://xn--bcher-kva.example/", ok("http://bücher.example/").toString())
    }

    @Test
    fun stripsFragment() {
        assertEquals("http://a.test/x?y=1", ok("http://a.test/x?y=1#frag").toString())
        assertEquals("http://a.test/", ok("http://a.test#frag").toString())
    }

    @Test
    fun encodesUnsafeCharactersInPathAndQuery() {
        assertEquals("http://a.test/a%20b?c=d%20e", ok("http://a.test/a b?c=d e").toString())
        assertEquals("http://a.test/%C3%A9", ok("http://a.test/é").toString())
        assertEquals("http://a.test/?x=%3Cscript%3E", ok("http://a.test/?x=<script>").toString())
        assertEquals("http://a.test/?a%5B%5D=1", ok("http://a.test/?a[]=1").toString())
        assertEquals("http://a.test/%7Bx%7D", ok("http://a.test/{x}").toString())
    }

    @Test
    fun keepsValidEscapesAndEncodesInvalidPercent() {
        assertEquals("http://a.test/%2F", ok("http://a.test/%2F").toString())
        assertEquals("http://a.test/%25zz", ok("http://a.test/%zz").toString())
        assertEquals("http://a.test/%25", ok("http://a.test/%").toString())
    }

    @Test
    fun loneSurrogateDoesNotCrash() {
        assertEquals("http://a.test/%EF%BF%BD", ok("http://a.test/\uD800").toString())
    }

    @Test
    fun cleartextAndOrigin() {
        assertTrue(ok("http://a.test/").isCleartext)
        assertFalse(ok("https://a.test/").isCleartext)
        assertEquals("https://a.test:443", ok("https://a.test/").origin)
        assertEquals(ok("https://a.test:443/x").origin, ok("https://A.TEST/y").origin)
    }

    @Test
    fun extraQueryMerges() {
        assertEquals("http://a.test/?x=1&y=2", ok("http://a.test/?x=1").withExtraQuery("y=2").toString())
        assertEquals("http://a.test/?y=2", ok("http://a.test/").withExtraQuery("y=2").toString())
        assertNull(ok("http://a.test/").withExtraQuery("").query)
    }

    @Test
    fun encodeComponentEscapesEverythingSpecial() {
        assertEquals("a%26b%3Dc%20d%0D%0A", Urls.encodeComponent("a&b=c d\r\n"))
        assertEquals("%C3%A9-_.~", Urls.encodeComponent("é-_.~"))
    }

    @Test
    fun encodeFormUsesPlus() {
        assertEquals("a+b%26c%3Dd%0A", Urls.encodeForm("a b&c=d\n"))
        assertEquals("%7E", Urls.encodeForm("~"))
    }

    @Test
    fun decodeLenientHandlesBrokenEscapes() {
        assertEquals("a b", Urls.decodeLenient("a%20b"))
        assertEquals("a b", Urls.decodeLenient("a+b"))
        assertEquals("%zz", Urls.decodeLenient("%zz"))
        assertEquals("%", Urls.decodeLenient("%"))
        assertEquals("api_key", Urls.decodeLenient("api%5Fkey"))
    }

    private fun resolved(base: String, location: String) = when (val result = Urls.resolve(ok(base), location)) {
        is UrlResult.Ok -> result.url.toString()
        is UrlResult.Bad -> "BAD"
    }

    @Test
    fun resolvesRelativeReferences() {
        val base = "http://a.test/x/y?q=1"
        assertEquals("http://a.test/x/z", resolved(base, "z"))
        assertEquals("http://a.test/z", resolved(base, "/z"))
        assertEquals("http://a.test/z", resolved(base, "../z"))
        assertEquals("http://a.test/x/", resolved(base, "."))
        assertEquals("http://a.test/", resolved(base, ".."))
        assertEquals("http://a.test/z", resolved(base, "../../../../z"))
        assertEquals("http://a.test/x/y?r=2", resolved(base, "?r=2"))
        assertEquals("http://a.test/x/y?q=1", resolved(base, "#f"))
        assertEquals("http://b.test/z", resolved(base, "//b.test/z"))
        assertEquals("https://b.test/z", resolved(base, "https://b.test/z"))
        assertEquals("http://a.test/x/z?a=../b", resolved(base, "z?a=../b"))
    }

    @Test
    fun resolveRefusesHostile() {
        val base = "http://a.test/x"
        listOf(
            "file:///etc/passwd", "javascript:alert(1)", "http://a@b/", "///evil.com", "\\\\evil.com",
            "/\\evil.com", "http://a.test/\r\nX: y", "ftp://x/", "data:text/html,x",
        ).forEach { assertEquals(it, "BAD", resolved(base, it)) }
        assertEquals("BAD", resolved(base, "/" + "x".repeat(Limits.MAX_URL)))
    }
}
