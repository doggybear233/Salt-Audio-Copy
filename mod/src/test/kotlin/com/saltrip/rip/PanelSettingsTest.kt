package com.saltrip.rip

import com.saltrip.rip.settings.RipSettings
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
        PanelState.loadDraft(RipSettings())
    }

    @Test
    fun loadDraftFillsEveryField() {
        val s = RipSettings(
            folderTemplate = "%albumartist%/%albumtitle%",
            fileTemplate = "%tracknr2% %title%",
            coverFileName = "cover",
            secureMode = true,
            testMode = false,
            outputRoot = "C:\\Music"
        )
        PanelState.loadDraft(s)
        assertEquals("%albumartist%/%albumtitle%", PanelState.editFolder)
        assertEquals("%tracknr2% %title%", PanelState.editFile)
        assertEquals("cover", PanelState.editCover)
        assertTrue(PanelState.draft.secureMode)
        assertFalse(PanelState.testMode)
        assertEquals("C:\\Music", PanelState.draft.outputRoot)
        assertEquals(-1, PanelState.editTarget, "刚打开设置时不该预选任何模板框")
    }

    @Test
    fun updateDraftOnlyTouchesWhatIsAsked() {
        PanelState.loadDraft(RipSettings(format = "wav", keepWav = true))
        PanelState.updateDraft { it.copy(format = "flac") }
        assertEquals("flac", PanelState.draft.format)
        assertTrue(PanelState.draft.keepWav, "其他字段不能被顺手改掉")
    }

    @Test
    fun beginClearsPauseAndCancelPrompt() {
        PanelState.paused = true
        PanelState.cancelArmed = true
        PanelState.begin("读取中…")
        assertFalse(PanelState.paused, "开始新任务时要清掉上一次的暂停标记")
        assertFalse(PanelState.cancelArmed, "开始新任务时要清掉上一次的取消确认")
        assertTrue(PanelState.busy)
        assertNull(PanelState.progress)
    }

    @Test
    fun gateBlocksUntilScan() {
        PanelState.clearProgress()
        assertNull(PanelState.disc)
        val gate = PanelState.actionGate()
        assertTrue(gate != null && gate.contains("扫描"), "未扫描时应提示先去扫描；实际：$gate")
    }

    @Test
    fun testModeDefaultsOff() {
        // 真机动作（试写/预扫）默认不出现，避免普通用户点出事
        assertFalse(PanelState.testMode || RipSettings().testMode)
    }
}
