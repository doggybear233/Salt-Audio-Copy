package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.Http
import com.doggybear233.saltaudiocopy.meta.MetaOverride
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-386：`album.json` 里的空串表示「这栏没填」，不是「清空」。
 *
 * 现象：只要用户保存过一次手填元数据（`AlbumRipper`/`AlbumJsonWriter` 会把没填的
 * 年份、流派写成 `""`），下次 MusicBrainz 查到的年份就会被这个空串抹掉，
 * 标签里 DATE 永远为空。
 *
 * F-385：取消上一次查询后，新一次抓轨/选候选/导出要能重新联网。
 */
class BatchR307MetaTest {

    private fun tmp(): Path = TempDirs.create("b307m").also { it.toFile().deleteOnExit() }

    @Test
    fun blankFieldsInAlbumJsonDoNotEraseFetchedValues() {
        val dir = tmp()
        val json = dir.resolve("album.json")
        Files.writeString(
            json,
            """
            {
              "albumArtist": "",
              "albumTitle": "",
              "year": "",
              "genre": "",
              "tracks": [ { "number": 1, "title": "", "artist": "", "isrc": "" } ]
            }
            """.trimIndent()
        )
        val override = MetaOverride.load(json)
        assertTrue(override != null, "album.json 应能解析")

        val fetched = AlbumMeta(
            albumArtist = "宇多田ヒカル",
            albumTitle = "Fantôme",
            year = "2016",
            genre = "J-Pop",
            discId = "abc",
            cddbId = "1234",
            source = "musicbrainz",
            tracks = listOf(TrackMeta(1, "道", "宇多田ヒカル", 300_000, "JPU901601234"))
        )
        val merged = MetaOverride.merge(fetched, override!!)
        assertEquals("宇多田ヒカル", merged.albumArtist, "空串不能覆盖查到的专辑艺术家")
        assertEquals("Fantôme", merged.albumTitle)
        assertEquals("2016", merged.year, "空串不能抹掉年份")
        assertEquals("J-Pop", merged.genre, "空串不能抹掉流派")
        assertEquals("道", merged.tracks.single().title, "空曲名不能覆盖查到的曲名")
        assertEquals("宇多田ヒカル", merged.tracks.single().artist)
        assertEquals("JPU901601234", merged.tracks.single().isrc)
    }

    @Test
    fun realValuesInAlbumJsonStillWin() {
        val dir = tmp()
        val json = dir.resolve("album.json")
        Files.writeString(
            json,
            """{ "albumArtist": "手填艺术家", "albumTitle": "手填专辑", "year": "1999" }"""
        )
        val override = MetaOverride.load(json)!!
        val fetched = AlbumMeta(
            albumArtist = "网上艺术家", albumTitle = "网上专辑", year = "2016",
            discId = "abc", cddbId = "1234", source = "musicbrainz"
        )
        val merged = MetaOverride.merge(fetched, override)
        assertEquals("手填艺术家", merged.albumArtist)
        assertEquals("1999", merged.year)
    }

    @Test
    fun newRunGetsAFreshNetworkScope() {
        // 模拟「上一批被取消」
        Http.beginScope()
        Http.abort()
        assertTrue(Http.activeScope().cancelled, "abort 之后当前 scope 必须是已取消")

        // 新一次抓轨/选候选的入口要换一批 scope
        ConfigActions.beginRunForTest()
        assertFalse(
            Http.activeScope().cancelled,
            "新一次操作必须换新 scope，否则取消过之后就再也查不了元数据"
        )
    }
}
