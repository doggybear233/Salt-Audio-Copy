package com.saltrip.rip

import com.saltrip.rip.encode.FlacEncoder
import com.saltrip.rip.rip.WavWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class FlacEncoderTest {

    @Test
    fun `args are ordered and level clamped`() {
        val args = FlacEncoder.buildArgs(Path.of("a.wav"), Path.of("b.flac"), 5)
        assertEquals(listOf("-f", "-s", "-5", "-o", "b.flac", "a.wav"), args)
        assertEquals("-0", FlacEncoder.buildArgs(Path.of("a"), Path.of("b"), -3)[2])
        assertEquals("-8", FlacEncoder.buildArgs(Path.of("a"), Path.of("b"), 42)[2])
    }

    @Test
    fun `locate returns the bundled encoder`() {
        val exe = FlacEncoder.locate()
        assertTrue(exe != null, "插件包里带 flac.exe，locate 不该为 null")
        assertTrue(exe!!.endsWith("flac.exe"), exe)
    }

    @Test
    fun `encodes silence to flac and reports size`() {
        val exe = FlacEncoder.locate()
        assumeTrue(exe != null, "内置 flac.exe 解不出来，跳过真实编码")

        val dir = Files.createTempDirectory("saltrip-flac")
        val wav = dir.resolve("in.wav")
        val flac = dir.resolve("out.flac")
        WavWriter(wav).use { w ->
            val zeros = ByteArray(2352 * 75) // 1 秒静音
            w.write(zeros, 0, zeros.size)
        }
        val size = FlacEncoder.encode(exe!!, wav, flac, 5)
        assertTrue(size > 0)
        assertEquals(Files.size(flac), size)
        val head = Files.readAllBytes(flac).copyOfRange(0, 4)
        assertEquals("fLaC", String(head, Charsets.US_ASCII))
    }

    @Test
    fun `fails cleanly when encoder is missing`() {
        val dir = Files.createTempDirectory("saltrip-flac-err")
        val wav = dir.resolve("in.wav")
        WavWriter(wav).use { w -> w.write(ByteArray(2352), 0, 2352) }
        val err = runCatching {
            FlacEncoder.encode("definitely-not-a-real-encoder-xyz", wav, dir.resolve("o.flac"), 5, 5)
        }.exceptionOrNull()
        assertTrue(err != null)
        assertContains(err!!.message ?: "", "FLAC")
        assertTrue(!Files.exists(dir.resolve("o.flac")))
    }
}
