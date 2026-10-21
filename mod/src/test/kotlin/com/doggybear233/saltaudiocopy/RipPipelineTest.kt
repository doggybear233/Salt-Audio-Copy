package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.rip.FakeTrackSource
import com.doggybear233.saltaudiocopy.rip.RipEngine
import com.doggybear233.saltaudiocopy.rip.ScanResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 整条抓轨流水线的假驱动器测试。
 *
 * 这些用例覆盖的是过去只能靠真机、或者只能靠我「推理」的部分：
 * 断点续抓、verify 重试、C2 降级、gap 计入、取消。
 * 其中 resume 那条正是 0.5.0 引入的那个 bug 的守护测试。
 */
class RipPipelineTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = TempDirs.create("saltac-pipe")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun scan(sectors: Int, leadOut: Int = sectors) = ScanResult(
        "\\\\.\\X:",
        FakeTrackSource.inquiry(),
        FakeTrackSource.disc3Tracks(leadOut)
    )

    private fun fullCrc(fake: FakeTrackSource, from: Int, count: Int): Long {
        val crc = CRC32()
        for (lba in from until from + count) crc.update(fake.sector(lba))
        return crc.value
    }

    @Test
    fun ripsWholeTrackWithMatchingCrc() {
        val fake = FakeTrackSource(sectors = 6000)
        val out = dir.resolve("01.wav")
        var last = 0
        val r = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = out,
            cancel = { false }, onProgress = { last = it.sectorsDone }, source = fake
        )
        assertEquals(2000, r.sectors)
        assertEquals(2000L * 2352, r.bytes)
        assertEquals(fullCrc(fake, 0, 2000), r.crc32)
        assertEquals(2000, last)
        assertEquals(44L + 2000L * 2352, Files.size(out))
        assertFalse(r.cancelled)
    }

    /** 0.5.0 那个 bug 的守护：半截文件必须原地续写，而不是另存一份。 */
    @Test
    fun resumesPartialFileAndEndsWithSameCrc() {
        val fake = FakeTrackSource(sectors = 6000)
        val whole = dir.resolve("whole.wav")
        val full = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = whole,
            cancel = { false }, onProgress = {}, source = fake
        )

        // 造一个「抓到 800 扇区时崩了」的半截文件
        val partial = dir.resolve("partial.wav")
        val bytes = Files.readAllBytes(whole)
        Files.write(partial, bytes.copyOf(44 + 800 * 2352))

        val resumed = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = partial,
            cancel = { false }, onProgress = {}, resume = true, source = fake
        )
        assertEquals(full.crc32, resumed.crc32, "续抓后的 CRC 必须与整轨一致")
        assertEquals(full.sectors.toLong() * 2352, resumed.bytes)
        assertEquals(44L + 2000L * 2352, Files.size(partial))
    }

    /**
     * 续抓 + 同名完整文件：以前 append 会从文件尾再写一遍，得到的 WAV 是「两遍音频」，
     * 时长翻倍、CRC 全错，但日志显示 100% 完成。
     */
    @Test
    fun resumeOnCompleteFileKeepsItInsteadOfAppendingTwice() {
        val fake = FakeTrackSource(sectors = 6000)
        val out = dir.resolve("full.wav")
        val full = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = out,
            cancel = { false }, onProgress = {}, source = fake
        )
        assertEquals(44L + 2000L * 2352, Files.size(out))
        val readsAfterFirstRip = fake.reads

        var progress = -1
        val again = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = out,
            cancel = { false }, onProgress = { progress = it.sectorsDone }, resume = true, source = fake
        )
        assertEquals(full.crc32, again.crc32)
        assertTrue(again.completedBefore, "命中「上一轮已抓完」要标记出来，调用方据此跳过 offset 二次校正")
        assertEquals(
            44L + 2000L * 2352, Files.size(out),
            "续抓遇到完整旧文件不能追加成两遍"
        )
        assertEquals(readsAfterFirstRip, fake.reads, "完整文件不该再被读一遍")
        assertEquals(2000, progress, "整轨跳过的续抓也要发一次进度，否则进度条停在上一轨")
    }

    /**
     * 进程被强杀时头部 data 长度还是 0：必须按文件实际长度续抓，否则从 0 重来、
     * 把已经读好的部分再写一遍。
     */
    @Test
    fun resumeUsesFileLengthWhenHeaderSaysZero() {
        val fake = FakeTrackSource(sectors = 6000)
        val whole = dir.resolve("whole2.wav")
        val full = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = whole,
            cancel = { false }, onProgress = {}, source = fake
        )
        val partial = dir.resolve("crash.wav")
        val bytes = Files.readAllBytes(whole).copyOf(44 + 800 * 2352)
        // 模拟「写到一半被强杀」：长度字段没回填
        for (i in 40..43) bytes[40 + (i - 40)] = 0
        Files.write(partial, bytes)

        val resumed = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = partial,
            cancel = { false }, onProgress = {}, resume = true, source = fake
        )
        assertEquals(full.crc32, resumed.crc32)
        assertEquals(44L + 2000L * 2352, Files.size(partial))
    }

    /**
     * 「读不稳」必须进入结果，而不是只写一行日志：以前用尽重试仍不一致也报告 100% 质量。
     */
    @Test
    fun unstableChunksAreReportedWhenReadsNeverAgree() {
        val fake = FakeTrackSource(sectors = 6000)
        val jitter = object : com.doggybear233.saltaudiocopy.rip.TrackSource by fake {
            private var n = 0
            override fun readAudioSectors(lba: Int, count: Int): ByteArray {
                n++
                val b = fake.readAudioSectors(lba, count)
                b[0] = (b[0].toInt() xor n).toByte()
                return b
            }
        }
        val r = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = dir.resolve("jitter.wav"),
            cancel = { false }, onProgress = {}, verify = true, maxRetries = 2, source = jitter
        )
        assertTrue(r.unstableChunks > 0, "每次都不同时必须记录为不稳定块")
    }

    @Test
    fun verifyRetriesUntilTwoReadsAgree() {        val fake = FakeTrackSource(sectors = 6000, corruptFirstRead = mutableSetOf(288))
        val out = dir.resolve("01.wav")
        val r = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = out,
            cancel = { false }, onProgress = {}, verify = true, maxRetries = 8, source = fake
        )
        assertTrue(r.readErrors > 0, "应当记录到重试")
        assertEquals(fullCrc(fake, 0, 2000), r.crc32)
    }

    @Test
    fun c2UnsupportedFallsBackWithoutFailing() {
        val fake = FakeTrackSource(sectors = 6000, c2Unsupported = true)
        val out = dir.resolve("01.wav")
        val r = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = out,
            cancel = { false }, onProgress = {}, useC2 = true, source = fake
        )
        assertEquals(fullCrc(fake, 0, 2000), r.crc32)
        assertTrue(fake.c2Reads >= 1, "应当尝试过 C2")
    }

    @Test
    fun c2ReportedErrorsTriggerReRead() {
        val fake = FakeTrackSource(sectors = 6000, c2Bad = mutableSetOf(288))
        val out = dir.resolve("01.wav")
        val r = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = out,
            cancel = { false }, onProgress = {}, useC2 = true, maxRetries = 2, retryDelayMs = 0, source = fake
        )
        // C2 报错只影响重读次数，最终数据仍以读到的为准
        assertEquals(fullCrc(fake, 0, 2000), r.crc32)
        assertTrue(fake.c2Reads >= 2, "报错后应当重读")
    }

    @Test
    fun cancellationStopsAtChunkBoundary() {
        val fake = FakeTrackSource(sectors = 6000)
        val out = dir.resolve("01.wav")
        var calls = 0
        val r = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = out,
            cancel = { calls++ > 2 }, onProgress = {}, source = fake
        )
        assertTrue(r.cancelled)
        assertTrue(r.sectors < 2000, "取消后不应写完整轨")
        assertEquals(44L + r.sectors.toLong() * 2352, Files.size(out))
    }

    @Test
    fun gapIsDetectedAndCanBeIncluded() {
        // 第 2 轨从 2000 起；1997..1999 的 Q 报 index=0（前一轨的间隙）
        val fake = FakeTrackSource(
            sectors = 6000,
            qAt = { lba ->
                when {
                    lba in 1997..1999 -> FakeTrackSource.q(0, 1, 0, lba + 150)
                    lba in 0..5999 -> FakeTrackSource.q(0, 2, 1, lba + 150)
                    else -> null
                }
            }
        )
        val out = dir.resolve("02.wav")
        val r = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 1, outFile = out,
            cancel = { false }, onProgress = {},
            detectGaps = true, gapFloorLba = 1800, gapMode = "prepend", source = fake
        )
        assertEquals(3, r.gapSectors)
        assertEquals(2000 + 3, r.sectors, "计入间隙后应当多读 3 个扇区")
    }

    @Test
    fun gapIsReportedButNotIncludedByDefault() {
        val fake = FakeTrackSource(
            sectors = 6000,
            qAt = { lba -> if (lba in 1997..1999) FakeTrackSource.q(0, 1, 0, lba + 150) else FakeTrackSource.q(0, 2, 1, lba + 150) }
        )
        val r = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 1, outFile = dir.resolve("02.wav"),
            cancel = { false }, onProgress = {},
            detectGaps = true, gapFloorLba = 1800, source = fake
        )
        assertEquals(0, r.gapSectors, "默认（附加到上一轨）不会把间隙写进本轨")
        assertEquals(2000, r.sectors, "默认不改变音轨边界")
    }

    /** 间隙归属三档：leaveout 要按下一轨间隙裁掉尾部。 */
    @Test
    fun leaveoutTrimsTrailingGap() {
        val fake = FakeTrackSource(sectors = 6000)
        val r = RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 1, outFile = dir.resolve("02.wav"),
            cancel = { false }, onProgress = {},
            gapMode = "leaveout", nextGapSectors = 3, source = fake
        )
        assertEquals(1997, r.sectors, "丢弃 3 扇区尾部间隙")
        assertEquals(0, r.gapSectors)
        assertEquals(44L + 1997L * 2352, java.nio.file.Files.size(dir.resolve("02.wav")))
    }

    @Test
    fun cacheDefeatInsertsDistantReads() {
        val fake = FakeTrackSource(sectors = 6000)
        RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = dir.resolve("cd.wav"),
            cancel = { false }, onProgress = {}, verify = true, writeFile = false,
            defeatCache = true, source = fake
        )
        val probes = fake.log.filter { it.second == 1 }
        assertTrue(probes.size >= 2, "每次重读前都应插一次 1 扇区远端读，实际 ${probes.size} 次")
        assertTrue(probes.all { it.first > 16 }, "远端读不应落在正在抓的块内: $probes")
    }

    @Test
    fun cacheDefeatOffMeansNoProbeReads() {
        val fake = FakeTrackSource(sectors = 6000)
        RipEngine.ripTrack(
            scan = scan(6000), trackIndex = 0, outFile = dir.resolve("ncd.wav"),
            cancel = { false }, onProgress = {}, verify = true, writeFile = false,
            source = fake
        )
        assertEquals(0, fake.log.count { it.second == 1 }, "没开缓存击破时不该有额外的 1 扇区读")
    }
}
