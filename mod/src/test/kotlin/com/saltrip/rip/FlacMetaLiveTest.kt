package com.saltrip.rip

import com.saltrip.rip.album.AlbumRipper
import com.saltrip.rip.encode.FlacEncoder
import com.saltrip.rip.encode.FlacMeta
import com.saltrip.rip.rip.FakeTrackSource
import com.saltrip.rip.rip.ScanResult
import com.saltrip.rip.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 真 flac.exe 验证：编码 → 自己写标签/封面 → 用 `flac -t` 证明音频流没被改坏。
 *
 * 不依赖光驱（用 [FakeTrackSource]），找不到 flac.exe 时 skip。
 */
class FlacMetaLiveTest {

    private lateinit var dir: Path
    private var flac: String? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltrip-flacmeta-live")
        flac = FlacEncoder.locate()
        assumeTrue(flac != null, "内置 flac.exe 解不出来，跳过真实编码")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun testStream(file: Path): Pair<Int, String> {
        val proc = ProcessBuilder(listOf(flac!!, "-t", "-s", file.toString())).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        proc.waitFor()
        return proc.exitValue() to text
    }

    @Test
    fun tagRewriteKeepsStreamDecodable() {
        // 用假驱动器产出一小段 WAV（44.1k/2ch/16bit 静音），再真编码
        val fake = FakeTrackSource(sectors = 40)
        val wav = dir.resolve("in.wav")
        val buf = java.io.ByteArrayOutputStream()
        val dataBytes = 40L * 2352L
        buf.write(com.saltrip.rip.album.ImageSplicer.header(dataBytes))
        repeat(40) { buf.write(fake.sector(it)) }
        Files.write(wav, buf.toByteArray())

        val out = dir.resolve("道.flac")
        FlacEncoder.encode(flac!!, wav, out, 5)
        assertTrue(Files.size(out) > 0)

        FlacMeta.writeTags(
            out,
            FlacMeta.trackTags(
                albumTitle = "Fantôme", albumArtist = "宇多田ヒカル", year = "2016", genre = "J-Pop",
                trackNumber = 1, trackTitle = "道", trackArtist = null, isrc = null,
                trackTotal = 1, discNumber = 1, totalDiscs = 1, comment = "SaltRip test"
            )
        )
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        )
        FlacMeta.writeCover(out, png, "image/png")

        val (code, msg) = testStream(out)
        assertEquals(0, code, "flac -t 必须通过（说明音频帧没被改坏）：$msg")
        val tags = FlacMeta.readTags(out)
        assertEquals("道", tags["TITLE"])
        assertEquals("宇多田ヒカル", tags["ARTIST"])
    }

    @Test
    fun albumRipperWritesTagsEndToEnd() {
        val settings = RipSettings(
            outputRoot = dir.toString(),
            folderTemplate = "%albumartist%/%albumtitle%",
            fileTemplate = "%tracknr2% %title%",
            fetchMetadata = false,
            format = "flac",
                keepWav = false,
            embedCover = false,
            secureMode = true,
            testAndCopy = false,
            useCdText = false,
            guardDisc = true,
            resume = false,
            detectGaps = false
        )
        val scan = ScanResult("\\\\.\\X:", FakeTrackSource.inquiry(), FakeTrackSource.disc3Tracks(6000))
        val out = AlbumRipper.rip(scan, settings, { false }, {}, onlyTracks = setOf(1), source = FakeTrackSource(sectors = 6000))
        assertEquals(1, out.tracks.size)
        val file = out.tracks[0].file
        assertTrue(file.toString().endsWith(".flac"), "应输出 FLAC：$file")
        val tags = FlacMeta.readTags(file)
        assertEquals("Track 01", tags["TITLE"])
        assertEquals("1", tags["TRACKNUMBER"])
        val (code, msg) = testStream(file)
        assertEquals(0, code, msg)
        // keepWav=false 时不应残留 WAV
        val wavs = Files.list(file.parent).use { s -> s.filter { it.toString().endsWith(".wav") }.toList() }
        assertTrue(wavs.isEmpty(), "keepWav=false 应删除中间 WAV：$wavs")
    }
}
