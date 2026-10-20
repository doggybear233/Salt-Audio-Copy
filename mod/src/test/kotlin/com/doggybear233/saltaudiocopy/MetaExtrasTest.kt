package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumTrackResult
import com.doggybear233.saltaudiocopy.album.CueWriter
import com.doggybear233.saltaudiocopy.encode.FlacMeta
import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.scsi.CdText
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CD-TEXT 的 0x82（词曲作者）与 0x8E（UPC/MCN）贯通测试。
 *
 * EAC 把这两项分别写进 CUE 的 `SONGWRITER` 与 `CATALOG`，FLAC 里对应 `COMPOSER`。
 */
class MetaExtrasTest {

    private fun latin(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun cdTextParsesSongwriterAndUpc() {
        val data = CdText.buildResponse(
            listOf(
                Triple(0x80, 0, latin("Fantome")),
                Triple(0x81, 0, latin("Utada Hikaru")),
                Triple(0x82, 1, latin("Utada Hikaru")),
                Triple(0x82, 2, latin("Kirie")),
                Triple(0x82, 0, latin("Various")),
                Triple(0x8E, 0, latin("4988006223456"))
            )
        )
        val info = CdText.parse(data)
        assertNotNull(info)
        assertEquals("Utada Hikaru", info.songwriters[1])
        assertEquals("Kirie", info.songwriters[2])
        assertEquals("Various", info.albumSongwriter)
        assertEquals("4988006223456", info.upc)
    }

    @Test
    fun cueWritesCatalogAndSongwriterLines() {
        val album = AlbumMeta(
            albumArtist = "宇多田ヒカル",
            albumTitle = "Fantôme",
            discId = "",
            cddbId = "",
            source = "cd-text"
        )
        val tracks = listOf(
            AlbumTrackResult(1, "道", "宇多田ヒカル", Path.of("01.wav"), 0, 100, 1L, 0, 10),
            AlbumTrackResult(2, "俺の彼女", "宇多田ヒカル", Path.of("02.wav"), 100, 100, 2L, 0, 10)
        )
        val text = CueWriter.build(
            album, tracks, listOf("01.wav", "02.wav"),
            catalog = "4988006223456",
            songwriters = mapOf(1 to "宇多田ヒカル", 2 to "Kirie")
        )
        assertTrue(text.contains("CATALOG 4988006223456"), "缺少 CATALOG 行")
        assertTrue(text.contains("    SONGWRITER \"宇多田ヒカル\""), "缺少 SONGWRITER 行")
        // 每轨各一行
        assertEquals(2, Regex("^    SONGWRITER ", RegexOption.MULTILINE).findAll(text).count())
    }

    @Test
    fun cueOmitsCatalogWhenAbsent() {
        val album = AlbumMeta(albumArtist = "A", albumTitle = "B", discId = "", cddbId = "", source = "test")
        val tracks = listOf(
            AlbumTrackResult(1, "T1", "A", Path.of("01.wav"), 0, 100, 1L, 0, 10)
        )
        val text = CueWriter.build(album, tracks, listOf("01.wav"))
        assertTrue(!text.contains("CATALOG"), "没有 UPC 时不应写 CATALOG")
        assertTrue(!text.contains("SONGWRITER"), "没有词曲作者时不应写 SONGWRITER")
    }

    @Test
    fun flacTagsCarryComposer() {
        val tags = FlacMeta.trackTags(
            albumTitle = "Fantôme",
            albumArtist = "宇多田ヒカル",
            year = "2016",
            genre = "J-Pop",
            trackNumber = 1,
            trackTitle = "道",
            trackArtist = "宇多田ヒカル",
            isrc = "JPU251600101",
            trackTotal = 11,
            discNumber = 1,
            totalDiscs = 1,
            comment = "Salt Audio Copy",
            composer = "宇多田ヒカル"
        ).toMap()
        assertEquals("宇多田ヒカル", tags["COMPOSER"])
        assertEquals("JPU251600101", tags["ISRC"])
    }

    @Test
    fun blankComposerIsDropped() {
        val tags = FlacMeta.trackTags(
            albumTitle = "A", albumArtist = "B", year = null, genre = null,
            trackNumber = 1, trackTitle = "T", trackArtist = null, isrc = null,
            trackTotal = null, discNumber = null, totalDiscs = null,
            comment = "Salt Audio Copy", composer = "   "
        ).toMap()
        assertTrue("COMPOSER" !in tags, "空白词曲作者不应写入标签")
    }

    @Test
    fun exportedJsonKeepsDiscIds() {
        // 读取侧认 discId/cddbId，写入侧之前从不写：导出的模板改完再读回来就丢了。
        val toc = com.doggybear233.saltaudiocopy.scsi.DiscToc(
            1, 1, listOf(
                com.doggybear233.saltaudiocopy.scsi.TocEntry(1, true, 1, 0),
                com.doggybear233.saltaudiocopy.scsi.TocEntry(0xAA, true, 1, 1000)
            )
        )
        val album = com.doggybear233.saltaudiocopy.meta.AlbumMeta(
            albumArtist = "A", albumTitle = "B", discId = "mbid", cddbId = "ccid", source = "test"
        )
        val text = com.doggybear233.saltaudiocopy.album.AlbumJsonWriter.build(album, toc)
        assertTrue(text.contains("\"discId\": \"mbid\""), text)
        assertTrue(text.contains("\"cddbId\": \"ccid\""), text)
    }
}
