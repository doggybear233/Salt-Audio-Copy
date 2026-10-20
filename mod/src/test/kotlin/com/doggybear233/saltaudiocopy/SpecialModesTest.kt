package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumTrackResult
import com.doggybear233.saltaudiocopy.album.CueWriter
import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 清单 I 区（特殊模式）里能在没有光驱/没有真实盘的情况下断言的规则。
 */
class SpecialModesTest {

    private fun track(n: Int, start: Int) = AlbumTrackResult(
        number = n, title = "T$n", artist = "A", startLba = start,
        sectors = 1000, crc32 = 0x1234, errors = 0, elapsedMs = 0,
        file = java.nio.file.Path.of("/tmp/t$n.wav")
    )

    /**
     * 整盘镜像 + 只抓部分音轨：CUE 的 INDEX 必须以「实际拼进镜像的第一轨」为 0，
     * 而不是整盘第 1 轨——否则播放器找错位置。
     */
    @Test
    fun imageCueUsesFirstSelectedTrackAsOrigin() {
        val tracks = listOf(track(3, 20_000), track(4, 30_000))
        val cue = CueWriter.buildImage(AlbumMeta(albumArtist = "A", albumTitle = "B", discId = "", cddbId = "", source = "manual"), tracks, "img.wav", tracks.first().startLba)
        val indexes = cue.lines().filter { it.contains("INDEX 01") }
        assertEquals(2, indexes.size, "两轨应有两个 INDEX 01：$indexes")
        assertTrue(indexes.first().contains("00:00:00"), "第一轨 INDEX 应为 00:00:00：${indexes.first()}")
        assertTrue(!indexes.last().contains("00:00:00"), "第二轨 INDEX 不该从 0 开始：${indexes.last()}")
    }

    /** 逐轨 CUE（非镜像）仍然是每个 FILE 一条 INDEX。 */
    @Test
    fun perTrackCueKeepsOneFilePerTrack() {
        val tracks = listOf(track(1, 0), track(2, 1000))
        val cue = CueWriter.build(AlbumMeta(albumArtist = "A", albumTitle = "B", discId = "", cddbId = "", source = "manual"), tracks, listOf("01.wav", "02.wav"))
        assertEquals(2, cue.lines().count { it.contains("FILE ") })
        assertEquals(2, cue.lines().count { it.contains("INDEX 01") })
    }
}
