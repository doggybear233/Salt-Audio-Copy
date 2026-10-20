package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.NoticeKind
import com.doggybear233.saltaudiocopy.ui.Notify
import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 例行提示走坞上沿的状态条，只有坞不在屏幕上时才退回宿主 toast。
 *
 * 宿主 toast 是整宽贴窗口底的，坞也在底部——用它说「扫描完成」必然压住进度条。
 */
class NoticeTest {

    @BeforeTest
    fun setUp() {
        PanelState.visible = true
        PanelState.notice = null
    }

    @AfterTest
    fun tearDown() {
        PanelState.notice = null
        PanelState.visible = false
    }

    @Test
    fun infoShowsInBarWhenPanelVisible() {
        Notify.info("扫描完成")
        val notice = assertNotNull(PanelState.notice)
        assertEquals("扫描完成", notice.text)
        assertEquals(NoticeKind.INFO, notice.kind)
    }

    @Test
    fun warnUsesWarnKind() {
        Notify.warn("这张盘没有 CD-TEXT")
        assertEquals(NoticeKind.WARN, PanelState.notice?.kind)
    }

    @Test
    fun sameTextRestartsTimer() {
        Notify.info("正在扫描光驱，请稍候…")
        val first = assertNotNull(PanelState.notice).id
        Notify.info("正在扫描光驱，请稍候…")
        val second = assertNotNull(PanelState.notice).id
        assertTrue(second > first, "同一段文案再次触发也要换 id，否则计时不会重置")
    }

    @Test
    fun hiddenPanelDoesNotWriteBar() {
        PanelState.visible = false
        // 宿主 API 不在单测 classpath 上，toastHost 内部 runCatching 会吞掉异常——
        // 这里只要求「不写状态条、不抛出去」。
        Notify.info("扫描完成")
        assertNull(PanelState.notice)
    }
}
