package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 查元数据期间要锁住会改盘/写盘的按钮。
 *
 * 真机反馈：镜像连不上时查询会卡十几秒，期间按钮全亮着，用户能点抓轨，
 * 既看不到「在等什么」，也可能一边查一边抓。
 */
class MetaBusyGateTest {

    @AfterTest
    fun tearDown() {
        PanelState.metaBusy = false
        PanelState.busy = false
        PanelState.showDisc(null)
    }

    private fun audioDisc() = PanelState.DiscInfo(
        tracks = 3,
        artist = "",
        album = "",
        lengthText = "3:00",
        discId = "x",
        audio = true
    )

    @Test
    fun lookupBlocksRipAndScanUntilItFinishes() {
        PanelState.showDisc(audioDisc())
        PanelState.busy = false
        PanelState.metaBusy = true
        val gate = PanelState.actionGate()
        assertNotNull(gate, "查元数据期间抓轨必须被挡住")
        assertTrue(gate.contains("元数据"), "提示要说清是在等元数据，实际是 $gate")

        PanelState.metaBusy = false
        assertNull(PanelState.actionGate(), "查完之后按钮要恢复可用")
    }
}
