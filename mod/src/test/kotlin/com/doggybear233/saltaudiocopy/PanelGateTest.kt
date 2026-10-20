package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 面板动作闸门。
 *
 * 目的：让「没盘 / 插的是数据盘 / 正在忙」这几种情况**按钮直接不可点并说明原因**，
 * 而不是点了之后弹一句错误。这里锁住判定本身，面板与设置页共用。
 */
class PanelGateTest {

    @AfterTest
    fun reset() {
        PanelState.busy = false
        PanelState.showDisc(null)
    }

    private fun audioDisc(tracks: Int = 10) =
        PanelState.DiscInfo(tracks, "宇多田ヒカル", "Fantôme", "61:23", "o6OqqpbGy_JOCeJyEftrU1T0L6E-", audio = true)

    private fun dataDisc() =
        PanelState.DiscInfo(1, "", "", "43:09", "", audio = false)

    @Test
    fun withoutDiscTheActionIsBlockedWithAHint() {
        assertNull(PanelState.disc)
        assertFalse(PanelState.hasAudioDisc)
        assertEquals("先点「扫描」识别光盘", PanelState.actionGate())
    }

    @Test
    fun dataDiscIsBlockedAndSaysWhy() {
        PanelState.showDisc(dataDisc())
        assertFalse(PanelState.hasAudioDisc)
        assertTrue(PanelState.actionGate()!!.contains("不是音频 CD"))
    }

    @Test
    fun audioDiscAllowsRipping() {
        PanelState.showDisc(audioDisc())
        assertTrue(PanelState.hasAudioDisc)
        assertNull(PanelState.actionGate())
    }

    @Test
    fun busyWinsOverEverythingElse() {
        PanelState.showDisc(audioDisc())
        PanelState.busy = true
        assertEquals("正在执行，等当前操作结束", PanelState.actionGate())
    }

    @Test
    fun scanningAgainOverwritesPreviousDisc() {
        PanelState.showDisc(audioDisc())
        PanelState.showDisc(dataDisc())
        assertFalse(PanelState.hasAudioDisc)
        PanelState.showDisc(null)
        assertNull(PanelState.disc)
    }
}
