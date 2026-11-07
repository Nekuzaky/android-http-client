package dev.nekuz.reqlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignTextTest {
    private val fallback = "fallback"

    @Test
    fun keepsOrdinaryLabels() {
        assertEquals("Maps", ForeignText.label("Maps", fallback))
        assertEquals("Café Noir", ForeignText.label("Café Noir", fallback))
        assertEquals("日本語アプリ", ForeignText.label("日本語アプリ", fallback))
    }

    @Test
    fun removesControlCharacters() {
        assertEquals("evilline", ForeignText.label("evil\nline", fallback))
        assertEquals("ab", ForeignText.label("a\u0000\u0007b\r", fallback))
    }

    @Test
    fun removesBidiAndFormatCharacters() {
        assertEquals("gpj.exe", ForeignText.label("\u202Egpj.exe\u202C", fallback))
        assertEquals("ab", ForeignText.label("a\u2066\u2069\u200B\u200D\u00AD\uFEFFb", fallback))
    }

    @Test
    fun removesLoneSurrogatesAndPrivateUse() {
        assertEquals("ab", ForeignText.label("a\uD800b", fallback))
        assertEquals("ab", ForeignText.label("a\uE000b", fallback))
    }

    @Test
    fun fallsBackWhenNothingIsVisible() {
        assertEquals(fallback, ForeignText.label(null, fallback))
        assertEquals(fallback, ForeignText.label("", fallback))
        assertEquals(fallback, ForeignText.label("\u200B\u202E", fallback))
        assertEquals(fallback, ForeignText.label("ㅤ⠀", fallback))
    }

    @Test
    fun capsLength() {
        val text = ForeignText.clean("a".repeat(5000), 10)
        assertEquals("a".repeat(10) + "…", text)
    }

    @Test
    fun capsCombiningMarks() {
        val zalgo = "a" + "́".repeat(500) + "b"
        assertEquals("a" + "́".repeat(4) + "b", ForeignText.clean(zalgo))
    }

    @Test
    fun hugeInputIsBounded() {
        val text = ForeignText.clean("x".repeat(10_000_000), 100)
        assertEquals(101, text.length)
    }

    @Test
    fun optionalReportsUnprintable() {
        assertNull(ForeignText.optional(null))
        assertNull(ForeignText.optional(""))
        assertEquals(ForeignText.UNPRINTABLE, ForeignText.optional("\u200B"))
    }

    @Test
    fun blockKeepsLinesAndTabs() {
        assertEquals("a\n\tb\nc", ForeignText.block("a\r\n\tb\rc", 100))
    }

    @Test
    fun blockRemovesHiddenCharacters() {
        assertEquals("ab\ncd", ForeignText.block("a\u0000b\n\u202Ec\u001B[31md".replace("[31m", ""), 100))
    }

    @Test
    fun blockKeepsHtmlAsLiteralText() {
        val html = "<script>alert(1)</script><img src=x onerror=y>"
        assertEquals(html, ForeignText.block(html, 200))
    }

    @Test
    fun blockCapsLength() {
        val text = ForeignText.block("abc".repeat(100), 10)
        assertEquals(11, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun blockDoesNotSplitSupplementary() {
        val text = ForeignText.block("😀😀", 3)
        assertEquals("😀…", text)
    }

    @Test
    fun blockHandlesNull() {
        assertEquals("", ForeignText.block(null, 10))
        assertFalse(ForeignText.block("", 10).isNotEmpty())
    }
}
