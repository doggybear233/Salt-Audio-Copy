package com.saltrip.rip

import com.saltrip.rip.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 面板状态机：设置草稿、暂停/取消确认、动作闸门。 */
class PanelSettingsTest {

    @AfterTest
    fun reset() {
        PanelState.showDisc(null)
        PanelState.clearProgress()
        PanelState.settingsOpen = false
        PanelState.lastOutDir = null
    }

    @Test
    fun loadSettingsFillsDraft() {
        PanelState.loadSettings(
            folder = "%albumartist%/%albumtitle%",
            file = "%tracknr2% %title%",
            cover = "cover",
            secure = true,
            test = false,
            root = "C:\\Music"
        )
        assertEquals("%albumartist%/%albumtitle%", PanelState.editFolder)
        assertEquals("%tracknr2% %title%", PanelState.editFile)
        assertEquals("cover", PanelState.editCover)
        assertTrue(PanelState.secureMode)
        assertFalse(PanelState.testMode)
        assertEquals("C:\\Music", PanelState.outputRootText)
    }

    @Test
    fun beginClearsPauseAndCancelPrompt() {
        PanelState.paused = true
        PanelState.cancelArmed = true
        PanelState.begin("准备中…")
        assertFalse(PanelState.paused, "开始新任务时应清掉上一次的暂停")
        assertFalse(PanelState.cancelArmed, "开始新任务时应清掉上一次的取消确认")
        assertTrue(PanelState.busy)
        assertNull(PanelState.progress)
    }

    @Test
    fun gateBlocksUntilScan() {
        PanelState.clearProgress()
        assertNull(PanelState.disc)
        val gate = PanelState.actionGate()
        assertTrue(gate != null && gate.contains("扫描"), "未扫描时应提示先扫描，实际：$gate")
    }

    @Test
    fun testModeDefaultsOff() {
        // 默认值来自 RipSettings.testMode，这里确认面板状态默认关闭：
        // 普通用户不该看到「只测不写」这类真机测试动作。
        assertFalse(PanelState.testMode || com.saltrip.rip.settings.RipSettings().testMode)
    }
}
