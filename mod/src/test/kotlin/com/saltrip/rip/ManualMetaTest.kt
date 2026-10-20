package com.saltrip.rip

import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.meta.TrackMeta
import com.saltrip.rip.ui.ManualDraft
import com.saltrip.rip.ui.ManualEntry
import com.saltrip.rip.ui.ManualTrack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 手填元数据的纯逻辑：占位过滤、空标题回落、缓存复用、按钮切换的四个象限。
 *
 * 这些规则决定「抓轨文件名叫什么」，错一次就会生成 `Track 05` 之外的怪名字，
 * 所以不放在 UI 里靠肉眼验。
 */
class ManualMetaTest {

    @Test
    fun placeholderValuesAreTreatedAsEmpty() {
        assertEquals("", ManualDraft.displayable(null))
        assertEquals("", ManualDraft.displayable("  "))
        assertEquals("", ManualDraft.displayable("未知艺术家"))
        assertEquals("", ManualDraft.displayable("Unknown Album"))
        assertEquals("宇多田ヒカル", ManualDraft.displayable(" 宇多田ヒカル "))
    }

    @Test
    fun emptyTitleFallsBackToTrackNumber() {
        val d = ManualDraft(
            albumArtist = "Def Tech",
            albumTitle = "Greatest Hits",
            tracks = listOf(ManualTrack(5, title = ""), ManualTrack(7, title = "Lovin' You"))
        )
        val a = d.toAlbumMeta("DISCID")
        assertEquals("Track 05", a.tracks[0].title)
        assertEquals("Lovin' You", a.tracks[1].title)
        // 空轨艺术家留 null，写标签时回落到专辑艺术家。
        assertEquals(null, a.tracks[0].artist)
        assertEquals("manual", a.source)
        assertEquals("DISCID", a.discId)
    }

    @Test
    fun withTrackOnlyTouchesGivenNumber() {
        val d = ManualDraft(tracks = listOf(ManualTrack(1, "a"), ManualTrack(2, "b")))
        val edited = d.withTrack(2, title = "B", artist = "X")
        assertEquals("a", edited.tracks[0].title)
        assertEquals("B", edited.tracks[1].title)
        assertEquals("X", edited.tracks[1].artist)
        // 越界不该抛，也不该改动
        assertEquals(d, d.withTrack(9, title = "nope"))
    }

    @Test
    fun forDiscPrefersCachedAlbumJson() {
        val cached = AlbumMeta(
            albumArtist = "宇多田ヒカル",
            albumTitle = "Fantôme",
            year = "2016",
            discId = "X",
            cddbId = "Y",
            source = "album.json",
            tracks = listOf(TrackMeta(1, "道"), TrackMeta(2, "俺の彼女")),
            discNumber = 2,
            totalDiscs = 3
        )
        val d = ManualDraft.forDisc(trackCount = 2, artist = "面板上的", album = "面板专辑", cached = cached)
        assertEquals("宇多田ヒカル", d.albumArtist)
        assertEquals("Fantôme", d.albumTitle)
        assertEquals("道", d.tracks[0].title)
        assertEquals(2, d.discNumber)
        assertEquals(3, d.totalDiscs)
    }

    @Test
    fun forDiscFallsBackToPanelAndTrackPlaceholders() {
        val d = ManualDraft.forDisc(trackCount = 3, artist = "Some Artist", album = null, cached = null)
        assertEquals("Some Artist", d.albumArtist)
        assertEquals("", d.albumTitle)
        assertEquals(3, d.tracks.size)
        assertEquals("Track 01", d.tracks[0].title)
        assertEquals(listOf(1, 2, 3), d.tracks.map { it.number })
    }

    @Test
    fun buttonTurnsManualOnlyAfterEmptyLookup() {
        // 还没查过：保持「刷新元数据」
        assertFalse(ManualEntry.buttonIsManual(candidatesEmpty = true, lookupTried = false))
        // 查过且空：变「手动填写」
        assertTrue(ManualEntry.buttonIsManual(candidatesEmpty = true, lookupTried = true))
        // 有候选：保持「刷新元数据」（用户要换版本，不是重填）
        assertFalse(ManualEntry.buttonIsManual(candidatesEmpty = false, lookupTried = true))
        assertFalse(ManualEntry.buttonIsManual(candidatesEmpty = false, lookupTried = false))
    }

    @Test
    fun hasAnythingIgnoresBlankRows() {
        assertFalse(ManualDraft(tracks = listOf(ManualTrack(1))).hasAnything)
        assertTrue(ManualDraft(albumTitle = "X").hasAnything)
        assertTrue(ManualDraft(tracks = listOf(ManualTrack(1, title = "y"))).hasAnything)
    }
}
