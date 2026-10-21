package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R3 第 2 批：取消标志与执行器的生命周期。
 *
 * 两个真实故障（F-336 / F-337 / F-357）：
 * 1. `userCancel` 只在整盘抓轨入口复位。取消过一次之后，「只测不写」和「抓第 1 轨」
 *    进到门里第一句就看见 `userCancel == true`，立刻自我取消——按钮看起来完全没用。
 * 2. 卸载时执行器被 `shutdownNow()`，但 `start()` 不会重建。宿主在同一进程里再次启用插件后，
 *    每个投递都抛 `RejectedExecutionException`。
 */
class RunLifecycleTest {

    @Test
    fun startingANewRunClearsThePreviousCancel() {
        val reset = ConfigActions.resetStateForTest()
        assertTrue(reset, "beginRun 必须把 userCancel 与 paused 都复位")
    }

    @Test
    fun workerSurvivesAShutdownAndCanRunAgain() {
        val pool = ConfigActions.workerAfterShutdownForTest()
        val latch = CountDownLatch(1)
        pool.execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "卸载后重建的队列应该真的能执行任务")
    }

    @Test
    fun pausedStateIsVisibleToThePanel() {
        PanelState.paused = true
        assertEquals(true, PanelState.paused)
        ConfigActions.resetStateForTest()
        assertEquals(false, PanelState.paused, "新一次操作开始时面板不该还显示暂停")
    }
}
