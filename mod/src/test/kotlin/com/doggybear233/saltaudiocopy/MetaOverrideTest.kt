package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.MetaOverride
import com.doggybear233.saltaudiocopy.meta.MetadataService
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

/**
 * 手工元数据覆盖（album.json）与候选排序。
 *
 * 这两块都是纯逻辑，不碰光驱，所以能在无盘环境下完整验证——
 * 对「日版盘 MB 命中错版本」这类最常被投诉的问题来说，这是唯一的自动回归手段。
 */
class MetaOverrideTest {

    private fun tempDir(): java.nio.file.Path = TempDirs.create("saltac-override")

    private fun base(): AlbumMeta = AlbumMeta(
        albumArtist = "宇多田ヒカル",
        albumTitle = "Fantôme",
        year = "2016",
        genre = "J-Pop",
        discId = "o6OqqpbGy_JOCeJyEftrU1T0L6E-",
        cddbId = "b2060bae",
        source = "musicbrainz",
        tracks = listOf(
            TrackMeta(1, "道", "宇多田ヒカル", 220_000L, null),
            TrackMeta(2, "俺の彼女", "宇多田ヒカル", 250_000L, null)
        )
    )

    @Test
    fun loadsSidecarJson() {
        val dir = tempDir()
        val f = dir.resolve("album.json")
        Files.writeString(
            f,
            """
            {
              "albumArtist": "宇多田ヒカル",
              "albumTitle": "Fantôme (Remaster)",
              "year": "2016",
              "tracks": [
                { "number": 1, "title": "道 (Remaster)", "isrc": "JPU901601234" },
                { "number": 2, "title": "俺の彼女" }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        val ov = MetaOverride.load(f)
        assertNotNull(ov)
        assertEquals("Fantôme (Remaster)", ov.albumTitle)
        assertEquals(2, ov.tracks.size)
        assertEquals("JPU901601234", ov.tracks[0].isrc)
    }

    @Test
    fun mergeOnlyOverridesPresentFields() {
        val b = base()
        val ov = AlbumMeta(
            albumArtist = "",
            albumTitle = "Fantôme (Remaster)",
            year = null,
            genre = null,
            discId = "",
            cddbId = "",
            source = "album.json",
            tracks = listOf(TrackMeta(1, "道 (Remaster)", null, null, "JPU901601234"))
        )
        val m = MetaOverride.merge(b, ov)
        assertEquals("宇多田ヒカル", m.albumArtist, "空字符串不应覆盖原值")
        assertEquals("Fantôme (Remaster)", m.albumTitle)
        assertEquals("2016", m.year, "未出现的字段保留原值")
        assertEquals("J-Pop", m.genre)
        assertEquals("道 (Remaster)", m.tracks[0].title)
        assertEquals("宇多田ヒカル", m.tracks[0].artist, "override 没给 artist 时沿用原值")
        assertEquals("JPU901601234", m.tracks[0].isrc)
        assertEquals(220_000L, m.tracks[0].lengthMs, "长度没覆盖时保留原值")
        assertEquals(2, m.tracks.size)
        assertTrue(m.source.contains("musicbrainz") && m.source.contains("album.json"))
    }

    @Test
    fun mergeCanAddTracksMissingFromBase() {
        val b = base()
        val ov = AlbumMeta(
            albumArtist = "", albumTitle = "", year = null, genre = null,
            discId = "", cddbId = "", source = "album.json",
            tracks = listOf(TrackMeta(11, "ボーナストラック", null, null, null))
        )
        val m = MetaOverride.merge(b, ov)
        assertEquals(3, m.tracks.size)
        assertEquals(11, m.tracks.last().number, "合并后按轨号排序")
    }

    @Test
    fun mergeWorksWithoutBase() {
        val ov = AlbumMeta(
            albumArtist = "A", albumTitle = "T", year = "2001", genre = "Rock",
            discId = "d", cddbId = "c", source = "album.json",
            tracks = listOf(TrackMeta(1, "one", "A", null, null))
        )
        val m = MetaOverride.merge(null, ov)
        assertEquals("A", m.albumArtist)
        assertEquals(1, m.tracks.size)
        assertEquals("album.json", m.source)
    }

    @Test
    fun resolvePathPriority() {
        val root = tempDir()
        assertNull(MetaOverride.resolvePath(root, "abc", ""), "没有任何文件时返回 null")

        Files.writeString(root.resolve("album.json"), "{}", Charsets.UTF_8)
        assertEquals(root.resolve("album.json"), MetaOverride.resolvePath(root, "abc", ""))

        val specific = root.resolve("abc.album.json")
        Files.writeString(specific, "{}", Charsets.UTF_8)
        assertEquals(specific, MetaOverride.resolvePath(root, "abc", ""), "<DiscID>.album.json 优先于 album.json")

        val explicit = root.resolve("mine.json")
        Files.writeString(explicit, "{}", Charsets.UTF_8)
        assertEquals(explicit, MetaOverride.resolvePath(root, "abc", explicit.toString()))
    }

    @Test
    fun brokenJsonIsIgnoredNotFatal() {
        val dir = tempDir()
        val f = dir.resolve("album.json")
        Files.writeString(f, "{ this is not json", Charsets.UTF_8)
        assertNull(MetaOverride.load(f))
    }

    @Test
    fun rankPutsMatchingTrackCountFirst() {
        val same = base()
        val other = base().copy(albumTitle = "Multi-disc Box", tracks = base().tracks + base().tracks)
        val ranked = MetadataService.rank(listOf(other, same), 2, preferTrackCount = true)
        assertEquals("Fantôme", ranked.first().albumTitle)
        assertEquals(2, ranked.size, "排序不能丢候选")

        val keepOrder = MetadataService.rank(listOf(other, same), 2, preferTrackCount = false)
        assertEquals("Multi-disc Box", keepOrder.first().albumTitle)
    }

    @Test
    fun mergePrefersTheOverrideCover() {
        // 用户手导的封面（<id>.album.cover.jpg）是他明确的选择，
        // 不能被网上候选的封面盖掉。
        val base = AlbumMeta(albumArtist = "A", albumTitle = "B", discId = "id", cddbId = "cc", source = "test").withCover(byteArrayOf(1, 2), "image/jpeg")
        val ov = AlbumMeta(albumArtist = "", albumTitle = "", discId = "", cddbId = "", source = "album.json").withCover(byteArrayOf(9, 9, 9), "image/png")
        val merged = MetaOverride.merge(base, ov)
        assertEquals(3, merged.cover!!.size)
        assertEquals("image/png", merged.coverMime)
    }
}
