package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumTrackResult
import com.doggybear233.saltaudiocopy.album.CueWriter
import com.doggybear233.saltaudiocopy.album.LogWriter
import com.doggybear233.saltaudiocopy.album.PcmOffset
import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import com.doggybear233.saltaudiocopy.rip.WavWriter
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WritersTest {

    private val album = AlbumMeta(
        albumArtist = "宇多田ヒカル",
        albumTitle = "Fantôme",
        year = "2016",
        genre = "J-Pop",
        discId = "o6OqqpbGy_JOCeJyEftrU1T0L6E-",
        cddbId = "b2060bae",
        source = "musicbrainz",
        tracks = listOf(
            TrackMeta(1, "道", "宇多田ヒカル", 221000),
            TrackMeta(2, "俺の彼女", "宇多田ヒカル", 238000)
        )
    )

    private val toc = DiscToc(
        1, 2,
        listOf(TocEntry(1, true, 1, 0), TocEntry(2, true, 1, 16575), TocEntry(0xAA, true, 1, 34425))
    )

    private fun results(dir: java.nio.file.Path) = listOf(
        AlbumTrackResult(1, "道", "宇多田ヒカル", dir.resolve("01 道.wav"), 0, 16575, 0x1234ABCD, 0, 1000),
        AlbumTrackResult(2, "俺の彼女", "宇多田ヒカル", dir.resolve("02 俺の彼女.wav"), 16575, 17850, 0x0BADF00D, 2, 1200)
    )

    @Test
    fun cueStartsWithBomAndUsesCrlf() {
        val dir = Files.createTempDirectory("saltac-cue")
        val text = CueWriter.build(album, results(dir), listOf("01 道.wav", "02 俺の彼女.wav"))
        assertTrue(text.startsWith("\uFEFF"))
        assertTrue(text.contains("\r\n"))
        assertTrue(!text.replace("\r\n", "").contains("\n"))
        assertTrue(text.contains("PERFORMER \"宇多田ヒカル\""))
        assertTrue(text.contains("TITLE \"Fantôme\""))
        assertTrue(text.contains("FILE \"01 道.wav\" WAVE"))
        assertTrue(text.contains("  TRACK 01 AUDIO"))
        assertTrue(text.contains("    INDEX 01 00:00:00"))
        assertTrue(text.contains("REM DISCID o6OqqpbGy_JOCeJyEftrU1T0L6E-"))
        assertTrue(text.contains("REM GENRE J-Pop"))
        assertTrue(text.contains("REM DATE 2016"))
    }

    @Test
    fun cueQuotesAreSanitized() {
        val dir = Files.createTempDirectory("saltac-cue2")
        val a = album.copy(albumTitle = "A \"quoted\" album")
        val text = CueWriter.build(a, results(dir), listOf("a.wav", "b.wav"))
        assertTrue(text.contains("TITLE \"A 'quoted' album\""))
    }

    @Test
    fun cueWriteAddsBomBytes() {
        val dir = Files.createTempDirectory("saltac-cue3")
        val p = dir.resolve("t.cue")
        CueWriter.write(p, CueWriter.build(album, results(dir), listOf("a.wav", "b.wav")))
        val bytes = Files.readAllBytes(p)
        assertEquals(0xEF.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])
    }

    @Test
    fun logContainsPerTrackCrcAndSummary() {
        val dir = Files.createTempDirectory("saltac-log")
        val text = LogWriter.build(
            "0.2.0", "HL-DT-ST BD-RE BP71N", toc, album, results(dir), 12_345, false,
            listOf("secureMode" to "true", "offsetSamples" to "0"), listOf("test note")
        )
        assertTrue(text.contains("Fantôme"))
        assertTrue(text.contains("1234ABCD"))
        assertTrue(text.contains("0BADF00D"))
        assertTrue(text.contains("重试"))
        assertTrue(text.contains("全盘 CRC 合并"))
        assertTrue(text.contains("test note"))
        assertTrue(text.contains("secureMode"))
    }

    @Test
    fun wavWriterWritesValidHeader() {
        val dir = Files.createTempDirectory("saltac-wav")
        val p = dir.resolve("a.wav")
        val data = ByteArray(2352) { (it % 251).toByte() }
        WavWriter(p).use { w ->
            w.write(data, 0, data.size)
            assertEquals(2352L, w.dataByteCount())
        }
        val bytes = Files.readAllBytes(p)
        assertEquals(2352 + 44, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        val riffSize = java.nio.ByteBuffer.wrap(bytes, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(2352 + 36, riffSize)
        val dataSize = java.nio.ByteBuffer.wrap(bytes, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(2352, dataSize)
    }

    @Test
    fun pcmOffsetPositiveShiftsDataAndKeepsLength() {
        val dir = Files.createTempDirectory("saltac-off")
        val p = dir.resolve("a.wav")
        val data = ByteArray(64) { it.toByte() }
        WavWriter(p).use { it.write(data, 0, data.size) }
        PcmOffset.apply(p, 4) // 丢弃前 4 个采样 = 16 字节
        val bytes = Files.readAllBytes(p)
        assertEquals(44 + 64, bytes.size)
        // 头部保留，数据左移 16 字节，末尾 16 字节为 0
        assertEquals(16, bytes[44].toInt())
        assertEquals(0, bytes[44 + 48].toInt())
    }

    @Test
    fun pcmOffsetNegativeShiftsDataRight() {
        val dir = Files.createTempDirectory("saltac-off2")
        val p = dir.resolve("a.wav")
        val data = ByteArray(64) { it.toByte() }
        WavWriter(p).use { it.write(data, 0, data.size) }
        PcmOffset.apply(p, -4)
        val bytes = Files.readAllBytes(p)
        assertEquals(0, bytes[44].toInt())
        assertEquals(0, bytes[44 + 15].toInt())
        // 右移 4 个采样 = 16 字节：开头 16 字节补零，原数据整体后移
        assertEquals(1, bytes[44 + 17].toInt(), "原第 1 字节应落在 +17")
        assertEquals(47, bytes[44 + 64 - 1].toInt(), "原数据最后一个字节（47）应落在末尾")
        assertEquals(0, bytes[44].toInt())
        assertEquals(64 + 44, bytes.size)
    }

    @Test
    fun pcmOffsetBeyondDataSilencesEverythingWithoutThrowing() {
        // 用户把偏移设成 ±2000 samples，而小文件只有 64 字节：要么整段静音、要么保持原样，
        // 但绝不能越界写坏文件（以前没有这条用例，越界行为无人知道）
        val dir = Files.createTempDirectory("saltac-off3")
        val p = dir.resolve("a.wav")
        val data = ByteArray(64) { it.toByte() }
        WavWriter(p).use { it.write(data, 0, data.size) }
        PcmOffset.apply(p, 2000)
        val bytes = Files.readAllBytes(p)
        assertEquals(44 + 64, bytes.size, "长度不能变")
        assertEquals(64, bytes.drop(44).count { it.toInt() == 0 }, "整段应为静音")
    }

    @Test
    fun pcmOffsetIgnoresTrailingBytesThatAreNotAFullSample() {
        val dir = Files.createTempDirectory("saltac-off4")
        val p = dir.resolve("a.wav")
        val data = ByteArray(62) { it.toByte() }   // 不是 4 的倍数
        WavWriter(p).use { it.write(data, 0, data.size) }
        PcmOffset.apply(p, 4)
        val bytes = Files.readAllBytes(p)
        assertEquals(44 + 62, bytes.size, "非整采样长度不能让文件变长")
        assertEquals(60, bytes[44 + 60].toInt(), "不足一个采样时整段保持原样")
        assertEquals(61, bytes[44 + 61].toInt())
    }

    @Test
    fun pcmOffsetNoopOnZero() {
        val dir = Files.createTempDirectory("saltac-off3")
        val p = dir.resolve("a.wav")
        val data = ByteArray(32) { it.toByte() }
        WavWriter(p).use { it.write(data, 0, data.size) }
        val before = Files.readAllBytes(p)
        PcmOffset.apply(p, 0)
        assertTrue(before.contentEquals(Files.readAllBytes(p)))
    }

    /** 日志要能看出「质量/峰值」和 Test&Copy 的测试 CRC（对齐 EAC 日志的可读性）。 */
    @Test
    fun logReportsQualityPeakAndTestCrc() {
        val dir = Files.createTempDirectory("saltac-log2")
        val track = AlbumTrackResult(
            number = 1, title = "道", artist = "宇多田ヒカル",
            file = dir.resolve("01 道.wav"),
            startLba = 0, sectors = 1000, crc32 = 0x1234ABCDL,
            errors = 5, elapsedMs = 5000, peakLevel = 1.0,
            testCrc32 = 0x1234ABCDL
        )
        val clean = track.copy(number = 2, file = dir.resolve("02.wav"), errors = 0, peakLevel = 0.5, testCrc32 = 0xDEADBEEFL)
        val text = LogWriter.build(
            "0.11.0", "HL-DT-ST", toc, album, listOf(track, clean), 5000, false,
            listOf("secureMode" to "true"), emptyList()
        )
        assertTrue("质量" in text, "日志应有质量列")
        assertTrue("99.5%" in text, "5 次重试 / 1000 扇区 ≈ 99.5%：\n$text")
        assertTrue("100.0%" in text)
        assertTrue("  0.0 " in text, "峰值 1.0 应写成 0.0 dBFS 列：\n$text")
        assertTrue(" -6.0 " in text, "峰值 0.5 应写成 -6.0 dBFS 列：\n$text")
        assertTrue("DEADBEEF" in text, "有 testCrc 时应有测试 CRC 列")
        assertTrue("不一致" in text, "测试/复制 CRC 不同要标出来")
        assertTrue("整体质量" in text)
        assertTrue("最高峰值" in text)
    }

    @Test
    fun qualityIsClampedAndNeverNegative() {
        val dir = Files.createTempDirectory("saltac-log3")
        val bad = AlbumTrackResult(
            number = 1, title = "x", artist = null, file = dir.resolve("01.wav"),
            startLba = 0, sectors = 10, crc32 = 0, errors = 50, elapsedMs = 1, peakLevel = 0.0
        )
        assertEquals(0.0, bad.qualityPct, "重试超过扇区数时应钳到 0")
        val log = LogWriter.build("v", "d", toc, album, listOf(bad), 1, false, emptyList(), emptyList())
        assertTrue("-inf" in log, "静音应显示 -inf")
    }
}
