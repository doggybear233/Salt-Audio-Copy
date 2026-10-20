package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumRipper
import com.doggybear233.saltaudiocopy.album.RipPlan
import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import com.doggybear233.saltaudiocopy.rip.FakeTrackSource
import com.doggybear233.saltaudiocopy.rip.ScanResult
import com.doggybear233.saltaudiocopy.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RipPlanTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltac-plan")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun settings() = RipSettings(
        outputRoot = dir.toString(),
        folderTemplate = "%albumartist%/%albumtitle%",
        fileTemplate = "%tracknr2% %title%",
        writeCue = true,
        writeLog = true,
        fetchMetadata = false
    )

    private fun album() = AlbumMeta(
        albumArtist = "宇多田ヒカル",
        albumTitle = "Fantôme",
        year = "2016",
        genre = "J-Pop",
        discId = "x",
        cddbId = "y",
        source = "test",
        tracks = listOf(
            TrackMeta(1, "道", "宇多田ヒカル", 260_000),
            TrackMeta(2, "俺の彼女", "宇多田ヒカル", 250_000),
            TrackMeta(3, "花束を君に", "宇多田ヒカル", 280_000)
        )
    )

    private fun scan(sectors: Int = 6000) = ScanResult(
        "\\\\.\\X:",
        FakeTrackSource.inquiry(),
        FakeTrackSource.disc3Tracks(sectors)
    )

    @Test
    fun planMatchesWhatRipperActuallyWrites() {
        val fake = FakeTrackSource(sectors = 6000)
        val cfg = settings()
        // 先出计划（此时目录是空的），再真抓，然后逐名对账
        // 抓轨时没有元数据 → 用占位名；计划必须用同样输入才能对账
        val plan = RipPlan.build(scan().toc, cfg, album = null)
        val performed = AlbumRipper.rip(scan(), cfg, { false }, {}, source = fake)

        assertEquals(plan.albumDir, performed.outDir, "目录必须与真抓一致")
        val planned = plan.tracks.map { it.fileName }
        val actual = performed.tracks.map { it.file.toString().substringAfterLast('\\').substringAfterLast('/') }
        assertEquals(planned, actual, "计划里的文件名必须与真抓落盘的文件名逐个一致（否则预览就是骗人）")
    }

    @Test
    fun reportsWriteActionForFreshDisc() {
        val plan = RipPlan.build(scan().toc, settings(), album())
        assertEquals(listOf("写入", "写入", "写入"), plan.tracks.map { it.action })
        assertEquals("01 道.wav", plan.tracks[0].fileName)
        assertTrue(plan.totalBytes > 0)
        assertEquals(3, plan.tracks.size)
    }

    @Test
    fun reportsSuffixWhenFileExists() {
        val cfg = settings()
        val discDir = dir.resolve("宇多田ヒカル").resolve("Fantôme")
        Files.createDirectories(discDir)
        Files.write(discDir.resolve("01 道.wav"), ByteArray(8))
        val plan = RipPlan.build(scan().toc, cfg, album())
        assertEquals("01 道 (2).wav", plan.tracks[0].fileName)
        assertTrue(plan.tracks[0].action.startsWith("另存为"))
    }

    @Test
    fun overwritePolicyIsWarnedAndReported() {
        val cfg = settings().copy(overwritePolicy = "overwrite")
        val discDir = dir.resolve("宇多田ヒカル").resolve("Fantôme")
        Files.createDirectories(discDir)
        Files.write(discDir.resolve("01 道.wav"), ByteArray(8))
        val plan = RipPlan.build(scan().toc, cfg, album())
        assertEquals("覆盖", plan.tracks[0].action)
        assertTrue(plan.warnings.any { it.contains("覆盖") })
    }

    @Test
    fun trackSelectionLimitsPlan() {
        val cfg = settings().copy(trackSelection = "2-3")
        val plan = RipPlan.build(scan().toc, cfg, album())
        assertEquals(listOf(2, 3), plan.tracks.map { it.track })
    }

    @Test
    fun gapModesChangeExpectedBytes() {
        val gaps = mapOf(2 to 10, 3 to 10)
        val append = RipPlan.build(scan().toc, settings(), album(), gaps)
        val prepend = RipPlan.build(scan().toc, settings().copy(gapMode = "prepend"), album(), gaps)
        val leaveout = RipPlan.build(scan().toc, settings().copy(gapMode = "leaveout"), album(), gaps)
        assertEquals(2000L * 2352, append.tracks[1].bytes)
        assertEquals(2010L * 2352, prepend.tracks[1].bytes)
        assertEquals(1990L * 2352, leaveout.tracks[1].bytes)
    }

    @Test
    fun describeMentionsEverythingImportant() {
        val text = RipPlan.build(scan().toc, settings(), album()).describe()
        assertTrue(text.contains("输出目录："))
        assertTrue(text.contains("宇多田ヒカル - Fantôme"))
        assertTrue(text.contains("01 道.wav"))
        assertTrue(text.contains("CUE："))
        assertTrue(text.contains("日志："))
    }

    @Test
    fun multiDiscFolderIsAutomaticWhenTemplateLacksDiscNumber() {
        val cfg = settings().copy(folderTemplate = "%albumartist%/%albumtitle%")
        val twoDiscs = album().copy(totalDiscs = 2, discNumber = 2)
        val plan = RipPlan.build(scan().toc, cfg, twoDiscs)
        assertEquals("CD2", plan.albumDir.fileName.toString())
    }
}
