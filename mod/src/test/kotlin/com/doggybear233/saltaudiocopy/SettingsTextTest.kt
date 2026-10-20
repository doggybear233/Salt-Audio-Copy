package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.OptionText
import com.doggybear233.saltaudiocopy.ui.SettingsSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 面板文案来自 `preference_config.json`（[OptionText]），不是 `SettingsSpec` 里的空串。
 *
 * 曾经面板直接用 `opt_<key>` 去 `Strings` 查，查不到就退回 key，
 * 于是界面上出现 `opt_gapMethod` 这种标题——这组测试盯的就是这件事。
 */
class SettingsTextTest {

    @Test
    fun everySettingKeyHasHumanLabel() {
        val missing = SettingsSpec.settingKeys.filter { key ->
            val label = OptionText.label(key, "")
            label.isBlank() || label == key || label.startsWith("opt_")
        }
        assertEquals(emptyList(), missing, "这些键在 preference_config.json 里没有 title")
    }

    @Test
    fun everyActionHasHumanLabel() {
        val missing = SettingsSpec.actionKeys.filter { key ->
            val label = OptionText.label(key, "")
            label.isBlank() || label == key
        }
        assertEquals(emptyList(), missing, "这些按钮在 preference_config.json 里没有 title")
    }

    @Test
    fun hintsComeFromJsonSummary() {
        // 开关项在 JSON 里都有 summary；面板靠它解释「这个开关到底影响什么」。
        val noHint = SettingsSpec.settingKeys.filter { OptionText.hint(it, "").isBlank() }
        assertTrue(noHint.isEmpty(), "这些键缺 summary：$noHint")
    }

    @Test
    fun unknownKeyFallsBackToGivenText() {
        assertEquals("自定义", OptionText.label("noSuchKey", "自定义"))
        assertFalse(OptionText.label("noSuchKey").startsWith("opt_"))
    }
}
