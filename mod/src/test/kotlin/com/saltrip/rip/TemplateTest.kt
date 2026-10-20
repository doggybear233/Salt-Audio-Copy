package com.saltrip.rip

import com.saltrip.rip.naming.TemplateEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateTest {

    private val vars = mapOf(
        "albumartist" to "宇多田ヒカル",
        "albumtitle" to "Fantôme",
        "tracknr" to "3",
        "tracknr2" to "03",
        "title" to "道",
        "year" to "2016"
    )

    @Test
    fun expandsKnownVariables() {
        assertEquals("宇多田ヒカル/Fantôme", TemplateEngine.expand("%albumartist%/%albumtitle%", vars))
        assertEquals("03 道", TemplateEngine.expand("%tracknr2% %title%", vars))
        assertEquals("3 道", TemplateEngine.expand("%tracknr% %title%", vars))
    }

    @Test
    fun keepsUnknownVariableIntact() {
        assertEquals("%nope% x", TemplateEngine.expand("%nope% x", vars))
    }

    @Test
    fun splitsIntoSanitizedSegments() {
        val segs = TemplateEngine.expandToSegments("%albumartist%/%albumtitle%", vars)
        assertEquals(listOf("宇多田ヒカル", "Fantôme"), segs)
    }

    @Test
    fun dropsTraversalSegments() {
        val segs = TemplateEngine.expandToSegments("a/../b/./c", vars)
        assertEquals(listOf("a", "b", "c"), segs)
    }

    @Test
    fun replacesIllegalWindowsChars() {
        assertEquals("a_b_c_d_e_f_g_h_i", TemplateEngine.sanitizeSegment("a\\b/c:d*e?f\"g<h>i"))
        assertEquals("_", TemplateEngine.sanitizeSegment("   "))
    }

    @Test
    fun trimsTrailingDotsAndSpaces() {
        assertEquals("track", TemplateEngine.sanitizeSegment("track...   "))
    }

    @Test
    fun guardsReservedDeviceNames() {
        assertEquals("_CON", TemplateEngine.sanitizeSegment("CON"))
        assertEquals("_nul", TemplateEngine.sanitizeSegment("nul"))
        assertEquals("concept", TemplateEngine.sanitizeSegment("concept"))
    }

    @Test
    fun keepsCjkAndEmoji() {
        val s = TemplateEngine.sanitizeSegment("桜流し 🌸")
        assertEquals("桜流し 🌸", s)
    }

    @Test
    fun truncatesByCodepoints() {
        val long = "あ".repeat(200)
        val out = TemplateEngine.sanitizeSegment(long, 120)
        assertEquals(120, out.codePointCount(0, out.length))
    }

    @Test
    fun nfcNormalizes() {
        val decomposed = "Fanto\u0302me" // Fanto + combining circumflex
        assertEquals("Fantôme", TemplateEngine.sanitizeSegment(decomposed))
    }

    @Test
    fun pathLimitCheck() {
        assertTrue(TemplateEngine.fitsPathLimit("C:\\a\\b"))
        assertFalse(TemplateEngine.fitsPathLimit("x".repeat(300)))
    }
}
