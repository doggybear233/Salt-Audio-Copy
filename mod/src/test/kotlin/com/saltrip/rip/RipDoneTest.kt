package com.saltrip.rip

import com.saltrip.rip.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「已抓完 → 重新抓取 → 二次确认」的状态机。 */
class RipDoneTest {

    @AfterTest
    fun tearDown() {
        PanelState.clearRipped()
    }

    @Test
    fun markRippedSetsFinishedState() {
        PanelState.markRipped(13)
        assertTrue(PanelState.ripDone)
        assertFalse(PanelState.reRipArmed)
        assertTrue(PanelState.status.contains("已抓完"), PanelState.status)
        assertTrue(PanelState.status.contains("13"), PanelState.status)
        assertTrue(PanelState.detail.contains("重新抓取"), PanelState.detail)
    }

    @Test
    fun clearRippedForgetsPreviousDisc() {
        PanelState.markRipped(3)
        PanelState.reRipArmed = true
        PanelState.clearRipped()
        assertFalse(PanelState.ripDone)
        assertFalse(PanelState.reRipArmed)
    }

    @Test
    fun armingSurvivesUntilCleared() {
        PanelState.markRipped(2)
        PanelState.reRipArmed = true
        assertTrue(PanelState.ripDone)
        assertTrue(PanelState.reRipArmed)
        PanelState.reRipArmed = false
        assertTrue(PanelState.ripDone, "解除确认不该忘掉「已抓完」")
    }
}
