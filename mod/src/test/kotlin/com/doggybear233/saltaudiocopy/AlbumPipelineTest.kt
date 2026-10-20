package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumRipper
import com.doggybear233.saltaudiocopy.rip.FakeTrackSource
import com.doggybear233.saltaudiocopy.rip.ScanResult
import com.doggybear233.saltaudiocopy.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 整张专辑的端到端测试（假驱动器，无光驱、无网络）。
 *
 * 覆盖的是「把零件装起来」这一层：模板命名、目录、CUE、日志、封面缺失、
 * Test&Copy、断点续抓、换碟守卫跳过。这些以前全部只能靠真机验证。
 */
class AlbumPipelineTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltac-album")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun settings() = RipSettings(
        outputRoot = dir.toString(),
        folderTemplate = "%albumartist%/%albumtitle%",
        fileTemplate = "%tracknr2% %title%",
        fetchMetadata = false,
        writeCue = true,
        writeLog = true,
        secureMode = true,
        testAndCopy = false,
        useCdText = true,
        guardDisc = true,
        resume = true,
        detectGaps = false,
        gapMode = "append"
    )

    /**
     * WAV 的 data 区长度。
     *
     * 0.25 起抓轨完成后会给 WAV 追加 RIFF `LIST/INFO` 标签（资源管理器可见的
     * 标题/艺术家），所以文件总大小不再等于 44+数据；断言必须看数据区。
     */
    private fun dataBytes(p: Path): Long =
        com.doggybear233.saltaudiocopy.rip.WavInfo.read(p)?.dataBytes
            ?: error("不是合法 WAV：$p")

    /** 只允许 fmt + data 两个块；出现 LIST/id3 说明标签被拼进了镜像。 */
    private fun assertChunksAreOnlyAudio(p: Path) {
        val bytes = Files.readAllBytes(p)
        val ids = ArrayList<String>()
        var off = 12
        while (off + 8 <= bytes.size) {
            val id = String(bytes, off, 4, Charsets.US_ASCII)
            val size = (bytes[off + 4].toLong() and 0xFF) or
                ((bytes[off + 5].toLong() and 0xFF) shl 8) or
                ((bytes[off + 6].toLong() and 0xFF) shl 16) or
                ((bytes[off + 7].toLong() and 0xFF) shl 24)
            ids += id
            off += 8 + size.toInt() + (size.toInt() and 1)
        }
        assertEquals(listOf("fmt ", "data"), ids, "镜像 WAV 只应含 fmt + data，实际 $ids：$p")
    }

    private fun fake() = FakeTrackSource(sectors = 6000)

    private fun scan() = ScanResult("\\\\.\\X:", FakeTrackSource.inquiry(), FakeTrackSource.disc3Tracks(6000))

    /**
     * offset 校正会改写 PCM：报告里的 CRC 必须是**校正后**文件的 CRC，
     * 否则日志里的校验值和用户手上的文件对不上。
     */
    @Test
    fun offsetShiftRecomputesReportedCrc() {
        val plain = AlbumRipper.rip(
            scan = scan(), settings = settings().copy(offsetSamples = 0),
            cancel = { false }, onProgress = {}, onStage = {}, onlyTracks = setOf(1), source = fake()
        )
        val shifted = AlbumRipper.rip(
            scan = scan(), settings = settings().copy(offsetSamples = 37),
            cancel = { false }, onProgress = {}, onStage = {}, onlyTracks = setOf(1), source = fake()
        )
        val plainTrack = plain.tracks.first()
        val shiftedTrack = shifted.tracks.first()
        assertTrue(plainTrack.crc32 != shiftedTrack.crc32, "偏移后 CRC 必须变")
        assertEquals(
            AlbumRipper.wavStats(shiftedTrack.file).first, shiftedTrack.crc32,
            "报告的 CRC 必须是文件内容的 CRC"
        )
    }

    @Test
    fun ripsWholeAlbumWithPlaceholderNames() {        val out = AlbumRipper.rip(
            scan = scan(), settings = settings(), cancel = { false },
            onProgress = {}, onlyTracks = null, source = fake()
        )
        assertEquals(3, out.tracks.size)
        assertTrue(!out.cancelled)

        val albumDir = dir.resolve("未知艺术家").resolve("未知专辑")
        assertTrue(Files.isDirectory(albumDir), "专辑目录应存在：$albumDir")

        for (n in 1..3) {
            val wav = albumDir.resolve(String.format("%02d Track %02d.wav", n, n))
            assertTrue(Files.exists(wav), "缺少 $wav")
            assertEquals(2000L * 2352, dataBytes(wav), "第 $n 轨应有 2000 扇区")
        }

        val cue = Files.list(albumDir).use { s -> s.filter { it.toString().endsWith(".cue") }.findFirst().orElse(null) }
        assertTrue(cue != null, "应写出 CUE")
        val cueText = Files.readString(cue!!)
        assertEquals(3, Regex("TRACK \\d+ AUDIO").findAll(cueText).count())
        assertTrue(cueText.contains("INDEX 01 00:00:00"))

        val log = Files.list(albumDir).use { s -> s.filter { it.toString().endsWith(".log") }.findFirst().orElse(null) }
        assertTrue(log != null, "应写出日志")
        assertTrue(Files.readString(log!!).contains("CRC32"), "日志应包含 CRC 列")
    }

    @Test
    fun onlySelectedTracksAreRipped() {
        val out = AlbumRipper.rip(
            scan = scan(), settings = settings(), cancel = { false },
            onProgress = {}, onlyTracks = setOf(1, 3), source = fake()
        )
        assertEquals(2, out.tracks.size)
        assertEquals(listOf(1, 3), out.tracks.map { it.number })
    }

    @Test
    fun testAndCopyPathProducesSameCrc() {
        val s = settings().copy(testAndCopy = true)
        val out = AlbumRipper.rip(
            scan = scan(), settings = s, cancel = { false },
            onProgress = {}, onlyTracks = setOf(1), source = fake()
        )
        assertEquals(1, out.tracks.size)
        assertTrue(out.tracks[0].errors == 0)
    }

    @Test
    fun secondRunSuffixesInsteadOfClobbering() {
        AlbumRipper.rip(scan(), settings(), { false }, {}, onlyTracks = setOf(1), source = fake())
        val again = AlbumRipper.rip(scan(), settings(), { false }, {}, onlyTracks = setOf(1), source = fake())
        assertTrue(again.tracks.isNotEmpty())
        val albumDir = dir.resolve("未知艺术家").resolve("未知专辑")
        val wavs = Files.list(albumDir).use { s -> s.filter { it.toString().endsWith(".wav") }.toList() }
        // 第二次运行时 01.wav 已完整 → 按 suffix 策略另存为 "01 Track 01 (2).wav"
        assertEquals(2, wavs.size, "应保留两份：$wavs")
    }

    @Test
    fun cancellationMidAlbumStopsCleanly() {
        var calls = 0
        val out = AlbumRipper.rip(
            scan = scan(), settings = settings(), cancel = { calls++ > 4 },
            onProgress = {}, onlyTracks = null, source = fake()
        )
        assertTrue(out.cancelled)
        assertTrue(out.tracks.size < 3, "取消后不应抓完整张")
    }

    @Test
    fun imageModeWritesSingleFileWithAbsoluteCue() {
        val s = settings().copy(imageMode = true)
        val out = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = null, source = fake())

        val albumDir = dir.resolve("未知艺术家").resolve("未知专辑")
        val wavs = Files.list(albumDir).use { st -> st.filter { it.toString().endsWith(".wav") }.toList() }
        assertEquals(1, wavs.size, "镜像模式下只应留一个 WAV：$wavs")
        val image = wavs[0]
        // 3 轨 × 2000 扇区
        assertEquals(3L * 2000L * 2352L, dataBytes(image))
        // 文件总长必须正好是「头 + 音频」：每轨拼接前都可能被追加过标签，
        // 若按文件长度而不是 data 长度拼接，标签字节会混进 PCM（听感错位、CUE 全错）。
        // 镜像本身在拼接后会被写上专辑级标签，所以这里只核对 data 区与 PCM 内容。
        assertEquals(44L + 3L * 2000L * 2352L, dataBytes(image) + 44L, "镜像里不能夹带逐轨标签字节")
        val bytes = Files.readAllBytes(image)
        val dataStart = 44
        assertTrue(
            bytes.size >= dataStart + 3 * 2000 * 2352,
            "音频区比预期短：${bytes.size} 字节"
        )

        val cues = Files.list(albumDir).use { st -> st.filter { it.toString().endsWith(".cue") }.toList() }
        assertEquals(1, cues.size)
        val cue = String(Files.readAllBytes(cues[0]), Charsets.UTF_8)
        assertEquals(1, Regex("(?m)^FILE ").findAll(cue).count(), "镜像 CUE 只能有一个 FILE")
        assertTrue(cue.contains(image.fileName.toString()))
        // 绝对时间：0 / 2000 / 4000 扇区 → 00:00:00 / 00:26:50 / 00:53:25
        assertTrue(cue.contains("INDEX 01 00:00:00"), cue)
        assertTrue(cue.contains("INDEX 01 00:26:50"), cue)
        assertTrue(cue.contains("INDEX 01 00:53:25"), cue)
        // 每轨结果都指向同一个镜像文件
        assertTrue(out.tracks.all { it.file == image })
    }

    @Test
    fun imageModeKeepsPerTrackFilesWhenCancelled() {
        var calls = 0
        val s = settings().copy(imageMode = true)
        val out = AlbumRipper.rip(scan(), s, { calls++ > 4 }, {}, onlyTracks = null, source = fake())
        assertTrue(out.cancelled)
        val albumDir = dir.resolve("未知艺术家").resolve("未知专辑")
        val wavs = Files.list(albumDir).use { st -> st.filter { it.toString().endsWith(".wav") }.toList() }
        assertTrue(wavs.size >= 1, "取消时不应丢已抓好的音轨：$wavs")
    }

    @Test
    fun prescanGapsAreReusedDuringRip() {
        // 第 2 轨前 10 扇区是 INDEX 00；预扫应把它算进第 2 轨，且抓轨阶段不再读 Q
        val fake = FakeTrackSource(
            sectors = 6000,
            qAt = { lba ->
                when (lba) {
                    in 1990..1999 -> FakeTrackSource.q(0, 2, 0, lba + 150)
                    in 2000..5999 -> FakeTrackSource.q(0, if (lba < 4000) 2 else 3, 1, lba + 150)
                    else -> FakeTrackSource.q(0, 1, 1, lba + 150)
                }
            }
        )
        val s = settings().copy(analyzeBeforeRip = true, gapMode = "prepend")
        val out = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = null, source = fake)
        assertEquals(10, out.tracks[1].gapSectors, "预扫得到的间隙应传给第 2 轨")
        assertEquals(0, out.tracks[0].gapSectors)
        // includeGaps 打开时文件应包含间隙扇区
        val second = out.tracks[1]
        assertEquals(second.sectors * 2352L, dataBytes(second.file))
    }

    /** 间隙三档：prepend 把前间隙并进本轨，CUE 必须写 INDEX 00 + 偏移的 INDEX 01。 */
    @Test
    fun prependModeMovesGapIntoFollowingTrack() {
        val fake = FakeTrackSource(
            sectors = 6000,
            qAt = { lba ->
                // 第 2 轨 INDEX 01 = 2000，前 10 个扇区是 INDEX 00
                if (lba in 1990..1999) FakeTrackSource.q(0, 1, 0, lba + 150)
                else FakeTrackSource.q(0, 2, 1, lba + 150)
            }
        )
        val out = AlbumRipper.rip(
            scan = scan(), settings = settings().copy(gapMode = "prepend"), cancel = { false },
            onProgress = {}, onlyTracks = setOf(2), source = fake
        )
        val f = out.tracks.single().file
        assertEquals(2010L * 2352, dataBytes(f), "第 2 轨应包含 10 扇区前间隙")
        val cue = Files.readString(out.cueFile)
        assertTrue("INDEX 00 00:00:00" in cue, "prepend 必须写 INDEX 00：\n$cue")
        assertTrue("INDEX 01 00:00:10" in cue, "INDEX 01 应在 10 扇区处：\n$cue")
    }

    /** leaveout：裁掉尾部间隙，CUE 只有 INDEX 01。 */
    @Test
    fun leaveoutModeTrimsGapBeforeNextTrack() {
        val fake = FakeTrackSource(
            sectors = 6000,
            qAt = { lba ->
                if (lba in 1990..1999) FakeTrackSource.q(0, 1, 0, lba + 150)
                else FakeTrackSource.q(0, 2, 1, lba + 150)
            }
        )
        val out = AlbumRipper.rip(
            scan = scan(), settings = settings().copy(gapMode = "leaveout"), cancel = { false },
            onProgress = {}, onlyTracks = setOf(1), source = fake
        )
        val f = out.tracks.single().file
        assertEquals(1990L * 2352, dataBytes(f), "第 1 轨应裁掉第 2 轨前的 10 扇区间隙")
        val cue = Files.readString(out.cueFile)
        assertTrue("INDEX 01 00:00:00" in cue)
        assertFalse("INDEX 00" in cue, "leaveout 不应写 INDEX 00")
    }

    /** 镜像只在 append 下成立：prepend 时必须跳过并留下说明。 */
    @Test
    fun imageModeIsSkippedWhenGapModeIsNotAppend() {
        val out = AlbumRipper.rip(
            scan = scan(), settings = settings().copy(gapMode = "prepend", imageMode = true),
            cancel = { false }, onProgress = {}, onlyTracks = setOf(1, 2), source = fake()
        )
        assertEquals(2, out.tracks.map { it.file }.distinct().size, "prepend 下不应合并成单文件镜像")
        assertTrue(Files.exists(out.tracks[0].file), "逐轨文件仍应存在")
        val log = Files.readString(out.logFile)
        assertTrue("跳过整盘镜像" in log, "日志应说明原因：\n$log")
    }

    @Test
    fun writeAlbumJsonRoundTripsThroughOverride() {
        val s = settings().copy(writeAlbumJson = true)
        AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        val albumDir = dir.resolve("未知艺术家").resolve("未知专辑")
        val json = albumDir.resolve("album.json")
        assertTrue(Files.isRegularFile(json), "应写出 album.json：$albumDir")
        val meta = com.doggybear233.saltaudiocopy.meta.MetaOverride.load(json)
        assertTrue(meta != null, "写出的 album.json 必须能被 MetaOverride 读回")
        assertEquals("未知专辑", meta!!.albumTitle)
        assertTrue(meta.tracks.isNotEmpty(), "应记录音轨")
        assertTrue(meta.tracks.first().title.isNotBlank(), "占位标题不应为空")
    }

    @Test
    fun albumJsonIsNotWrittenWhenDisabled() {
        AlbumRipper.rip(scan(), settings(), { false }, {}, onlyTracks = setOf(1), source = fake())
        val albumDir = dir.resolve("未知艺术家").resolve("未知专辑")
        assertFalse(Files.exists(albumDir.resolve("album.json")), "默认不应产生 album.json")
    }

    @Test
    fun secondRipOfSameDiscReusesCachedAlbumJsonOffline() {
        // 第一次：联网关闭 → 占位名，同时把元数据副本写进 .saltaudiocopy 缓存
        val s = settings().copy(writeAlbumJson = true, fetchMetadata = false)
        AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())

        // 手工修正缓存（模拟用户改过专辑名）
        val root = settings().effectiveRoot
        val cache = com.doggybear233.saltaudiocopy.album.DiscCache.path(root, com.doggybear233.saltaudiocopy.meta.DiscId.musicBrainz(scan().toc))
        assertTrue(Files.isRegularFile(cache), "应写出每盘缓存：$cache")
        val fixed = Files.readString(cache).replace("未知艺术家", "宇多田ヒカル").replace("未知专辑", "Fantôme")
        Files.writeString(cache, fixed)

        // 第二次：同样的盘、同样的设置，离线也应命中缓存，目录名用修正后的值
        val again = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        val dir = again.outDir
        assertEquals("宇多田ヒカル", dir.parent.fileName.toString(), "应复用缓存的专辑艺术家")
        assertEquals("Fantôme", dir.fileName.toString(), "应复用缓存的专辑名")
        assertTrue(again.notes.any { it.contains("使用上次保存的元数据") }, "日志应说明命中了缓存：${again.notes}")
    }

    @Test
    fun cacheIsIgnoredWhenReuseDisabled() {
        val s = settings().copy(writeAlbumJson = true, fetchMetadata = false)
        AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        val root = settings().effectiveRoot
        val cache = com.doggybear233.saltaudiocopy.album.DiscCache.path(root, com.doggybear233.saltaudiocopy.meta.DiscId.musicBrainz(scan().toc))
        Files.writeString(cache, Files.readString(cache).replace("未知专辑", "Fantôme"))

        val again = AlbumRipper.rip(
            scan(), s.copy(reuseAlbumJson = false), { false }, {}, onlyTracks = setOf(1), source = fake()
        )
        assertEquals("未知专辑", again.outDir.fileName.toString(), "关掉复用后应回到占位名")
    }

    @Test
    fun readCommandIsAppliedAndLogged() {
        val out = AlbumRipper.rip(
            scan(), settings().copy(readCommand = "read10"), { false }, {}, onlyTracks = setOf(1), source = fake()
        )
        assertEquals(
            com.doggybear233.saltaudiocopy.scsi.ReadCommand.READ10,
            com.doggybear233.saltaudiocopy.scsi.ReadCommandPreference.preferred,
            "抓轨开始时应把设置里的读命令写进全局偏好"
        )
        val log = Files.list(out.outDir).use { s -> s.filter { it.toString().endsWith(".log") }.findFirst().orElse(null) }
        assertTrue(log != null, "应生成日志")
        assertTrue(Files.readString(log!!).contains("readCommand"), "日志摘要应记录读命令，便于事后对账")
    }
    /**
     * 取消只删本次新建的文件：第二次抓同一张盘（同名文件已存在 → 另存 (2)）时，
     * [com.doggybear233.saltaudiocopy.album.AlbumRipResult.createdFiles] 必须只包含这次多出来的文件，
     * 不能把上一次的成品算进去——否则「取消后清理」会删掉用户的旧作品。
     */
    @Test
    fun createdFilesOnlyContainsThisRunsOutput() {
        val first = AlbumRipper.rip(
            scan(), settings().copy(resume = false), { false }, {}, onlyTracks = setOf(1), source = fake()
        )
        assertTrue(first.createdFiles.isNotEmpty(), "第一次抓轨应记录新建文件")
        val firstNames = first.createdFiles.map { it.fileName.toString() }.toSet()

        val second = AlbumRipper.rip(
            scan(), settings().copy(resume = false), { false }, {}, onlyTracks = setOf(1), source = fake()
        )
        val secondNames = second.createdFiles.map { it.fileName.toString() }.toSet()
        assertTrue(secondNames.isNotEmpty(), "第二次抓轨也应记录新建文件")
        assertTrue(
            secondNames.none { it in firstNames },
            "第二次的 createdFiles 不能包含上一次的文件：$secondNames ∩ $firstNames"
        )
        assertTrue(
            secondNames.any { it.contains("(2)") },
            "已存在的同名文件应另存为 (2)，实际：$secondNames"
        )
    }
}
