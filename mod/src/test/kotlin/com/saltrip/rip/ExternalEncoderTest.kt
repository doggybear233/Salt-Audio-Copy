package com.saltrip.rip

import com.saltrip.rip.encode.ExternalEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class ExternalEncoderTest {

    @Test
    fun splitsArgsRespectingQuotes() {
        assertEquals(
            listOf("-f", "-s", "-5", "-o", "C:\\out dir\\01.flac", "C:\\in dir\\01.wav"),
            ExternalEncoder.splitArgs("-f -s -5 -o \"C:\\out dir\\01.flac\" \"C:\\in dir\\01.wav\"")
        )
    }

    @Test
    fun expandsPlaceholdersBeforeSplitting() {
        val vars = mapOf("in" to "a b.wav", "out" to "a b.flac", "tracknr2" to "03")
        val argv = ExternalEncoder.plan("-o \"%out%\" \"%in%\" -T \"%tracknr2%\"", vars)
        assertEquals(listOf("-o", "a b.flac", "a b.wav", "-T", "03"), argv)
    }

    @Test
    fun longestPlaceholderWins() {
        val vars = mapOf("tracknr" to "3", "tracknr2" to "03")
        assertEquals(listOf("03", "3"), ExternalEncoder.plan("%tracknr2% %tracknr%", vars))
    }

    @Test
    fun unknownPlaceholdersStay() {
        assertEquals(listOf("%nope%"), ExternalEncoder.plan("%nope%", mapOf("in" to "x")))
    }

    @Test
    fun locateReturnsNullForBlank() {
        assertEquals(null, ExternalEncoder.locate("  "))
    }

    @Test
    fun encodeFailsWhenOutputMissing() {
        val dir = Files.createTempDirectory("saltrip-ext")
        val out = dir.resolve("o.flac")
        // 用 java 自己当「编码器」，它不会产出 o.flac → 必须报错且不留半成品
        val java = System.getProperty("java.home") + "\\bin\\java.exe"
        if (!File(java).isFile) return
        assertFailsWith<IllegalStateException> {
            ExternalEncoder.encode(java, listOf("-version"), out, timeoutSec = 60)
        }
        assertTrue(Files.notExists(out))
    }
}
