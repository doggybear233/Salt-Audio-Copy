package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.Json
import com.doggybear233.saltaudiocopy.meta.GnudbClient
import com.doggybear233.saltaudiocopy.meta.MusicBrainzClient
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonTest {

    @Test
    fun parsesNestedStructures() {
        val v = Json.parse("""{"a":[1,2,{"b":"x\ny"}],"c":true,"d":null}""")
        assertEquals(1L, Json.long(Json.path(v, "a", "0")))
        assertEquals("x\ny", Json.str(Json.path(v, "a", "2", "b")))
        assertEquals(true, Json.path(v, "c"))
        assertEquals(null, Json.path(v, "d"))
    }

    @Test
    fun parsesUnicodeEscapes() {
        val v = Json.parse("""{"t":"\u685c\u6d41\u3057"}""")
        assertEquals("桜流し", Json.str(Json.path(v, "t")))
    }

    @Test
    fun parsesRealMbShapedResponse() {
        val text = """
            {"disc":{"id":"x"},"releases":[
              {"id":"rel-1","title":"Fantôme","date":"2016-09-28","country":"JP",
               "artist-credit":[{"name":"宇多田ヒカル","joinphrase":""}],
               "release-group":{"primary-type":"Album","secondary-types":[]},
               "media":[{"position":1,"track-count":2,"tracks":[
                  {"number":"1","title":"道","length":221000},
                  {"number":"2","title":"俺の彼女","length":238000}]}]}
            ]}
        """.trimIndent()
        val toc = DiscToc(1, 2, listOf(TocEntry(1, true, 1, 0), TocEntry(2, true, 1, 5000), TocEntry(0xAA, true, 1, 10000)))
        val albums = MusicBrainzClient.parseDiscidResponse(Json.parse(text), toc, "DISCID", "82020006")
        assertEquals(1, albums.size)
        val a = albums[0]
        assertEquals("宇多田ヒカル", a.albumArtist)
        assertEquals("Fantôme", a.albumTitle)
        assertEquals("2016", a.year)
        assertEquals("Album", a.genre)
        assertEquals("rel-1", a.releaseId)
        assertEquals(listOf("道", "俺の彼女"), a.tracks.map { it.title })
        assertEquals(221000L, a.tracks[0].lengthMs)
    }

    @Test
    fun multiDiscResponseGetsDiscNumberAndTotal() {
        // medium.position 是「第几张」，media.size 才是「共几张」。
        // 以前把 position 当 totalDiscs，双碟装的第二张会被算成 CD2/2——第一张算成 CD1/1。
        val json = """
            {"releases":[{"id":"rel-1","title":"Two Disc","artist-credit":[{"name":"A"}],
              "media":[
                {"position":1,"track-count":2,"tracks":[{"number":"1","title":"a1"},{"number":"2","title":"a2"}]},
                {"position":2,"track-count":2,"tracks":[{"number":"1","title":"b1"},{"number":"2","title":"b2"}]}
              ]}]}
        """.trimIndent()
        val toc = com.doggybear233.saltaudiocopy.scsi.DiscToc(
            1, 2, listOf(
                com.doggybear233.saltaudiocopy.scsi.TocEntry(1, true, 1, 0),
                com.doggybear233.saltaudiocopy.scsi.TocEntry(2, true, 1, 2000),
                com.doggybear233.saltaudiocopy.scsi.TocEntry(0xAA, true, 1, 5000)
            )
        )
        val list = com.doggybear233.saltaudiocopy.meta.MusicBrainzClient
            .parseDiscidResponse(com.doggybear233.saltaudiocopy.meta.Json.parse(json), toc, "id", "cddb")
        assertEquals(2, list[0].totalDiscs, "双碟装总碟数应为 2")
        assertEquals(1, list[0].discNumber, "轨数与光盘一致的 medium 是第 1 张")
    }

    @Test
    fun parsesGnudbRecord() {
        val text = """
            210 rock 1f090a1d CD database entry follows
            # xmcd
            DTITLE=松田聖子 / Strawberry Time
            DYEAR=1987
            DGENRE=J-Pop
            TTITLE0=Strawberry Time
            TTITLE1=Kimono Beat
            EXTD=ARTIST=松田聖子
            .
        """.trimIndent()
        val album = GnudbClient.parseReadResponse(text.lines())
        assertNotNull(album)
        assertEquals("松田聖子", album.albumArtist)
        assertEquals("Strawberry Time", album.albumTitle)
        assertEquals("1987", album.year)
        assertEquals("J-Pop", album.genre)
        assertEquals(listOf("Strawberry Time", "Kimono Beat"), album.tracks.map { it.title })
        assertEquals("gnudb", album.source)
    }

    @Test
    fun gnudbReturnsNullOnError() {
        assertEquals(null, GnudbClient.parseReadResponse(listOf("500 Unknown application")))
    }

    @Test
    fun jsonPathToleratesMissingKeys() {
        val v = Json.parse("""{"a":{}}""")
        assertEquals(null, Json.path(v, "a", "b", "c"))
        assertTrue(Json.arr(Json.path(v, "zzz")).isEmpty())
    }
}
