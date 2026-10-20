package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.DriveSelect
import com.doggybear233.saltaudiocopy.rip.ScanResult
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.InquiryData
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import com.doggybear233.saltaudiocopy.ui.ManualDraft
import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 盘身份与并发：盘符三套语义、保存时换盘、监视线程退出。
 */
class BatchR204Test {

    private val toc = DiscToc(
        1, 2,
        listOf(
            TocEntry(1, true, 1, 0),
            TocEntry(2, true, 1, 2_000),
            TocEntry(0xAA, true, 1, 4_000)
        )
    )

    private fun scan(path: String) =
        ScanResult(path, InquiryData("SALT", "FAKE CD", "1.00", 0x05), toc)

    @Test
    fun driveLettersAreNormalisedBeforeMatching() {
        val scans = listOf(scan("\\\\.\\E:"), scan("\\\\.\\F:"))
        // 设置里可能是裸字母、带冒号、甚至 SPTI 全路径：都该选中同一台
        assertEquals("\\\\.\\F:", DriveSelect.pick(scans, "F")?.path)
        assertEquals("\\\\.\\F:", DriveSelect.pick(scans, "f:")?.path)
        assertEquals("\\\\.\\F:", DriveSelect.pick(scans, "\\\\.\\F:")?.path)
        assertEquals("\\\\.\\E:", DriveSelect.pick(scans, "e")?.path)
    }

    @Test
    fun unknownPreferredDriveFallsBackToFirst() {
        val scans = listOf(scan("\\\\.\\E:"))
        // pick 选不到时返回 null，由调用方决定退回「第一台音频 CD」——这里锁住「不瞎猜」这条
        assertEquals(null, DriveSelect.pick(scans, "Z"))
    }

    @Test
    fun discChangeIsDetectedOnlyWhenBothSidesKnown() {
        val a = "AAA"
        val b = "BBB"
        assertTrue(ConfigActions.discChanged(a, b), "两张不同的盘必须拦住")
        assertFalse(ConfigActions.discChanged(a, a))
        assertFalse(ConfigActions.discChanged("", b), "旧草稿没有指纹时放行")
        assertFalse(ConfigActions.discChanged(a, ""), "扫不到指纹时放行")
    }

    @Test
    fun savingIsRefusedWhileRipping() {
        PanelState.busy = true
        try {
            PanelState.manual = ManualDraft(albumArtist = "十明", albumTitle = "変身のレシピ")
            PanelState.manualOpen = true
            ConfigActions.saveManualMeta()
            assertTrue(PanelState.manualOpen, "拒绝保存时草稿面板要留着")
            assertTrue(
                PanelState.status.contains("抓轨进行中"),
                "要明确告诉用户为什么没保存：${PanelState.status}"
            )
        } finally {
            PanelState.busy = false
            PanelState.manualOpen = false
        }
    }

    @Test
    fun watcherStopWaitsForTheThread() {
        DiscWatcher.tickOverride = { true }
        try {
            DiscWatcher.start()
            Thread.sleep(80)
            DiscWatcher.stop()
            assertFalse(DiscWatcher.workerAliveForTest(), "stop() 之后旧线程必须已经退出")
        } finally {
            DiscWatcher.tickOverride = null
            DiscWatcher.stop()
        }
    }
}
