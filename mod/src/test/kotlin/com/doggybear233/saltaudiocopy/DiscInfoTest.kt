package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelFormat
import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 面板上的「这张盘是什么」一行：元数据还没查到时不能写成「未知专辑」。
 */
class DiscInfoTest {

    private fun info(
        tracks: Int = 11,
        artist: String = "",
        album: String = "",
        length: String = "61:23",
        audio: Boolean = true
    ) = PanelState.DiscInfo(tracks, artist, album, length, "o6OqqpbGy_JOCeJyEftrU1T0L6E-", audio)

    @Test
    fun noDiscMeansNoLine() {
        assertNull(PanelFormat.discLine(null))
    }

    @Test
    fun beforeMetadataOnlyCountAndLength() {
        assertEquals("11 轨 · 61:23", PanelFormat.discLine(info()))
    }

    @Test
    fun withMetadataShowsArtistAndAlbum() {
        assertEquals(
            "宇多田ヒカル · Fantôme · 11 轨 · 61:23",
            PanelFormat.discLine(info(artist = "宇多田ヒカル", album = "Fantôme"))
        )
    }

    @Test
    fun placeholderNamesAreSuppressed() {
        assertEquals(
            "11 轨 · 61:23",
            PanelFormat.discLine(info(artist = "未知艺术家", album = "未知专辑"))
        )
    }

    @Test
    fun dataDiscIsMarked() {
        assertEquals(
            "2 轨 · 04:00 · 非音频盘",
            PanelFormat.discLine(info(tracks = 2, length = "04:00", audio = false))
        )
    }

    @Test
    fun durationFormatsMinutesAndHours() {
        assertEquals("1:01:23", PanelFormat.duration(61 * 60_000L + 23_000L))
        assertEquals("1:02:03", PanelFormat.duration(3_723_000L))
        assertEquals("45:07", PanelFormat.duration(2_707_000L))
        assertEquals("--:--", PanelFormat.duration(0))
        assertEquals("--:--", PanelFormat.duration(-5))
    }

    @Test
    fun showDiscIsReadableFromState() {
        PanelState.showDisc(info(artist = "A", album = "B"))
        assertEquals("A · B · 11 轨 · 61:23", PanelFormat.discLine(PanelState.disc))
        PanelState.showDisc(null)
        assertNull(PanelState.disc)
    }
}
