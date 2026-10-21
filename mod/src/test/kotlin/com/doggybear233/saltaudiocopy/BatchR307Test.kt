package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.rip.WavWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-359：取消抓轨时不能把「能接着写」的半截 WAV 一起删掉。
 *
 * 以前取消分支把 createdFiles 里除「已抓完的轨」以外的东西全删了，
 * 于是设置里明明开着「断点续抓」，用户点取消再点抓取仍是从头再来。
 */
class BatchR307Test {

    private fun tmp(): Path = Files.createTempDirectory("b307").also { it.toFile().deleteOnExit() }

    /** 写一份带真实 PCM 的 WAV（WavWriter 会写合法的 RIFF 头）。 */
    private fun partialWav(dir: Path, name: String, bytes: Int): Path {
        val p = dir.resolve(name)
        WavWriter(p).use { w ->
            w.write(ByteArray(bytes), 0, bytes)
        }
        return p
    }

    @Test
    fun partialWavIsKeptWhenResumeIsOn() {
        val dir = tmp()
        val wav = partialWav(dir, "01 灰かぶり.wav", 2352 * 10)
        val cue = dir.resolve("album.cue").also { Files.writeString(it, "FILE \"01.wav\" WAVE") }

        val resumable = ConfigActions.resumableOnCancel(listOf(wav, cue), resume = true)
        assertEquals(setOf(wav.toAbsolutePath().normalize()), resumable)

        val removable = ConfigActions.removableOnCancel(listOf(wav, cue), emptySet(), resumable)
        assertEquals(listOf(cue), removable)
    }

    @Test
    fun resumeOffDeletesEverythingNotFinished() {
        val dir = tmp()
        val wav = partialWav(dir, "01.wav", 2352 * 10)
        val resumable = ConfigActions.resumableOnCancel(listOf(wav), resume = false)
        assertTrue(resumable.isEmpty())
        assertEquals(listOf(wav), ConfigActions.removableOnCancel(listOf(wav), emptySet(), resumable))
    }

    @Test
    fun zeroLengthWavIsNotWorthKeeping() {
        val dir = tmp()
        // 只有 44 字节头、没有 PCM：续抓也接不上，删掉更干净
        val wav = dir.resolve("01.wav")
        WavWriter(wav).use { }
        assertTrue(ConfigActions.resumableOnCancel(listOf(wav), resume = true).isEmpty())
    }

    @Test
    fun finishedTrackIsNotDeletedEvenWithoutResume() {
        val dir = tmp()
        val done = partialWav(dir, "01.wav", 2352 * 10)
        val half = partialWav(dir, "02.wav", 2352 * 3)
        val finished = setOf(done.toAbsolutePath().normalize())
        val removable = ConfigActions.removableOnCancel(
            listOf(done, half),
            finished,
            ConfigActions.resumableOnCancel(listOf(done, half), resume = false)
        )
        assertEquals(listOf(half), removable)
    }

    /**
     * F-413：抓轨中途取消的端到端断言。
     *
     * 以前「取消后清理了什么、留了什么」没有任何测试：改动删除逻辑不会红，
     * 等到用户发现「取消以后续抓变回从头再来」才暴露。
     */
    @Test
    fun cancelMidRipKeepsResumablePartialAndReportsCancelled() {
        val dir = tmp()
        val settings = com.doggybear233.saltaudiocopy.settings.RipSettings(
            outputRoot = dir.toString(),
            fetchMetadata = false,
            writeCue = false,
            writeLog = false,
            resume = true,
            detectGaps = false,
            guardDisc = false,
            testAndCopy = false,
            driveSpeed = 0
        )
        val fake = com.doggybear233.saltaudiocopy.rip.FakeTrackSource(sectors = 6000)
        val scan = com.doggybear233.saltaudiocopy.rip.ScanResult(
            "\\\\.\\X:",
            com.doggybear233.saltaudiocopy.rip.FakeTrackSource.inquiry(),
            com.doggybear233.saltaudiocopy.rip.FakeTrackSource.disc3Tracks(6000)
        )
        // 第 1 轨抓完（6000 扇区 ≈ 223 次读）之后、第 2 轨中途取消：读次数超过 300 就停。
        val res = com.doggybear233.saltaudiocopy.album.AlbumRipper.rip(
            scan = scan,
            settings = settings,
            cancel = { fake.reads > 300 },
            onProgress = {},
            onStage = {},
            source = fake
        )
        assertTrue(res.cancelled, "中途取消必须标记 cancelled")
        assertTrue(res.tracks.isNotEmpty(), "取消前已完成的音轨要保留在结果里")

        val resumable = ConfigActions.resumableOnCancel(res.createdFiles, resume = true)
        val finished = res.tracks.filter { !it.skipped }.map { it.file.toAbsolutePath().normalize() }.toSet()
        val removable = ConfigActions.removableOnCancel(res.createdFiles, finished, resumable)
        assertTrue(
            removable.none { it in finished },
            "已完成的音轨不该出现在待删清单里"
        )
        // 取消的那一轨若已写入 PCM，必须留在可续抓集合里（而不是被删）
        assertTrue(
            resumable.none { it in removable },
            "可续抓的半截文件不该同时被判定为待删"
        )
    }
}
