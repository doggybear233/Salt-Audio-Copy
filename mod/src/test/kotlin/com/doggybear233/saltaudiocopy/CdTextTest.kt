package com.doggybear233.saltaudiocopy.scsi

import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.MetadataService
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CD-TEXT 解析（合成 pack 驱动，不需要光驱）。
 *
 * 报文来自真实驱动实测：无 CD-TEXT 的盘返回 `00 02` 后全零，所以「空」必须判成 null 而不是
 * 一张空专辑——否则元数据链路会把用户带到「未知专辑」的死路上。
 */
class CdTextTest {

    private fun toc(tracks: Int): DiscToc =
        DiscToc(1, tracks, (0 until tracks).map { TocEntry(it + 1, true, 1, it * 5000) })

    /** CD-TEXT 最常见的编码（字符集 0x00 = ISO-8859-1），用同名编码生成字节以保证往返一致。 */
    private fun latin(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun sjis(s: String) = s.toByteArray(charset("MS932"))

    @Test
    fun emptyResponseMeansNoCdText() {
        val data = ByteArray(4 + 18) // 声明长度 2、其余全零，与真实驱动一致
        data[1] = 2
        assertNull(CdText.parse(data))
        assertNull(CdText.parse(ByteArray(2)))
    }

    @Test
    fun parsesAlbumAndTrackTitles() {
        val data = CdText.buildResponse(
            listOf(
                Triple(0x80, 0, latin("Fantôme")),
                Triple(0x81, 0, sjis("宇多田ヒカル")),
                Triple(0x80, 1, sjis("道")),
                Triple(0x80, 2, sjis("俺の彼女"))
            )
        )
        val info = CdText.parse(data)
        assertNotNull(info)
        assertTrue(info.hasText)
        assertEquals("Fantôme", info.albumTitle)
        assertEquals("宇多田ヒカル", info.albumPerformer)
        assertEquals("道", info.titles[1])
        assertEquals("俺の彼女", info.titles[2])
    }

    @Test
    fun longTitleSpansMultiplePacks() {
        val title = "A Very Long Album Title That Needs More Than Twelve Bytes"
        val info = CdText.parse(CdText.buildResponse(listOf(Triple(0x80, 0, latin(title)))))
        assertNotNull(info)
        assertEquals(title, info.albumTitle)
    }

    @Test
    fun shiftJisViaSizeInfoCharset() {
        val sjis = "松田聖子".toByteArray(charset("MS932"))
        val data = CdText.buildResponse(
            listOf(
                Triple(0x8F, 0, byteArrayOf(0x00, 0x80.toByte())), // charset = 0x80 (Shift-JIS)
                Triple(0x81, 0, sjis),
                Triple(0x80, 0, "Strawberry Time".toByteArray(Charsets.US_ASCII))
            )
        )
        val info = CdText.parse(data)
        assertNotNull(info)
        assertEquals("松田聖子", info.albumPerformer, "0x8F 声明 Shift-JIS 时必须按 SJIS 解")
        assertEquals(0x80, info.charset)
    }

    @Test
    fun shiftJisAutoDetectedWithoutSizeInfo() {
        val sjis = "宇多田ヒカル".toByteArray(charset("MS932"))
        val info = CdText.parse(CdText.buildResponse(listOf(Triple(0x81, 0, sjis))))
        assertNotNull(info)
        assertEquals("宇多田ヒカル", info.albumPerformer)
        assertTrue(CdText.looksLikeShiftJis(sjis))
        assertFalse(CdText.looksLikeShiftJis(latin("plain latin")))
    }

    @Test
    fun latin1Fallback() {
        val latin = byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte()) // "Café" in ISO-8859-1
        val info = CdText.parse(CdText.buildResponse(listOf(Triple(0x80, 0, latin))))
        assertNotNull(info)
        assertEquals("Café", info.albumTitle)
    }

    @Test
    fun toAlbumMetaFillsPlaceholders() {
        val info = CdText.parse(
            CdText.buildResponse(
                listOf(Triple(0x80, 0, latin("Album")), Triple(0x80, 1, latin("One")))
            )
        )
        assertNotNull(info)
        val meta = info.toAlbumMeta(toc(3), discId = "abc", cddbId = "def")
        assertEquals("Album", meta.albumTitle)
        assertEquals("One", meta.tracks[0].title)
        assertEquals("Track 02", meta.tracks[1].title, "缺失的轨名用占位")
        assertEquals("cd-text", meta.source)
        assertEquals("abc", meta.discId)
    }

    @Test
    fun mergeGivesCdTextPriorityButKeepsMbExtras() {
        val cd = CdText.parse(
            CdText.buildResponse(
                listOf(
                    Triple(0x80, 0, latin("Fantôme (CD-TEXT)")),
                    Triple(0x81, 0, sjis("宇多田ヒカル")),
                    Triple(0x80, 1, sjis("道 (CD-TEXT)"))
                )
            )
        )
        val mb = AlbumMeta(
            albumArtist = "Utada Hikaru",
            albumTitle = "Fantôme",
            year = "2016",
            genre = "J-Pop",
            discId = "d", cddbId = "c", source = "musicbrainz",
            tracks = listOf(
                TrackMeta(1, "Mich", "Utada", 220_000L, null),
                TrackMeta(2, "Ore no Kanojo", "Utada", 250_000L, null)
            )
        )
        val merged = MetadataService.mergeCdText(cd, mb)
        assertEquals("Fantôme (CD-TEXT)", merged.albumTitle, "CD-TEXT 的标题优先")
        assertEquals("宇多田ヒカル", merged.albumArtist)
        assertEquals("2016", merged.year, "年份仍来自 MB")
        assertEquals("J-Pop", merged.genre)
        assertEquals("道 (CD-TEXT)", merged.tracks[0].title)
        assertEquals(220_000L, merged.tracks[0].lengthMs, "时长仍来自 MB")
        assertEquals("Ore no Kanojo", merged.tracks[1].title, "CD-TEXT 没覆盖的轨保留 MB 标题")
        assertEquals("cd-text+musicbrainz", merged.source)
    }

    @Test
    fun mergeWithoutCdTextReturnsMb() {
        val mb = AlbumMeta("a", "b", null, null, "d", "c", "musicbrainz", emptyList())
        assertEquals("musicbrainz", MetadataService.mergeCdText(null, mb).source)
    }
}
