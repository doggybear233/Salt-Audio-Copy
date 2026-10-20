package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.Json
import com.doggybear233.saltaudiocopy.meta.MusicBrainzClient
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MusicBrainz 响应的解释层：失败说明不能残留、多碟套装要按 discid 选对 medium。
 */
class MetadataLookupTest {

    private val toc = DiscToc(
        1, 2,
        listOf(
            TocEntry(1, true, 1, 0),
            TocEntry(2, true, 1, 2_000),
            TocEntry(0xAA, true, 1, 4_000)
        )
    )

    @Test
    fun notFoundIsNotAFailure() {
        val r = MusicBrainzClient.interpret(404, "", toc, "DISCID", "CDDB")
        assertTrue(r.releases.isEmpty())
        assertNull(r.failure, "404 是「库里没有这张盘」，不该说成服务故障")
    }

    @Test
    fun serverErrorIsReported() {
        val r = MusicBrainzClient.interpret(503, "busy", toc, "DISCID", "CDDB")
        assertTrue(r.releases.isEmpty())
        assertTrue(r.failure.orEmpty().contains("503"), "要说清是 HTTP 503：${r.failure}")
    }

    @Test
    fun failureDoesNotLeakIntoTheNextLookup() {
        // F-288：以前失败原因存在字段里，下一次成功查询不清空它 → 旧故障被当成本次结论。
        val failed = MusicBrainzClient.interpret(500, "", toc, "DISCID", "CDDB")
        assertTrue(failed.failure != null)
        val missing = MusicBrainzClient.interpret(404, "", toc, "DISCID", "CDDB")
        assertNull(missing.failure, "上一次的 500 不该跟到这一次的 404")
    }

    @Test
    fun mediumIsChosenByDiscIdNotTrackCount() {
        // 两张碟都是 2 轨（现场很常见）：只按轨数选会把第 2 张的曲目贴到第 1 张上。
        val json = """
            {"releases":[{"id":"rel-1","title":"Two Disc","date":"2001-05-01",
              "artist-credit":[{"name":"Some Artist"}],
              "media":[
                {"position":1,"track-count":2,"discs":[{"id":"DISC-A"}],
                 "tracks":[{"number":"1","title":"disc1-track1","length":1000},
                           {"number":"2","title":"disc1-track2","length":1000}]},
                {"position":2,"track-count":2,"discs":[{"id":"DISC-B"}],
                 "tracks":[{"number":"1","title":"disc2-track1","length":1000},
                           {"number":"2","title":"disc2-track2","length":1000}]}
              ]}]}
        """.trimIndent()
        val albums = MusicBrainzClient.parseDiscidResponse(Json.parse(json), toc, "DISC-B", "CDDBID")
        assertEquals(1, albums.size)
        val a = albums.single()
        assertEquals(2, a.discNumber, "查询的是第二张碟，discNumber 应该是 2")
        assertEquals(2, a.totalDiscs)
        assertEquals(listOf("disc2-track1", "disc2-track2"), a.tracks.map { it.title })
    }

    @Test
    fun withoutDiscIdsItFallsBackToTrackCount() {
        val json = """
            {"releases":[{"id":"rel-2","title":"Single","date":"1999",
              "artist-credit":[{"name":"Band"}],
              "media":[
                {"position":1,"track-count":9,"tracks":[{"number":"1","title":"x"}]},
                {"position":2,"track-count":2,"tracks":[{"number":"1","title":"y"},
                  {"number":"2","title":"z"}]}
              ]}]}
        """.trimIndent()
        val a = MusicBrainzClient.parseDiscidResponse(Json.parse(json), toc, "DISC-X", "CDDBID").single()
        assertEquals(2, a.discNumber)
        assertEquals(listOf("y", "z"), a.tracks.map { it.title })
    }
}
