package dev.nekuz.reqlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectsTest {
    private val secretHeaders = listOf(
        Kv("Authorization", "Bearer t"),
        Kv("Proxy-Authorization", "x"),
        Kv("Cookie", "a=b"),
        Kv("X-Api-Key", "k"),
        Kv("X-Auth-Token", "z"),
        Kv("Accept", "*/*"),
    )

    private fun request(
        url: String = "https://a.test/x",
        method: HttpMethod = HttpMethod.GET,
        headers: List<Kv> = secretHeaders,
        body: ByteArray? = null,
    ) = HopRequest(method, url, headers, body)

    private fun follow(request: HopRequest, status: Int, location: String?) =
        Redirects.next(request, status, location) as RedirectDecision.Follow

    private fun stop(request: HopRequest, status: Int, location: String?) =
        Redirects.next(request, status, location) as RedirectDecision.Stop

    @Test
    fun recognisesRedirectCodes() {
        listOf(301, 302, 303, 307, 308).forEach { assertTrue(Redirects.isRedirect(it)) }
        listOf(200, 300, 304, 305, 306, 400, -1).forEach { assertFalse(Redirects.isRedirect(it)) }
    }

    @Test
    fun sameOriginKeepsAuth() {
        val decision = follow(request(), 302, "/y")
        assertEquals("https://a.test/y", decision.next.url)
        assertEquals(secretHeaders, decision.next.headers)
        assertTrue(decision.droppedHeaders.isEmpty())
    }

    @Test
    fun explicitDefaultPortIsSameOrigin() {
        assertTrue(follow(request(), 302, "https://A.TEST:443/y").droppedHeaders.isEmpty())
    }

    @Test
    fun crossOriginDropsEverySensitiveHeader() {
        val decision = follow(request(), 302, "https://b.test/y")
        assertEquals(listOf(Kv("Accept", "*/*")), decision.next.headers)
        assertEquals(5, decision.droppedHeaders.size)
    }

    @Test
    fun differentPortIsCrossOrigin() {
        assertEquals(1, follow(request(url = "http://a.test:8000/"), 302, "http://a.test:8001/").next.headers.size)
    }

    @Test
    fun subdomainIsCrossOrigin() {
        assertEquals(1, follow(request(), 302, "https://sub.a.test/").next.headers.size)
    }

    @Test
    fun schemeChangeIsCrossOrigin() {
        val down = follow(request(), 301, "http://a.test/x")
        assertEquals(1, down.next.headers.size)
        assertTrue(down.downgraded)
        val up = follow(request(url = "http://a.test/"), 301, "https://a.test/")
        assertEquals(1, up.next.headers.size)
        assertFalse(up.downgraded)
    }

    @Test
    fun protocolRelativeIsCrossOrigin() {
        assertEquals("https://evil.test/", follow(request(), 302, "//evil.test/").next.url)
        assertEquals(1, follow(request(), 302, "//evil.test/").next.headers.size)
    }

    @Test
    fun crossOriginDropsHostHeader() {
        val decision = follow(request(headers = listOf(Kv("Host", "a.test"))), 302, "https://b.test/")
        assertTrue(decision.next.headers.isEmpty())
        assertTrue(decision.droppedHeaders.isEmpty())
    }

    @Test
    fun postBecomesGetOn301And302() {
        val body = "x".toByteArray()
        val headers = listOf(Kv("Content-Type", "text/plain"), Kv("Authorization", "t"))
        listOf(301, 302, 303).forEach { status ->
            val decision = follow(request(method = HttpMethod.POST, headers = headers, body = body), status, "/y")
            assertEquals(HttpMethod.GET, decision.next.method)
            assertNull(decision.next.body)
            assertEquals(listOf(Kv("Authorization", "t")), decision.next.headers)
        }
    }

    @Test
    fun methodAndBodyKeptOn307And308() {
        val body = "x".toByteArray()
        listOf(307, 308).forEach { status ->
            val decision = follow(request(method = HttpMethod.POST, body = body), status, "/y")
            assertEquals(HttpMethod.POST, decision.next.method)
            assertArrayEquals(body, decision.next.body)
        }
    }

    @Test
    fun putKeepsMethodOn302ButBecomesGetOn303() {
        assertEquals(HttpMethod.PUT, follow(request(method = HttpMethod.PUT), 302, "/y").next.method)
        assertEquals(HttpMethod.GET, follow(request(method = HttpMethod.PUT), 303, "/y").next.method)
    }

    @Test
    fun headStaysHead() {
        assertEquals(HttpMethod.HEAD, follow(request(method = HttpMethod.HEAD), 303, "/y").next.method)
    }

    @Test
    fun bodyIsNotResentToAnotherOrigin() {
        val body = "secret".toByteArray()
        listOf(307, 308).forEach { status ->
            assertTrue(stop(request(method = HttpMethod.POST, body = body), status, "https://b.test/").reason.contains("another origin"))
        }
        assertTrue(stop(request(method = HttpMethod.PUT, body = body), 302, "https://b.test/").reason.contains("another origin"))
    }

    @Test
    fun rewrittenPostMayLeaveOriginWithoutBody() {
        val decision = follow(request(method = HttpMethod.POST, body = "x".toByteArray()), 302, "https://b.test/")
        assertEquals(HttpMethod.GET, decision.next.method)
        assertNull(decision.next.body)
    }

    @Test
    fun hostileLocationsStop() {
        listOf(
            "file:///etc/passwd", "javascript:alert(1)", "ftp://x/", "http://u:p@evil.test/", "\\\\evil", "http://a\r\nX: y",
            "http://[bad/", "http://h:99999/", "content://x/y", "/" + "x".repeat(Limits.MAX_URL + 1),
        ).forEach {
            assertTrue(it, stop(request(), 302, it).reason.startsWith("Redirect refused"))
        }
    }

    @Test
    fun missingLocationStops() {
        assertTrue(stop(request(), 302, null).reason.contains("no Location"))
        assertTrue(stop(request(), 302, "").reason.contains("no Location"))
    }

    @Test
    fun brokenCurrentUrlStops() {
        assertTrue(stop(request(url = "not a url"), 302, "/x").reason.contains("could not be parsed"))
    }
}
