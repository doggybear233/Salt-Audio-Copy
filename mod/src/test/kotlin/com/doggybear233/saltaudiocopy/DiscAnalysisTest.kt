package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.DiscAnalysis
import com.doggybear233.saltaudiocopy.album.TrackGap
import com.doggybear233.saltaudiocopy.rip.FakeTrackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscAnalysisTest {

    @Test
    fun detectsIndex00ForEachTrack() {
        val toc = FakeTrackSource.disc3Tracks()
        // 第 2 轨在 1990..1999 是 index 0（10 扇区间隙），第 3 轨无间隙
        val fake = FakeTrackSource(
            sectors = 6000,
            qAt = { lba ->
                when (lba) {
                    in 1990..1999 -> FakeTrackSource.q(0, 2, 0, lba + 150)
                    in 2000..5999 -> FakeTrackSource.q(0, if (lba < 4000) 2 else 3, 1, lba + 150)
                    else -> FakeTrackSource.q(0, 1, 1, lba + 150)
                }
            }
        )
        val gaps = DiscAnalysis.gapsFor(toc, fake)
        assertEquals(3, gaps.size)
        assertEquals(0, gaps[0].gapSectors)
        assertEquals(10, gaps[1].gapSectors)
        assertEquals(1990, gaps[1].index00Lba)
        assertEquals(0, gaps[2].gapSectors)
        assertEquals(2000, gaps[1].sectors)
        assertEquals(2000, gaps[2].sectors)
    }

    @Test
    fun missingSubchannelYieldsZeroGapsWithoutThrowing() {
        val toc = FakeTrackSource.disc3Tracks()
        val fake = FakeTrackSource(sectors = 6000) // qAt 默认不支持
        val gaps = DiscAnalysis.gapsFor(toc, fake)
        assertEquals(3, gaps.size)
        assertTrue(gaps.all { it.gapSectors == 0 })
        assertEquals(6000, gaps.sumOf { it.sectors })
    }

    @Test
    fun describeShowsTableAndTotals() {
        val text = DiscAnalysis.describe(
            listOf(
                TrackGap(1, 0, 2000, 0),
                TrackGap(2, 2000, 1500, 12)
            )
        )
        assertTrue(text.contains("轨"))
        assertTrue(text.contains("00:00:00"))
        assertTrue(text.contains("00:26:50"))
        assertTrue(text.contains("+00:00:12"), text)
        assertTrue(text.contains("2 轨"), text)
        assertTrue(text.contains("1 轨有预间隙"), text)
    }

    @Test
    fun emptyTocReturnsEmpty() {
        assertEquals("没有音轨", DiscAnalysis.describe(DiscAnalysis.gapsFor(
            com.doggybear233.saltaudiocopy.scsi.DiscToc(1, 0, emptyList()), FakeTrackSource(sectors = 10)
        )))
    }
}
