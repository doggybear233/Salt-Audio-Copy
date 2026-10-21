package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R4-01/R4-05：设置会话状态机。
 *
 * - 收起（hide）不是放弃：未保存草稿必须留到下次打开；
 * - 取消（close）才是放弃：下次打开从磁盘重读；
 * - 「恢复默认/恢复备份」的二次确认不能跨收起或重开存活。
 */
class BatchR405SettingsLifecycleTest {

    @AfterTest
    fun reset() {
        PanelState.settingsOpen = false
        PanelState.draftDirty = false
        PanelState.resetArmed = false
        PanelState.resetArmedAt = 0L
        PanelState.restoreArmed = false
        PanelState.restoreArmedAt = 0L
        PanelState.loadDraft(com.doggybear233.saltaudiocopy.settings.RipSettings())
    }

    @Test
    fun hideKeepsUnsavedDraftAndOpenDoesNotReload() {
        ConfigActions.openSettings()
        PanelState.updateDraft { it.copy(fileTemplate = "%artist% - %title% [draft]") }
        assertTrue(PanelState.draftDirty)
        ConfigActions.hideSettings()
        ConfigActions.openSettings()
        assertEquals("%artist% - %title% [draft]", PanelState.draft.fileTemplate, "收起再打开不能把草稿换回磁盘值")
        assertTrue(PanelState.draftDirty)
    }

    @Test
    fun closeDiscardsDraftAndNextOpenReloadsDisk() {
        ConfigActions.openSettings()
        PanelState.updateDraft { it.copy(fileTemplate = "%artist% - %title% [discard]") }
        ConfigActions.closeSettings()
        ConfigActions.openSettings()
        assertFalse(PanelState.draftDirty)
        assertFalse(PanelState.draft.fileTemplate.contains("[discard]"), "取消后重开应回到磁盘值")
    }

    @Test
    fun confirmArmedDoesNotSurviveHideOrOpen() {
        ConfigActions.openSettings()
        PanelState.resetArmed = true
        PanelState.restoreArmed = true
        ConfigActions.hideSettings()
        assertFalse(PanelState.resetArmed, "收起后确认态必须作废")
        assertFalse(PanelState.restoreArmed)
        PanelState.resetArmed = true
        ConfigActions.openSettings()
        assertFalse(PanelState.resetArmed, "重开设置必须是未武装状态")
    }
}
