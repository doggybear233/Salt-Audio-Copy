package com.saltrip.rip

import com.saltrip.rip.album.AlbumJsonWriter
import com.saltrip.rip.album.AlbumRipper
import com.saltrip.rip.album.DiscCache
import com.saltrip.rip.encode.FlacEncoder
import com.saltrip.rip.encode.FlacMeta
import com.saltrip.rip.encode.ProcessResult
import com.saltrip.rip.encode.ProcessRunner
import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.meta.DiscId
import com.saltrip.rip.meta.TrackMeta
import com.saltrip.rip.rip.FakeTrackSource
import com.saltrip.rip.rip.ScanResult
import com.saltrip.rip.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FLAC 路径（内置编码器）的端到端测试。
 *
 * 这些用例以前只能靠「本机装了 flac.exe 才跑」的 `assumeTrue` 保底，CI 与干净机器上
 * 等于没测。现在编码器通过 [ProcessRunner] 注入假实现：命令行、产物、失败清理、
 * 超时文案都能在无编码器、无光驱的机器上断言。
 */
class FlacFormatTest {

    private lateinit var dir: Path
    private lateinit var fixtureDir: Path
    private lateinit var fixture: Path
    private lateinit var fakeExe: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltrip-flac")
        // 夹具放输出目录外面：否则「整盘镜像只该有一个 flac」这类断言会数到夹具。
        fixtureDir = Files.createTempDirectory("saltrip-flac-fixture")
        fixture = fixtureDir.resolve("tiny-silence.flac")
        val res = javaClass.getResourceAsStream("/fixtures/tiny-silence.flac")
        assertNotNull(res, "缺少测试夹具 /fixtures/tiny-silence.flac")
        res!!.use { Files.write(fixture, it.readBytes()) }
        fakeExe = Files.createFile(dir.resolve("fake-flac.exe"))
    }

    @AfterTest
    fun tearDown() {
        FlacEncoder.runnerOverride = null
        FlacEncoder.runnerOverride = null
        for (d in listOf(dir, fixtureDir)) {
            runCatching { Files.walk(d).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    // ---------------- 假编码器 ----------------

    /** 命令行里 `-o <path>` 的输出路径。 */
    private fun outOf(cmd: List<String>): Path {
        val i = cmd.indexOf("-o")
        require(i >= 0 && i + 1 < cmd.size) { "编码器命令行里没有 -o 输出路径：$cmd" }
        return Path.of(cmd[i + 1])
    }

    /**
     * 假编码器：把预置的合法 FLAC 复制到 `-o` 指定的位置。
     * 真 flac.exe 不在本机也能验证「产物被接受、大小被回报、失败会清理」。
     */
    private fun copyRunner(
        exit: Int = 0,
        produce: Boolean = true,
        capture: MutableList<List<String>>? = null
    ) = ProcessRunner { cmd, _ ->
        capture?.add(cmd.toList())
        if (produce) Files.copy(fixture, outOf(cmd), StandardCopyOption.REPLACE_EXISTING)
        ProcessResult(exit, "fake-encoder ok")
    }

    private fun wavInput(): Path = Files.createFile(dir.resolve("in.wav"))

    // ---------------- FlacEncoder ----------------

    @Test
    fun flacEncoderReturnsProducedSize() {
        FlacEncoder.runnerOverride = copyRunner()
        val out = dir.resolve("out.flac")
        val size = FlacEncoder.encode(fakeExe.toString(), wavInput(), out, 5)
        assertEquals(Files.size(fixture), size, "回报的应是产物字节数")
        assertTrue(Files.isRegularFile(out))
    }

    @Test
    fun flacEncoderPassesLevelAndOutputPath() {
        val captured = mutableListOf<List<String>>()
        FlacEncoder.runnerOverride = copyRunner(capture = captured)
        FlacEncoder.encode(fakeExe.toString(), wavInput(), dir.resolve("o.flac"), 8)
        val cmd = captured.single()
        assertTrue(cmd.contains("-f"), "必须带 -f 覆盖：$cmd")
        assertTrue(cmd.contains("-s"), "必须带 -s 静默：$cmd")
        assertTrue(cmd.contains("-8"), "压缩级别要按设置传：$cmd")
        assertEquals("o.flac", outOf(cmd).fileName.toString())
    }

    @Test
    fun flacEncoderDeletesHalfProductWhenExitIsNonZero() {
        FlacEncoder.runnerOverride = copyRunner(exit = 3)
        val out = dir.resolve("bad.flac")
        val e = assertFailsWith<IllegalStateException> {
            FlacEncoder.encode(fakeExe.toString(), wavInput(), out, 5)
        }
        assertTrue(e.message!!.contains("exit=3"), "错误里要带退出码：${e.message}")
        assertFalse(Files.exists(out), "半成品必须删掉，别让用户以为抓好了")
    }

    @Test
    fun flacEncoderReportsTimeout() {
        FlacEncoder.runnerOverride = ProcessRunner { _, _ -> throw TimeoutException("stuck") }
        val e = assertFailsWith<IllegalStateException> {
            FlacEncoder.encode(fakeExe.toString(), wavInput(), dir.resolve("t.flac"), 5, timeoutSec = 7)
        }
        assertTrue(e.message!!.contains("超时"), "超时要单独说清：${e.message}")
        assertTrue(e.message!!.contains("7"), "超时文案应带时长：${e.message}")
    }

    @Test
    fun flacEncoderReportsMissingOutput() {
        FlacEncoder.runnerOverride = copyRunner(produce = false)
        val e = assertFailsWith<IllegalStateException> {
            FlacEncoder.encode(fakeExe.toString(), wavInput(), dir.resolve("m.flac"), 5)
        }
        assertTrue(e.message!!.contains("没有产出文件"), "要说清是编码器没产出：${e.message}")
    }

    @Test
    fun flacEncoderReportsUnstartableExe() {
        FlacEncoder.runnerOverride = ProcessRunner { _, _ -> throw java.io.IOException("no such file") }
        val e = assertFailsWith<IllegalStateException> {
            FlacEncoder.encode(fakeExe.toString(), wavInput(), dir.resolve("x.flac"), 5)
        }
        assertTrue(e.message!!.contains("无法启动"), "启动失败要给可操作提示：${e.message}")
        assertTrue(e.message!!.contains("内置"), "提示里要说明内置件：${e.message}")
    }

    // ---------------- 整盘管线 ----------------

    private fun settings() = RipSettings(
        outputRoot = dir.toString(),
        folderTemplate = "%albumartist%/%albumtitle%",
        fileTemplate = "%tracknr2% %title%",
        fetchMetadata = false,
        writeCue = true,
        writeLog = true,
        secureMode = true,
        testAndCopy = false,
        useCdText = false,
        guardDisc = true,
        resume = false,
        detectGaps = false,
        gapMode = "append"
    )

    private fun fake() = FakeTrackSource(sectors = 6000)

    private fun scan() = ScanResult("\\\\.\\X:", FakeTrackSource.inquiry(), FakeTrackSource.disc3Tracks(6000))

    private fun filesIn(): List<Path> = Files.list(dir).use { s -> s.toList() }

    private fun findExt(ext: String): Path? =
        Files.walk(dir).use { s -> s.filter { it.fileName.toString().endsWith(ext, true) }.toList() }
            .singleOrNull()

    @Test
    fun flacRipProducesTaggedFlacAndKeepsNoWav() {
        FlacEncoder.runnerOverride = copyRunner()
        val s = settings().copy(format = "flac", keepWav = false, writeTags = true)
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())

        val track = res.tracks.single()
        assertTrue(track.file.toString().endsWith(".flac"), "画面上报的文件应是 flac：${track.file}")
        assertTrue(Files.isRegularFile(track.file))
        assertFalse(Files.exists(dir.resolve("未知艺术家/未知专辑/01 Track 01.wav")), "keepWav=false 时不该留下 WAV")

        val tags = FlacMeta.readTags(track.file)
        assertEquals("Track 01", tags["TITLE"], "占位标题也要写进标签：$tags")
        assertEquals("未知专辑", tags["ALBUM"], "专辑名要写进标签：$tags")
        assertEquals("1", tags["TRACKNUMBER"])

        val cue = res.cueFile?.let { Files.readString(it) } ?: ""
        assertTrue(cue.contains(".flac"), "CUE 的 FILE 行必须指向 flac：$cue")
        assertFalse(cue.contains(".wav"), "CUE 不该再指向已删掉的 WAV：$cue")
    }

    @Test
    fun flacTagsUseCachedAlbumJson() {
        FlacEncoder.runnerOverride = copyRunner()
        val toc = scan().toc
        val discId = DiscId.musicBrainz(toc)
        val cache = DiscCache.path(Path.of(settings().outputRoot), discId)
        Files.createDirectories(cache.parent)
        val meta = AlbumMeta(
            albumArtist = "宇多田ヒカル",
            albumTitle = "Fantôme",
            year = "2016",
            genre = "J-Pop",
            discId = discId,
            cddbId = "",
            source = "manual",
            tracks = listOf(TrackMeta(1, "道", "宇多田ヒカル"))
        )
        Files.writeString(cache, AlbumJsonWriter.build(meta, toc))

        val s = settings().copy(format = "flac", keepWav = false)
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())
        val tags = FlacMeta.readTags(res.tracks.single().file)
        assertEquals("道", tags["TITLE"], tags.toString())
        assertEquals("宇多田ヒカル", tags["ARTIST"], tags.toString())
        assertEquals("Fantôme", tags["ALBUM"], tags.toString())
        assertEquals("2016", tags["DATE"], tags.toString())
    }

    @Test
    fun flacImageModeProducesOneFlacAndCuePointsAtIt() {
        FlacEncoder.runnerOverride = copyRunner()
        val s = settings().copy(
            format = "flac", keepWav = false,
            imageMode = true, writeTags = true
        )
        val res = AlbumRipper.rip(scan(), s, { false }, {}, source = fake())

        val flacs = Files.walk(dir).use { st -> st.filter { it.fileName.toString().endsWith(".flac", true) }.toList() }
        assertEquals(1, flacs.size, "整盘镜像只应有一个 flac：$flacs")
        assertTrue(res.tracks.all { it.file == flacs.single() }, "所有轨都应指向同一个镜像：${res.tracks.map { it.file }}")
        val cue = res.cueFile?.let { Files.readString(it) } ?: ""
        assertTrue(cue.contains(flacs.single().fileName.toString()), "CUE 必须引用这个镜像：$cue")
        assertFalse(filesIn().any { it.fileName.toString().endsWith(".wav", true) }, "不该留下 WAV")
    }

    @Test
    fun brokenBundledEncoderKeepsWavAndExplainsWhy() {
        // 内置编码器解不出来（插件包损坏 / 无写权限）时，必须保住 WAV 并说明原因。
        FlacEncoder.runnerOverride = ProcessRunner { _, _ -> throw java.io.IOException("cannot run") }
        val s = settings().copy(format = "flac")
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())

        val track = res.tracks.single()
        assertTrue(track.file.toString().endsWith(".wav"), "编码器不可用时必须保住 WAV：${track.file}")
        assertTrue(Files.isRegularFile(track.file))
        assertTrue(
            res.warnings.any { it.contains("FLAC 编码失败（已保留 WAV）") },
            "要给用户看得懂的原因，而不是静默降级：${res.warnings}"
        )
    }

    @Test
    fun flacFailureFallsBackToWavWithNote() {
        FlacEncoder.runnerOverride = copyRunner(exit = 1)
        val s = settings().copy(format = "flac", keepWav = false)
        val res = AlbumRipper.rip(scan(), s, { false }, {}, onlyTracks = setOf(1), source = fake())

        val track = res.tracks.single()
        assertTrue(track.file.toString().endsWith(".wav"), "编码失败不该把用户的数据丢掉：${track.file}")
        assertTrue(Files.isRegularFile(track.file))
        assertTrue(
            res.warnings.any { it.contains("FLAC 编码失败（已保留 WAV）") },
            "失败原因要同时进 notes 与 warnings：${res.notes} / ${res.warnings}"
        )
    }
}
