package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.OutputCheck
import com.doggybear233.saltaudiocopy.album.Overwrite
import com.doggybear233.saltaudiocopy.rip.WavInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 输出自检 + 续抓与覆盖策略的交互。 */
class OutputCheckTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltac-out")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** 造一个 n 个扇区的合法 WAV（只写头 + 零数据，足够被解析）。 */
    private fun wav(name: String, sectors: Long, headerOnly: Boolean = false): Path {
        val p = dir.resolve(name)
        val dataLen = if (headerOnly) 0L else sectors * 2352L
        val h = ByteArray(44)
        fun ascii(off: Int, s: String) { for (i in s.indices) h[off + i] = s[i].code.toByte() }
        fun i32(off: Int, v: Long) {
            for (i in 0..3) h[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
        }
        fun i16(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF"); i32(4, dataLen + 36); ascii(8, "WAVE"); ascii(12, "fmt ")
        i32(16, 16); i16(20, 1); i16(22, 2); i32(24, 44100); i32(28, 44100 * 4); i16(32, 4); i16(34, 16)
        ascii(36, "data"); i32(40, dataLen)
        Files.newOutputStream(p).use { out ->
            out.write(h)
            if (dataLen > 0) out.write(ByteArray(dataLen.toInt()))
        }
        return p
    }

    // ---------- WavInfo ----------

    @Test
    fun readsHeaderFacts() {
        wav("a.wav", 10)
        val f = WavInfo.read(dir.resolve("a.wav"))!!
        assertTrue(f.headerOk)
        assertEquals(10L, f.sectors)
        assertEquals(44100, f.sampleRate)
        assertEquals(2, f.channels)
        assertEquals(16, f.bitsPerSample)
        assertTrue(WavInfo.wholeSectors(f))
    }

    @Test
    fun truncatedTailIsNotWholeSectors() {
        wav("b.wav", 5)
        val p = dir.resolve("b.wav")
        // 模拟「写到一半断了」：数据段少了 1000 字节
        val size = Files.size(p)
        java.io.RandomAccessFile(p.toFile(), "rw").use { it.setLength(size - 1000) }
        val f = WavInfo.read(p)!!
        assertTrue(!WavInfo.wholeSectors(f))
    }

    // ---------- 续抓 vs 覆盖策略 ----------

    @Test
    fun partialFileIsResumedInPlaceInsteadOfSuffixed() {
        wav("01 song.wav", 3)
        // 目标应为 10 扇区；已有 3 扇区 → 必须原位续写
        val p = Overwrite.resolve(dir, "01 song", ".wav", Overwrite.SUFFIX, resumeExpectedBytes = 10L * 2352)
        assertEquals("01 song.wav", p!!.fileName.toString())
    }

    @Test
    fun completeFileStillGetsSuffixed() {
        wav("01 song.wav", 10)
        val p = Overwrite.resolve(dir, "01 song", ".wav", Overwrite.SUFFIX, resumeExpectedBytes = 10L * 2352)
        assertEquals("01 song (2).wav", p!!.fileName.toString())
    }

    @Test
    fun partialFileIsSuffixedWhenResumeDisabled() {
        wav("01 song.wav", 3)
        val p = Overwrite.resolve(dir, "01 song", ".wav", Overwrite.SUFFIX, resumeExpectedBytes = null)
        assertEquals("01 song (2).wav", p!!.fileName.toString())
    }

    // ---------- OutputCheck ----------

    @Test
    fun cleanDirectoryPasses() {
        wav("01.wav", 4)
        Files.writeString(dir.resolve("a.cue"), "FILE \"01.wav\" WAVE\r\n  TRACK 01 AUDIO\r\n    INDEX 01 00:00:00\r\n")
        val r = OutputCheck.check(dir)
        assertTrue(r.ok, r.problems.joinToString())
        assertEquals(1, r.wavCount)
        assertEquals(1, r.cueCount)
    }

    @Test
    fun cuePointingAtMissingFileIsReported() {
        Files.writeString(dir.resolve("a.cue"), "FILE \"ghost.wav\" WAVE\r\n")
        val r = OutputCheck.check(dir)
        assertTrue(!r.ok)
        assertTrue(r.problems.any { it.detail.contains("不存在") })
    }

    @Test
    fun headerOnlyWavIsReported() {
        wav("empty.wav", 0, headerOnly = true)
        val r = OutputCheck.check(dir)
        assertTrue(r.problems.any { it.detail.contains("没有音频数据") })
    }

    @Test
    fun brokenHeaderIsReported() {
        Files.write(dir.resolve("bad.wav"), ByteArray(100) { 'X'.code.toByte() })
        val r = OutputCheck.check(dir)
        assertTrue(r.problems.any { it.detail.contains("RIFF") })
    }

    @Test
    fun emptyDirectorySaysSo() {
        val r = OutputCheck.check(dir)
        assertTrue(r.ok)
        assertTrue(r.summary().contains("没有 WAV/CUE"))
    }
}
