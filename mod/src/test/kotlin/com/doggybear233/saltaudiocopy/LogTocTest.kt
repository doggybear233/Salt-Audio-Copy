package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumTrackResult
import com.doggybear233.saltaudiocopy.album.LogWriter
import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** 日志里的 TOC 表与 CD-TEXT 状态行。 */
class LogTocTest {

    private val toc = DiscToc(
        1, 2,
        listOf(TocEntry(1, true, 1, 0), TocEntry(2, true, 1, 16575), TocEntry(0xAA, true, 1, 34425))
    )
    private val album = AlbumMeta(
        albumArtist = "A", albumTitle = "B", year = "2016", genre = "J-Pop",
        discId = "x", cddbId = "y", source = "musicbrainz"
    )
    private val tracks = listOf(
        AlbumTrackResult(1, "T1", "A", Path.of("01.wav"), 0, 16575, 1L, 0, 1000),
        AlbumTrackResult(2, "T2", "A", Path.of("02.wav"), 16575, 17850, 2L, 0, 1000)
    )

    private fun log(hasCdText: Boolean?): String = LogWriter.build(
        "0.21.0", "fake drive", toc, album, tracks, 5000, false,
        listOf("readCommand" to "auto"), emptyList(), hasCdText = hasCdText
    )

    @Test
    fun logHasTocTableWithPerTrackLengths() {
        val s = log(true)
        assertTrue(s.contains("TOC"), "缺少 TOC 段")
        assertTrue(s.contains("01  "), "缺少第 1 轨行")
        // 第 1 轨 16575 扇区 = 3:41:00（mm:ss:ff）
        assertTrue(s.contains("03:41:00"), "第 1 轨时长不对：$s")
    }

    @Test
    fun cdTextStatusIsExplicit() {
        assertTrue(log(true).contains("CD-TEXT       : 有"))
        assertTrue(log(false).contains("CD-TEXT       : 无"))
        assertTrue(log(null).contains("CD-TEXT       : 未检测"))
    }
}
