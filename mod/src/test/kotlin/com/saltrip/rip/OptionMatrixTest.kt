package com.saltrip.rip

import com.saltrip.rip.album.AlbumJsonWriter
import com.saltrip.rip.album.AlbumRipper
import com.saltrip.rip.album.DiscAnalysis
import com.saltrip.rip.album.DiscCache
import com.saltrip.rip.album.RipPlan
import com.saltrip.rip.encode.FlacEncoder
import com.saltrip.rip.encode.ProcessResult
import com.saltrip.rip.encode.ProcessRunner
import com.saltrip.rip.encode.WavTags
import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.meta.DiscId
import com.saltrip.rip.meta.MetaOverride
import com.saltrip.rip.meta.TrackMeta
import com.saltrip.rip.rip.FakeTrackSource
import com.saltrip.rip.rip.ScanResult
import com.saltrip.rip.rip.SecureRip
import com.saltrip.rip.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 非默认选项的行为矩阵。
 *
 * 默认值走通不代表「勾上/换掉这个选项」也对：这些用例专门把开关拨到另一边，
 * 断言落盘结果真的跟着变（否则就是设置界面里的假开关）。
 */
class OptionMatrixTest {

    private lateinit var dir: Path
    private lateinit var fixtureDir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltrip-matrix")
        // 真 FLAC 夹具：假编码器把合法字节交上去，标签路径才会真的被走到
        fixtureDir = Files.createTempDirectory("saltrip-matrix-fixture")
    }

    /** 合法的最小 FLAC（1 秒静音），给假编码器当产物。 */
    private fun flacBytes(): ByteArray? {
        val res = javaClass.getResourceAsStream("/fixtures/tiny-silence.flac") ?: return null
        return res.use { it.readBytes() }
    }

    @AfterTest
    fun tearDown() {
        FlacEncoder.runnerOverride = null
        FlacEncoder.runnerOverride = null
        for (d in listOf(dir, fixtureDir)) {
            runCatching { Files.walk(d).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun settings() = RipSettings(
        outputRoot = dir.toString(),
        folderTemplate = "%albumartist%/%albumtitle%",
        fileTemplate = "%tracknr2% %title%",
        fetchMetadata = false,
        writeCue = true,
        writeLog = true,
        secureMode = true,
        useCdText = false,
        guardDisc = true,
        resume = false,
        detectGaps = false,
        gapMode = "append",
        // 测试用的假驱动器没有 0xBB 语义，别让它去开真光驱
        driveSpeed = 0
    )

    private fun fake() = FakeTrackSource(sectors = 6000)

    private fun scan() = ScanResult("\\\\.\\X:", FakeTrackSource.inquiry(), FakeTrackSource.disc3Tracks(6000))

    private fun fakeExe(): Path = Files.createFile(dir.resolve("enc-${System.nanoTime()}.exe"))

    private fun copyRunner(capture: MutableList<List<String>>? = null) = ProcessRunner { cmd, _ ->
        capture?.add(cmd.toList())
        val i = cmd.indexOf("-o")
        if (i >= 0 && i + 1 < cmd.size) {
            val out = Path.of(cmd[i + 1])
            val real = if (out.fileName.toString().endsWith(".flac", true)) flacBytes() else null
            Files.write(out, real ?: byteArrayOf(1, 2, 3, 4))
        }
        ProcessResult(0, "ok")
    }

    // ---------------- CUE 的 CRC 标注 ----------------

    @Test
    fun annotateCueCrcWritesPerTrackCrc() {
        val s = settings().copy(annotateCueCrc = true)
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        val cue = res.cueFile?.let { Files.readString(it) } ?: ""
        assertTrue(cue.contains("REM CRC32"), "勾上后 CUE 里应有每轨 CRC：$cue")
    }

    @Test
    fun cueHasNoCrcWhenAnnotationIsOff() {
        val s = settings().copy(annotateCueCrc = false)
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        val cue = res.cueFile?.let { Files.readString(it) } ?: ""
        assertFalse(cue.contains("REM CRC32"), "关掉后不该再写 CRC 行：$cue")
    }

    @Test
    fun imageCueAlsoCarriesCrcAndCleanNames() {
        val s = settings().copy(
            annotateCueCrc = true, imageMode = true, keepWav = true
        )
        val res = AlbumRipper.rip(scan(), s, { false }, {}, source = fake())
        val cue = res.cueFile?.let { Files.readString(it) } ?: ""
        assertTrue(cue.contains("REM CRC32"), "镜像 CUE 也要有 CRC：$cue")
        assertTrue(cue.contains("TITLE \"Track 01\""), "轨标题要完整写成 TITLE \"...\"：$cue")
        assertTrue(cue.contains("PERFORMER \"未知艺术家\""), "轨艺术家要完整写成 PERFORMER \"...\"：$cue")
        assertFalse(cue.contains("\")"), "CUE 值里不该出现 `\\\")` 这种残缺引号：$cue")
    }

    // ---------------- WAV 标签开关 ----------------

    @Test
    fun writeWavTagsOnAddsTags() {
        val s = settings()
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        val tags = WavTags.read(res.tracks.single().file)
        assertTrue(tags.isNotEmpty(), "默认应写入标签：$tags")
        assertEquals("Track 01", tags["title"], "标签标题要与文件名同源：$tags")
    }

    // ---------------- 封面文件名 ----------------

    @Test
    fun coverFileNameSettingControlsWrittenCover() {
        val toc = scan().toc
        val discId = DiscId.musicBrainz(toc)
        val cache = DiscCache.path(dir, discId)
        Files.createDirectories(cache.parent)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        Files.writeString(
            cache,
            AlbumJsonWriter.build(
                AlbumMeta(
                    albumArtist = "a", albumTitle = "b", discId = discId, cddbId = "",
                    source = "manual", tracks = listOf(TrackMeta(1, "t"))
                ),
                toc
            )
        )
        Files.write(cache.resolveSibling(cache.fileName.toString().removeSuffix(".json") + ".cover.png"), png)

        val s = settings().copy(coverFileName = "folder", format = "wav")
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        val cover = res.coverFile
        assertNotNull(cover, "有封面字节时必须落盘")
        assertEquals("folder.png", cover.fileName.toString(), "封面文件名要跟设置走：$cover")
        assertTrue(Files.isRegularFile(cover))
    }

    // ---------------- 多碟目录 ----------------

    @Test
    fun totalDiscsOverrideCreatesDiscSubfolder() {
        val s = settings().copy(totalDiscsOverride = 2, discNumberOverride = 2)
        val plan = RipPlan.build(scan().toc, s, album = null)
        assertTrue(
            plan.albumDir.fileName.toString().startsWith("CD2"),
            "总碟数 > 1 且模板没有 %discnumber% 时应加多碟子目录：${plan.albumDir}"
        )
        val single = RipPlan.build(scan().toc, settings(), album = null)
        assertFalse(
            single.albumDir.fileName.toString().startsWith("CD"),
            "单碟不该平白多一层目录：${single.albumDir}"
        )
    }

    @Test
    fun multiDiscFolderNameIsConfigurable() {
        val s = settings().copy(totalDiscsOverride = 2, multiDiscFolder = "磁盘%discnumber%")
        val plan = RipPlan.build(scan().toc, s, album = null)
        assertEquals("磁盘1", plan.albumDir.fileName.toString(), "多碟目录名要按设置展开：${plan.albumDir}")
    }

    @Test
    fun cleanRipReportsNoWarnings() {
        // 反向保护：不能把「提示性笔记」也算成问题，否则用户每抓一张盘都被警告一次
        val s = settings().copy(format = "wav")
        val res = AlbumRipper.rip(scan(), s, { false }, {}, source = fake())
        assertTrue(res.warnings.isEmpty(), "顺利抓完不该有 warnings：${res.warnings}")
        assertTrue(res.notes.isNotEmpty(), "提示性信息仍要留在 notes 里")
    }

    @Test
    fun encoderFailureShowsUpInWarningsNotOnlyNotes() {
        // 内置编码器解不出来（包损坏 / 无权限）时，失败要能被 UI 看到。
        FlacEncoder.runnerOverride = ProcessRunner { _, _ -> throw java.io.IOException("cannot run") }
        val s = settings().copy(format = "flac")
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        assertTrue(res.warnings.any { it.contains("FLAC 编码失败（已保留 WAV）") }, "失败要能被 UI 看到：${res.warnings}")
    }

    @Test
    fun flacLevelReachesCommandLine() {
        val captured = mutableListOf<List<String>>()
        FlacEncoder.runnerOverride = copyRunner(captured)
        val s = settings().copy(format = "flac", flacLevel = 8)
        AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        val cmd = captured.single()
        assertTrue(cmd.contains("-8"), "压缩级别必须传给 flac.exe：$cmd")
        val out = Path.of(cmd[cmd.indexOf("-o") + 1])
        assertTrue(Files.isRegularFile(out), "假编码器应产出文件：$out")
        assertTrue(Files.size(out) > 0, "假编码器必须真的产出文件：${Files.size(out)}")
    }

    // ---------------- 出错恢复档位 ----------------

    @Test
    fun errorRecoveryLevelsMapToStablePolicies() {
        assertEquals(SecureRip.Policy(1, 1), SecureRip.policyFor("low"), "Low 只读一次，不校验")
        assertEquals(SecureRip.Policy(2, 9), SecureRip.policyFor("medium", maxRetries = 8), "中等=读两次比对（EAC 同义）")
        assertEquals(SecureRip.Policy(5, SecureRip.MAX_TOTAL_READS), SecureRip.policyFor("high"))
        assertEquals(
            SecureRip.policyFor("medium"),
            SecureRip.policyFor("nonsense"),
            "未知档位要退回 Medium，而不是关掉校验"
        )
    }

    // ---------------- 间隙检测方法 ----------------

    @Test
    fun silenceGapMethodRunsOnFlatAudio() {
        val src = FakeTrackSource(sectors = 6000)
        val gaps = DiscAnalysis.gapsFor(scan().toc, src, gapMethod = "silence") { }
        assertTrue(gaps.all { it.number in setOf(1, 2, 3) }, "间隙结果只该按轨号索引：$gaps")
    }

    // ---------------- 覆盖策略 ----------------

    @Test
    fun overwritePolicySkipLeavesExistingFileUntouched() {
        val target = dir.resolve("未知艺术家").resolve("未知专辑")
        Files.createDirectories(target)
        val existing = target.resolve("01 Track 01.wav")
        Files.write(existing, byteArrayOf(9, 9, 9, 9))
        val s = settings().copy(overwritePolicy = "skip")
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        assertEquals(4L, Files.size(existing), "skip 策略不该动已有文件")
        assertTrue(res.tracks.isEmpty() || res.tracks.single().skipped, "应报告为跳过：${res.tracks}")
    }

    @Test
    fun overwritePolicyOverwriteReplacesExistingFile() {
        val target = dir.resolve("未知艺术家").resolve("未知专辑")
        Files.createDirectories(target)
        val existing = target.resolve("01 Track 01.wav")
        Files.write(existing, byteArrayOf(9, 9, 9, 9))
        val s = settings().copy(overwritePolicy = "overwrite")
        AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        assertTrue(Files.size(existing) > 100_000, "overwrite 策略应重写文件：${Files.size(existing)}")
    }

    @Test
    fun overrideFileIsResolvedInDocumentedOrder() {
        val root = Files.createDirectories(dir.resolve("override-root"))
        assertNull(MetaOverride.resolvePath(root, null, ""), "什么都没有时应返回 null")

        val generic = root.resolve("album.json")
        Files.writeString(generic, "{}")
        assertEquals(generic, MetaOverride.resolvePath(root, null, ""), "回落 album.json")

        val byDiscId = root.resolve("disc-id.album.json")
        Files.writeString(byDiscId, "{}")
        assertEquals(byDiscId, MetaOverride.resolvePath(root, "disc-id", ""), "<discId>.album.json 优先于 album.json")

        val explicit = root.resolve("my-tags.json")
        Files.writeString(explicit, "{}")
        assertEquals(explicit, MetaOverride.resolvePath(root, "disc-id", explicit.toString()), "显式 overrideFile 最优先")

        assertEquals(
            root.resolve("my-tags.json"),
            MetaOverride.resolvePath(root, "disc-id", "my-tags.json"),
            "相对路径要按输出根解析"
        )
    }

    @Test
    fun missingOverrideFileIsIgnored() {
        val s = settings().copy(overrideFile = dir.resolve("nope.json").toString())
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        assertTrue(res.tracks.isNotEmpty(), "override 文件不存在也不能让抓轨失败")
        assertNull(res.album?.tracks?.firstOrNull()?.isrc, "缺省元数据不该凭空出现 ISRC")
    }
}
