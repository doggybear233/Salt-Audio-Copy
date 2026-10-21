package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumRipper
import com.doggybear233.saltaudiocopy.album.ImageSplicer
import com.doggybear233.saltaudiocopy.album.CueWriter
import com.doggybear233.saltaudiocopy.encode.WavTags
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

/** 批次 09：镜像拼接不带标签、CUE 类型、多碟目录兜底。 */
class Batch09Test {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = TempDirs.create("saltac-b09")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun wav(name: String, sectors: Int): Path {
        val p = dir.resolve(name)
        val data = ByteArray(sectors * 2352) { (it % 251).toByte() }
        val b = java.io.ByteArrayOutputStream()
        fun le32(v: Int) { b.write(v and 0xFF); b.write((v ushr 8) and 0xFF); b.write((v ushr 16) and 0xFF); b.write((v ushr 24) and 0xFF) }
        fun le16(v: Int) { b.write(v and 0xFF); b.write((v ushr 8) and 0xFF) }
        b.write("RIFF".toByteArray()); le32(36 + data.size); b.write("WAVE".toByteArray())
        b.write("fmt ".toByteArray()); le32(16); le16(1); le16(2); le32(44100); le32(44100 * 4); le16(4); le16(16)
        b.write("data".toByteArray()); le32(data.size); b.write(data)
        Files.write(p, b.toByteArray())
        return p
    }

    @Test
    fun splicerSkipsAppendedTags() {
        val a = wav("01.wav", 100)
        val b = wav("02.wav", 100)
        WavTags.write(a, mapOf("title" to "标签不该进镜像"))
        val out = dir.resolve("image.wav")
        val r = ImageSplicer.splice(listOf(a, b), out)!!
        assertEquals(200L * 2352, r.dataBytes)
        assertEquals(44L + 200L * 2352, Files.size(out), "镜像里只应有 PCM，不含 LIST/id3")
    }

    @Test
    fun cueFileTypeFollowsExtension() {
        assertEquals("FLAC", CueWriter.cueType("album.flac"))
        assertEquals("WAVE", CueWriter.cueType("album.wav"))
    }

    @Test
    fun albumRipperFallsBackToCdFolderForBlankTemplate() {
        val settings = RipSettings(
            outputRoot = dir.toString(),
            folderTemplate = "%albumartist%/%albumtitle%",
            fileTemplate = "%tracknr2% %title%",
            fetchMetadata = false,
            writeCue = false,
            writeLog = false,
            secureMode = false,
            testAndCopy = false,
            detectGaps = false,
            totalDiscsOverride = 2,
            multiDiscFolder = ""
        )
        val toc = FakeTrackSource.disc3Tracks(6000)
        val out = AlbumRipper.rip(
            scan = ScanResult("\\\\.\\X:", FakeTrackSource.inquiry(), toc),
            settings = settings, cancel = { false },
            onProgress = {}, onlyTracks = setOf(1), source = FakeTrackSource(sectors = 6000)
        )
        assertTrue(
            out.outDir.fileName.toString() == "CD1",
            "多碟模板留空时应回落到 CD%discnumber%，实际是 ${out.outDir}"
        )
    }
}
