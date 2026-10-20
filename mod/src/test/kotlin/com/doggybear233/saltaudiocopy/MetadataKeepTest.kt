package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 用户实测（F2 之后）：已经手填过元数据的盘再插入时，插件仍会联网查一次，
 * 查不到就把面板上的名字顶成「未知」，用户得再开一次手填面板保存才回来。
 *
 * 两条防线：① 本地已有名字时自动查询直接跳过；② 即便查了、结果为空，也不覆盖已有名字。
 */
class MetadataKeepTest {

    private fun album(artist: String, title: String) = AlbumMeta(
        albumArtist = artist,
        albumTitle = title,
        discId = "X",
        cddbId = "",
        source = "manual",
        tracks = listOf(TrackMeta(1, "曲名"))
    )

    @Test
    fun localNamesAreRecognised() {
        assertFalse(ConfigActions.hasLocalNames(null))
        assertTrue(ConfigActions.hasLocalNames(album("十明", "")))
        assertTrue(ConfigActions.hasLocalNames(album("", "変身のレシピ")))
        assertFalse(ConfigActions.hasLocalNames(album("", "")))
    }

    @Test
    fun emptyLookupKeepsWhatThePanelAlreadyHas() {
        assertEquals("十明", ConfigActions.keepNames(null, "十明"))
        assertEquals("十明", ConfigActions.keepNames("", "十明"))
        assertEquals("十明", ConfigActions.keepNames("   ", "十明"))
    }

    @Test
    fun realLookupResultWins() {
        assertEquals("Def Tech", ConfigActions.keepNames("Def Tech", "十明"))
    }

    @Test
    fun nothingAnywhereStaysEmpty() {
        assertEquals("", ConfigActions.keepNames(null, null))
        assertEquals("", ConfigActions.keepNames("", ""))
    }
}
