package dev.nekuz.reqlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseModelTest {
    private fun raw(
        body: ByteArray = ByteArray(0),
        headers: List<Kv> = emptyList(),
        status: Int = 200,
        message: String? = "OK",
        truncated: Boolean = false,
        hops: List<RedirectHop> = emptyList(),
        notes: List<String> = emptyList(),
    ) = RawResponse(status, message, headers, body, truncated, 5, "http://h/", hops, notes)

    private fun json(text: String) = Kv("Content-Type", text)

    @Test
    fun statusClasses() {
        assertEquals(StatusClass.INFORMATIONAL, StatusClass.of(101))
        assertEquals(StatusClass.SUCCESS, StatusClass.of(204))
        assertEquals(StatusClass.REDIRECT, StatusClass.of(302))
        assertEquals(StatusClass.CLIENT_ERROR, StatusClass.of(404))
        assertEquals(StatusClass.SERVER_ERROR, StatusClass.of(503))
        assertEquals(StatusClass.UNKNOWN, StatusClass.of(99))
        assertEquals(StatusClass.UNKNOWN, StatusClass.of(600))
    }

    @Test
    fun charsetParsing() {
        assertEquals(Charsets.UTF_8, BodyDecoder.charset(null))
        assertEquals(Charsets.UTF_8, BodyDecoder.charset("text/plain"))
        assertEquals(Charsets.ISO_8859_1, BodyDecoder.charset("text/plain; charset=ISO-8859-1"))
        assertEquals(Charsets.ISO_8859_1, BodyDecoder.charset("text/plain; CHARSET=\"iso-8859-1\""))
        assertEquals(Charsets.UTF_8, BodyDecoder.charset("text/plain; charset=nope-999"))
        assertEquals(Charsets.UTF_8, BodyDecoder.charset("text/plain; charset=" + "x".repeat(10_000)))
        assertEquals(Charsets.UTF_8, BodyDecoder.charset("charset=;;;'"))
    }

    @Test
    fun binaryDetection() {
        assertTrue(BodyDecoder.looksBinary(byteArrayOf(1, 2, 0, 3), Charsets.UTF_8))
        assertTrue(BodyDecoder.looksBinary(ByteArray(100) { 1 }, Charsets.UTF_8))
        assertFalse(BodyDecoder.looksBinary("hello\n\tworld".toByteArray(), Charsets.UTF_8))
        assertFalse(BodyDecoder.looksBinary("héllo 日本語".toByteArray(), Charsets.UTF_8))
        assertFalse(BodyDecoder.looksBinary("hi".toByteArray(Charsets.UTF_16LE), Charsets.UTF_16LE))
        assertFalse(BodyDecoder.looksBinary(ByteArray(0), Charsets.UTF_8))
    }

    @Test
    fun chunksCoverWholeTextWithoutLosingContent() {
        val text = (1..5000).joinToString("\n") { "line $it" }
        val chunks = BodyChunks.split(text, 100)
        assertEquals(text, chunks.joinToString("\n"))
        assertTrue(chunks.all { it.length <= 100 })
    }

    @Test
    fun chunksSplitLongLines() {
        val text = "x".repeat(1050)
        val chunks = BodyChunks.split(text, 100)
        assertEquals(11, chunks.size)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun chunksDoNotSplitSurrogatePairs() {
        val text = "😀".repeat(200)
        val chunks = BodyChunks.split(text, 7)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { Character.isHighSurrogate(it.last()) })
    }

    @Test
    fun chunksHandleEmptyAndLeadingNewlines() {
        assertTrue(BodyChunks.split("").isEmpty())
        assertEquals("\n\n\na", BodyChunks.split("\n\n\na", 2).joinToString("\n"))
        assertTrue(BodyChunks.split("\n".repeat(50), 10).isNotEmpty())
    }

    @Test
    fun emptyBody() {
        val view = ResponseViews.build(raw())
        assertEquals(BodyKind.EMPTY, view.bodyKind)
        assertTrue(view.bodyChunks.isEmpty())
        assertEquals("200 OK", view.statusLine)
    }

    @Test
    fun jsonIsPrettyPrinted() {
        val view = ResponseViews.build(raw("{\"a\":1}".toByteArray(), listOf(json("application/json"))))
        assertEquals(BodyKind.JSON, view.bodyKind)
        assertEquals("{\n  \"a\": 1\n}", view.bodyChunks.joinToString("\n"))
    }

    @Test
    fun jsonDetectedWithoutContentType() {
        assertEquals(BodyKind.JSON, ResponseViews.build(raw(" [1,2] ".toByteArray())).bodyKind)
    }

    @Test
    fun brokenJsonStaysText() {
        val view = ResponseViews.build(raw("{broken".toByteArray(), listOf(json("application/json"))))
        assertEquals(BodyKind.TEXT, view.bodyKind)
        assertEquals("{broken", view.bodyChunks.joinToString(""))
    }

    @Test
    fun deepJsonDoesNotCrash() {
        val view = ResponseViews.build(raw("[".repeat(1_000_000).toByteArray()))
        assertEquals(BodyKind.TEXT, view.bodyKind)
    }

    @Test
    fun htmlIsPlainText() {
        val html = "<html><script>alert(1)</script></html>"
        val view = ResponseViews.build(raw(html.toByteArray(), listOf(json("text/html"))))
        assertEquals(BodyKind.TEXT, view.bodyKind)
        assertEquals(html, view.bodyChunks.joinToString(""))
    }

    @Test
    fun binaryIsNotShown() {
        val view = ResponseViews.build(raw(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 0)))
        assertEquals(BodyKind.BINARY, view.bodyKind)
        assertTrue(view.bodyChunks.isEmpty())
        assertEquals("", view.copyText)
        assertEquals(7, view.sizeBytes)
    }

    @Test
    fun malformedUtf8DoesNotCrash() {
        val view = ResponseViews.build(raw(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte(), 0x41)))
        assertTrue(view.bodyChunks.joinToString("").contains("A"))
    }

    @Test
    fun bodyHostileCharactersAreCleaned() {
        val view = ResponseViews.build(raw("a\u202Eb\u001Bc\r\nd".toByteArray()))
        assertEquals("abc\nd", view.bodyChunks.joinToString("\n"))
    }

    @Test
    fun cutBodyIsMarked() {
        val view = ResponseViews.build(raw("abc".toByteArray(), truncated = true))
        assertTrue(view.truncated)
    }

    @Test
    fun headersAreCleanedAndCapped() {
        val many = (1..500).map { Kv("H$it", "v") }
        val view = ResponseViews.build(raw(headers = many + Kv("X", "y")))
        assertEquals(Limits.MAX_RESPONSE_HEADERS, view.headers.size)
        assertTrue(view.notes.any { it.contains("Only the first") })
        val hostile = ResponseViews.build(raw(headers = listOf(Kv("A\u202EB", "c\r\nd\u0000" + "x".repeat(100_000)), Kv("\u200B", "v"))))
        assertEquals("AB", hostile.headers[0].key)
        assertFalse(hostile.headers[0].value.contains('\n'))
        assertTrue(hostile.headers[0].value.length < 5_000)
        assertEquals("(unnamed)", hostile.headers[1].key)
    }

    @Test
    fun statusMessageIsCleaned() {
        assertEquals("200 OKx", ResponseViews.build(raw(message = "OK\r\n\u202Ex")).statusLine)
        assertEquals("200", ResponseViews.build(raw(message = null)).statusLine)
        assertTrue(ResponseViews.build(raw(message = "y".repeat(5000))).statusLine.length < 210)
    }

    @Test
    fun hopsAreCleaned() {
        val hop = RedirectHop(302, "http://a/\u202E", "http://b/\u0000", listOf("Authorization"))
        val view = ResponseViews.build(raw(hops = listOf(hop)))
        assertEquals("302: http://a/ to http://b/ (dropped Authorization)", view.hops[0])
    }

    @Test
    fun copyTextIsCapped() {
        val big = ("x".repeat(99) + "\n").repeat(5000)
        val view = ResponseViews.build(raw(big.toByteArray()))
        assertTrue(view.copyCut)
        assertEquals(Limits.MAX_COPY_CHARS, view.copyText.length)
    }

    @Test
    fun maximumBodyBuildsInTime() {
        val big = ByteArray(Limits.RESPONSE_CAP) { 'a'.code.toByte() }
        val view = ResponseViews.build(raw(big))
        assertEquals(Limits.RESPONSE_CAP.toLong(), view.bodyChunks.sumOf { it.length.toLong() })
    }
}
