package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.settings.RipSettings
import com.doggybear233.saltaudiocopy.ui.NumericDraft
import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * r3-09 的回归：设置页的确认态、脏标记与整份备份。
 *
 * 这三个点以前都没有界面/数据证据：确认态不可见且永不过期、备份只写 11 个键、
 * 数字草稿改了不触发重组。
 */
class BatchR309Test {

    @AfterTest
    fun cleanup() {
        PanelState.resetArmed = false
        NumericDraft.clearAll()
    }

    @Test
    fun backupCoversEveryPersistedSetting() {
        val keys = RipSettings().dumpKeys()
        // 46 个设置键里只有迁移用的 KEY_INCLUDE_GAPS 没有同名字段
        assertTrue(keys.size >= 45, "备份只覆盖了 ${keys.size} 个键：${keys.keys}")
        assertTrue(keys.containsKey("offsetSamples"), keys.keys.toString())
        assertTrue(keys.containsKey("annotateCueCrc"), keys.keys.toString())
        assertTrue(keys.containsKey("autoLookupOnInsert"), keys.keys.toString())
        assertEquals("", keys["proxyUrl"])
    }

    @Test
    fun closingSettingsDropsTheArmedReset() {
        PanelState.resetArmed = true
        ConfigActions.closeSettings()
        assertFalse(PanelState.resetArmed, "关掉设置页后不该还停在「再点一次」的待确认态")
    }

    @Test
    fun numericDraftChangesAreVisibleToRecomposition() {
        val before = PanelState.numericRevision
        NumericDraft.put("offsetSamples", "-")
        assertTrue(PanelState.numericRevision > before, "写数字草稿必须让设置页能重组")
        NumericDraft.clear("offsetSamples")
        NumericDraft.clearAll()
    }
}
