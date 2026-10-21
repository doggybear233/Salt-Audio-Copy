package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumTrackResult
import com.doggybear233.saltaudiocopy.album.ErrReport
import com.doggybear233.saltaudiocopy.rip.WavWriter
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResumeAndReportTest {

    private fun tmp(): Path = TempDirs.create("saltac-resume").resolve("a.wav")

    private fun frame(n: Int) = ByteArray(2352) { (it + n).toByte() }

    @Test
    fun `append continues after existing data`() {
        val p = tmp()
        WavWriter(p).use { w ->
            w.write(frame(1), 0, 2352)
            w.write(frame(2), 0, 2352)
        }
        assertEquals(44L + 4704, Files.size(p))

        val w2 = WavWriter(p, append = true)
        assertEquals(4704L, w2.priorDataBytes)
        w2.write(frame(3), 0, 2352)
        w2.close()

        assertEquals(44L + 7056, Files.size(p))
        val bytes = Files.readAllBytes(p)
        // 头部回填的 data 长度应为 3 扇区
        val dataLen = (bytes[40].toInt() and 0xFF) or
            ((bytes[41].toInt() and 0xFF) shl 8) or
            ((bytes[42].toInt() and 0xFF) shl 16) or
            ((bytes[43].toInt() and 0xFF) shl 24)
        assertEquals(7056, dataLen)
        // 续写的内容接在末尾
        assertEquals(3.toByte(), bytes[44 + 4704])
    }

    @Test
    fun `append on missing or foreign file starts fresh`() {
        val p = tmp()
        val fresh = WavWriter(p, append = true)
        assertEquals(0L, fresh.priorDataBytes)
        fresh.close()

        val q = tmp()
        Files.write(q, "not a wav at all, definitely longer than 44 bytes............".toByteArray())
        val w = WavWriter(q, append = true)
        assertEquals(0L, w.priorDataBytes)
        w.write(frame(9), 0, 2352)
        w.close()
        assertEquals(44L + 2352, Files.size(q))
    }

    @Test
    fun `truncateData drops partial sector`() {
        val p = tmp()
        WavWriter(p).use { w ->
            w.write(frame(1), 0, 2352)
            w.write(frame(2), 0, 100) // 半截扇区
        }
        val w2 = WavWriter(p, append = true)
        w2.truncateData(2352)
        assertEquals(2352L, w2.dataByteCount())
        assertEquals(44L + 2352, Files.size(p))
        w2.close()
    }

    private fun toc(): DiscToc = DiscToc(
        firstTrack = 1,
        lastTrack = 2,
        entries = listOf(
            TocEntry(track = 1, isAudio = true, adr = 1, lba = 0),
            TocEntry(track = 2, isAudio = true, adr = 1, lba = 5000),
            TocEntry(track = 0xAA, isAudio = true, adr = 1, lba = 10000)
        )
    )

    @Test
    fun `error report lists bad tracks and notes`() {
        val bad = AlbumTrackResult(
            number = 2, title = "道", artist = "宇多田ヒカル",
            file = Path.of("C:/tmp/02.wav"), startLba = 5000, sectors = 2352,
            crc32 = 0x1234ABCDL, errors = 3, elapsedMs = 1000
        )
        val good = bad.copy(number = 1, title = "title", errors = 0, crc32 = 1L)
        val text = ErrReport.build("A - B", toc(), listOf(bad, good), listOf("封面写入失败"))
        assertTrue(text.contains("第 02 轨"))
        assertTrue(text.contains("errors=3"))
        assertTrue(text.contains("1234ABCD"))
        assertTrue(text.contains("封面写入失败"))
        assertTrue(text.contains("2 轨"))
    }

    @Test
    fun `error report without bad tracks still lists notes`() {
        val good = AlbumTrackResult(
            number = 1, title = "t", artist = null, file = Path.of("C:/tmp/01.wav"),
            startLba = 0, sectors = 100, crc32 = 5L, errors = 0, elapsedMs = 1
        )
        val text = ErrReport.build("A - B", toc(), listOf(good), listOf("CUE 写入失败"))
        assertTrue(text.contains("没有被标记错误的音轨"))
        assertTrue(text.contains("CUE 写入失败"))
    }
}
