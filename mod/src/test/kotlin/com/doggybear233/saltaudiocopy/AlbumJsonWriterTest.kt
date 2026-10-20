package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumJsonWriter
import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.MetaOverride
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files

/**
 * 「导出 album.json → 用户改 → 抓轨时读回」这条闭环的自测：
 * 生成的文件必须能被我们自己的解析器吃下去，否则用户改完反而抓错。
 */
class AlbumJsonWriterTest {

    private fun toc(tracks: Int): DiscToc {
        val entries = (0 until tracks).map { TocEntry(it + 1, true, 1, it * 5000) }
        return DiscToc(firstTrack = 1, lastTrack = tracks, entries = entries)
    }

    private val album = AlbumMeta(
        albumArtist = "宇多田ヒカル",
        albumTitle = "Fantôme",
        year = "2016",
        genre = "J-Pop",
        discId = "o6OqqpbGy_JOCeJyEftrU1T0L6E-",
        cddbId = "b2060bae",
        source = "musicbrainz",
        tracks = listOf(
            TrackMeta(1, "道", "宇多田ヒカル", 220_000L, "JPU901601234"),
            TrackMeta(2, "俺の彼女", "宇多田ヒカル", 250_000L, null)
        )
    )

    @Test
    fun generatedJsonRoundTripsThroughOverride() {
        val dir = Files.createTempDirectory("saltac-albumjson")
        val f = dir.resolve("x.album.json")
        Files.writeString(f, AlbumJsonWriter.build(album, toc(2)), Charsets.UTF_8)

        val ov = MetaOverride.load(f)
        assertNotNull(ov, "自己生成的 JSON 必须能被 MetaOverride 解析")
        assertEquals("宇多田ヒカル", ov.albumArtist)
        assertEquals("Fantôme", ov.albumTitle)
        assertEquals("2016", ov.year)
        assertEquals(2, ov.tracks.size)
        assertEquals("道", ov.tracks[0].title)
        assertEquals("JPU901601234", ov.tracks[0].isrc)
        assertEquals("俺の彼女", ov.tracks[1].title)
    }

    @Test
    fun placeholdersWhenNoMetadata() {
        val text = AlbumJsonWriter.build(null, toc(3))
        val ov = MetaOverride.load(writeTemp(text))
        assertNotNull(ov)
        assertEquals(3, ov.tracks.size)
        assertEquals("Track 01", ov.tracks[0].title, "无元数据时给出可编辑的占位名")
        assertEquals(1, ov.discNumber)
    }

    @Test
    fun escapesQuotesAndBackslashes() {
        val weird = album.copy(albumTitle = "A \"quoted\" \\ title", tracks = listOf(TrackMeta(1, "line\nbreak", null, null, null)))
        val text = AlbumJsonWriter.build(weird, toc(1))
        assertTrue(text.contains("\\\"quoted\\\""), "引号必须转义，否则 JSON 坏掉")
        val ov = MetaOverride.load(writeTemp(text))
        assertNotNull(ov)
        assertEquals("A \"quoted\" \\ title", ov.albumTitle)
        assertEquals("line break", ov.tracks[0].title, "换行折成空格")
    }

    private fun writeTemp(text: String): java.nio.file.Path {
        val f = Files.createTempFile("saltac-albumjson-", ".json")
        Files.writeString(f, text, Charsets.UTF_8)
        return f
    }
}
