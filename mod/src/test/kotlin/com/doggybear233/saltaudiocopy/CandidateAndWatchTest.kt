package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.CoverArtClient
import com.doggybear233.saltaudiocopy.scsi.ScsiException
import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1 的离线可测部分：候选状态机、封面 URL 策略、换盘判定的「没盘 / 真故障」区分。
 */
class CandidateAndWatchTest {

    private fun scsi(key: Int, asc: Int, ascq: Int = 0) =
        ScsiException(
            message = "sense 0x70/$key/$asc/$ascq",
            scsiStatus = 2,
            senseKey = key,
            asc = asc,
            ascq = ascq
        )

    @Test
    fun coverSizeZeroMeansOriginal() {
        assertEquals(
            "https://coverartarchive.org/release/abc/front",
            CoverArtClient.urlFor("abc", 0)
        )
        assertEquals(
            "https://coverartarchive.org/release/abc/front-250",
            CoverArtClient.urlFor("abc", 250)
        )
        assertEquals(
            "https://coverartarchive.org/release/abc/front",
            CoverArtClient.urlFor("abc", -1)
        )
    }

    @Test
    fun candidateLabelShowsYearAndTrackCount() {
        val c = com.doggybear233.saltaudiocopy.meta.MetadataCandidates.Candidate(
            index = 0,
            albumArtist = "宇多田ヒカル",
            albumTitle = "Fantôme",
            year = "2016",
            trackCount = 11,
            releaseId = "x",
            source = "musicbrainz"
        )
        assertEquals("宇多田ヒカル · Fantôme（2016） · 11 轨", c.label())

        val noYear = c.copy(year = null)
        assertEquals("宇多田ヒカル · Fantôme · 11 轨", noYear.label())
    }

    @Test
    fun selectingCandidateClampsToRange() {
        val list = listOf(
            PanelState.Candidate(0, "A", "musicbrainz"),
            PanelState.Candidate(1, "B", "musicbrainz")
        )
        PanelState.showCandidates(list, selected = 5)
        assertEquals(1, PanelState.selectedCandidate)

        PanelState.selectCandidate(-3)
        assertEquals(1, PanelState.selectedCandidate, "越界不应改变选择")

        PanelState.selectCandidate(0)
        assertEquals(0, PanelState.selectedCandidate)

        PanelState.showCandidates(emptyList())
        assertEquals(-1, PanelState.selectedCandidate)
    }

    @Test
    fun notReadyAndMediumMissingAreNotFailures() {
        assertTrue(DiscWatcher.isEmptyDisc(scsi(2, 0x3A)), "无盘应视为空，不是故障")
        assertTrue(DiscWatcher.isEmptyDisc(scsi(2, 0x04)), "未就绪应视为空")
        assertTrue(DiscWatcher.isEmptyDisc(IllegalStateException("没有检测到可用的光驱")))
        // 介质格式不兼容 = 盘在里面（数据盘），要让用户看到错误，不能静默当空盘。
        assertFalse(DiscWatcher.isEmptyDisc(scsi(5, 0x30, 0x02)), "数据盘不该被当成空盘")
        assertFalse(DiscWatcher.isEmptyDisc(RuntimeException("boom")))
    }

    @Test
    fun emptyDiscReasonSurvivesWrapping() {
        val wrapped = IllegalStateException(
            "找到了光驱，但读不到光盘信息：无法读取光盘",
            scsi(2, 0x3A)
        )
        assertTrue(DiscWatcher.isEmptyDisc(wrapped), "包装层的文案不影响底层 sense 判定")
    }

    @Test
    fun candidateWithoutCoverDecodesToNull() {
        assertNull(com.doggybear233.saltaudiocopy.ui.CoverImages.decode(null))
        assertNull(com.doggybear233.saltaudiocopy.ui.CoverImages.decode(ByteArray(0)))
        assertNull(com.doggybear233.saltaudiocopy.ui.CoverImages.decode(byteArrayOf(1, 2, 3)), "坏图不应抛异常")
    }
}
