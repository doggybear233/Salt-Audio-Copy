package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.rip.ResumeGuard
import com.doggybear233.saltaudiocopy.rip.WavWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R2-06：续抓只认「这只文件确实是这一轨」。
 *
 * 只看字节数会让另一张盘的半截文件接上来（起点 LBA 不同、gap 处理不同），
 * 拼出的音频时长对、内容全错，日志还是 100%。
 */
class BatchR206Test {

    private fun tempDir(): Path = TempDirs.create("b206")

    private fun writer(path: Path, guard: ResumeGuard?) =
        WavWriter(path, append = true, guard = guard)

    @Test
    fun guardRoundTripsThroughTheSidecar() {
        val dir = tempDir()
        val wav = dir.resolve("01 x.wav")
        writer(wav, ResumeGuard(0, 2000)).use { assertEquals(0L, it.priorDataBytes) }
        // 同一个起点再打开：能认出这是同一轨，允许续写
        writer(wav, ResumeGuard(0, 2000)).use { assertEquals(0L, it.priorDataBytes) }
    }

    @Test
    fun differentStartLbaRestartsTheTrack() {
        val dir = tempDir()
        val wav = dir.resolve("01 x.wav")
        writer(wav, ResumeGuard(0, 2000)).use { w ->
            w.write(ByteArray(2352 * 5), 0, 2352 * 5)
        }
        // 换个 gap 处理后起点变成了 100：不能把旧的 5 扇区接上来
        writer(wav, ResumeGuard(100, 2000)).use { w ->
            assertEquals(0L, w.priorDataBytes, "起点不同必须整轨重抓")
            assertEquals(44L, Files.size(wav), "旧内容应被清空")
        }
    }

    @Test
    fun missingGuardMeansNoResume() {
        val dir = tempDir()
        val wav = dir.resolve("02 y.wav")
        // 先造一个没有守卫的半截文件（老版本留下的）
        writer(wav, null).use { w -> w.write(ByteArray(2352 * 3), 0, 2352 * 3) }
        writer(wav, ResumeGuard(0, 2000)).use { w ->
            assertEquals(0L, w.priorDataBytes, "没有守卫就不能相信文件内容")
        }
    }

    @Test
    fun matchingGuardAllowsContinuation() {
        val dir = tempDir()
        val wav = dir.resolve("03 z.wav")
        writer(wav, ResumeGuard(500, 2000)).use { w -> w.write(ByteArray(2352 * 4), 0, 2352 * 4) }
        writer(wav, ResumeGuard(500, 2000)).use { w ->
            assertEquals(2352L * 4, w.priorDataBytes)
        }
    }

    @Test
    fun guardFileLivesInTheCacheFolder() {
        val dir = tempDir()
        val wav = dir.resolve("04 k.wav")
        writer(wav, ResumeGuard(0, 10)).use { }
        val cache = dir.resolve(".saltaudiocopy")
        assertTrue(Files.isDirectory(cache), "守卫文件应放在 .saltaudiocopy 里，不污染输出目录")
        assertTrue(Files.list(cache).use { s -> s.anyMatch { it.fileName.toString().startsWith("resume-") } })
        assertTrue(Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().startsWith("resume-") } })
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(ResumeGuard.decode("hello"))
        assertEquals(ResumeGuard(3, 4, 44100, 2, 16), ResumeGuard.decode("startLba=3\nsectors=4\n"))
    }
}
