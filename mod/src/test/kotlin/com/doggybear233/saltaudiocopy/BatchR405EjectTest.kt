package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第 4 轮 root 修复（R4-07）：抓轨中弹盘/拔线。
 *
 * 以前 `markEjected` 无条件把 `busy` 清掉：旧线程还在写盘，界面已经允许再点「抓取整盘」，
 * 两条流程会同时操作同一个输出目录。现在 busy 期间只请求中止，由 worker 收尾。
 */
class BatchR405EjectTest {

    @AfterTest
    fun reset() {
        PanelState.busy = false
        PanelState.paused = false
        PanelState.cancelArmed = false
        PanelState.status = ""
        PanelState.detail = ""
    }

    @Test
    fun ejectDuringRunKeepsBusyAndRequestsAbort() {
        PanelState.busy = true
        PanelState.paused = true
        PanelState.markEjected("盘被弹出")
        assertTrue(PanelState.busy, "worker 还没收尾，不能在这里把 busy 清掉")
        assertFalse(PanelState.paused, "中止请求发出后不能停在暂停态")
        assertTrue(PanelState.status.contains("没有光盘"), PanelState.status)
    }

    @Test
    fun ejectWhileIdleClearsBusyImmediately() {
        PanelState.busy = false
        PanelState.markEjected("盘被弹出")
        assertFalse(PanelState.busy)
        assertFalse(PanelState.paused)
    }
}
