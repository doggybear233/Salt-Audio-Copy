package com.saltrip.rip.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 面板显示逻辑（不涉及 Compose 渲染）。
 *
 * 抓轨面板的数值一旦显示错（比如「剩余 0 秒」卡到天荒地老、速度 NaN），
 * 用户会直接以为程序挂了——所以这些格式化必须能被单测钉住。
 */
class PanelFormatTest {

    @AfterTest
    fun resetState() {
        PanelState.busy = false
        PanelState.stage = ""
        PanelState.progress = null
        PanelState.status = ""
        PanelState.detail = ""
    }

    @Test
    fun speedFormatting() {
        assertEquals("4.8x", PanelFormat.speed(4.81))
        assertEquals("1.0x", PanelFormat.speed(1.0))
        assertEquals("-", PanelFormat.speed(0.0))
        assertEquals("-", PanelFormat.speed(-3.0))
        assertEquals("-", PanelFormat.speed(Double.NaN))
        assertEquals("-", PanelFormat.speed(Double.POSITIVE_INFINITY))
    }

    @Test
    fun etaFormatting() {
        assertEquals("剩余时间未知", PanelFormat.eta(0))
        assertEquals("剩余时间未知", PanelFormat.eta(-5))
        assertEquals("剩余约 45 秒", PanelFormat.eta(45))
        assertEquals("剩余约 1 分", PanelFormat.eta(60))
        assertEquals("剩余约 1 分 20 秒", PanelFormat.eta(80))
        assertEquals("剩余约 59 分 59 秒", PanelFormat.eta(3599))
        assertEquals("剩余约 1 小时 0 分", PanelFormat.eta(3600))
        assertEquals("剩余约 2 小时 5 分", PanelFormat.eta(7500))
    }

    @Test
    fun trackLineFormatting() {
        assertEquals("第 3/11 轨 · 62%", PanelFormat.trackLine(3, 11, 0.627f))
        assertEquals("第 1/1 轨 · 0%", PanelFormat.trackLine(1, 1, -1f))
        assertEquals("第 2/2 轨 · 100%", PanelFormat.trackLine(2, 2, 2f))
        assertEquals("50%", PanelFormat.trackLine(1, 0, 0.5f))
    }

    @Test
    fun lifecycleMovesBetweenStates() {
        PanelState.begin("抓取第 1 轨")
        assertTrue(PanelState.busy)
        assertEquals("抓取第 1 轨", PanelState.stage)
        assertNull(PanelState.progress)

        PanelState.onProgress(PanelState.Progress(0.5f, 4.2, 30, 2, 11))
        val p = PanelState.progress
        assertNotNull(p)
        assertEquals(0.5f, p.fraction)

        PanelState.finish("完成", "11 轨")
        assertFalse(PanelState.busy)
        assertEquals("", PanelState.stage)
        assertNull(PanelState.progress)
        assertEquals("完成", PanelState.status)
    }

    @Test
    fun clearProgressKeepsStatusLine() {
        PanelState.begin("x")
        PanelState.set("正在抓取", "细节")
        PanelState.onProgress(PanelState.Progress(0.1f, 1.0, 5, 1, 2))
        PanelState.clearProgress()
        assertFalse(PanelState.busy)
        assertNull(PanelState.progress)
        assertEquals("", PanelState.stage)
        assertEquals("正在抓取", PanelState.status, "清进度不能把状态行一起清掉")
    }
}
