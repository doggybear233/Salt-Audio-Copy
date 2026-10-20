package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.settings.RipSettings
import com.doggybear233.saltaudiocopy.ui.NumericDraft
import com.doggybear233.saltaudiocopy.ui.PanelState
import com.doggybear233.saltaudiocopy.ui.SettingsAccess
import com.doggybear233.saltaudiocopy.ui.SettingsSpec
import com.doggybear233.saltaudiocopy.ui.actionTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 批次 14：界面与状态。
 *
 * 这一批修的都是「声明与实际不符」：心跳用了上一轮的时间、数字框吞退格与负号、
 * 「清空」chip 抹掉模板、诊断按钮没接线、测试模式是死状态。
 */
class Batch14Test {

    @Test
    fun beginResetsTheHeartbeatSoIdleStartsAtZero() {
        // 上一轮结束时 lastBeatAt 是旧时间；不复位的话首帧就会说「已等 4000 秒」
        PanelState.lastBeatAt = System.currentTimeMillis() - 4_000_000
        PanelState.begin("读 TOC")
        assertEquals(0L, PanelState.lastBeatAt)
        assertTrue(PanelState.beginAt > 0L, "本轮开始时间要记下来，用作无进度时的等待基准")
        PanelState.clearProgress()
    }

    @Test
    fun numericDraftAllowsEmptyAndMinusWhileTyping() {
        NumericDraft.clearAll()
        val s = RipSettings()
        NumericDraft.put("maxRetries", "")
        assertEquals("", SettingsAccess.text(s, "maxRetries"), "删空后输入框应是空的，而不是回落到旧值")

        NumericDraft.put("offsetSamples", "-")
        assertEquals("-", SettingsAccess.text(s, "offsetSamples"), "负号必须能打出来")

        NumericDraft.put("offsetSamples", "-116")
        val shifted = SettingsAccess.withText(s, "offsetSamples", "-116")
        assertEquals(-116, shifted.offsetSamples, "解析成功就写进设置")
        assertEquals("-116", SettingsAccess.text(shifted, "offsetSamples"))
        NumericDraft.clearAll()
    }

    @Test
    fun numericDraftClearedShowsTheRealValue() {
        NumericDraft.clearAll()
        val s = RipSettings(offsetSamples = 6)
        NumericDraft.put("offsetSamples", "")
        NumericDraft.clear("offsetSamples")
        assertEquals("6", SettingsAccess.text(s, "offsetSamples"))
    }

    @Test
    fun everyDeclaredActionIsDispatched() {
        val missing = SettingsSpec.actionKeys.toSet() - actionTargets
        assertTrue(missing.isEmpty(), "这些按钮声明了却没有接线：$missing")
    }

    @Test
    fun testActionsOnlyAppearInTestMode() {
        val quiet = RipSettings(testMode = false)
        val loud = RipSettings(testMode = true)
        val testOnly = listOf("testDisc", "ripFirstTrack")
        for (key in testOnly) {
            val item = assertNotNull(SettingsSpec.item(key), "缺少设置项 $key")
            assertFalse(item.visibleWhen(quiet), "$key 在测试模式关闭时不该显示")
            assertTrue(item.visibleWhen(loud), "$key 在测试模式开启时应该显示")
        }
        // 测试模式本身的开关必须一直在，否则没人能把它打开
        val sw = assertNotNull(SettingsSpec.item("testMode"))
        assertTrue(sw.visibleWhen(quiet))
    }

    @Test
    fun previewDoesNotDependOnReadingTheHostConfig() {
        // 测试环境里宿主配置根本拿不到；预览仍要有值（纯内存算），说明组合期没有读盘依赖
        PanelState.settingsSnapshot = RipSettings(outputRoot = "D:\\音楽")
        PanelState.showDisc(
            PanelState.DiscInfo(2, "十明", "変身のレシピ", "0:50", "id", true)
        )
        PanelState.refreshPreview()
        assertTrue(PanelState.previewPath.isNotBlank() && PanelState.previewPath != "…", PanelState.previewPath)
        PanelState.showDisc(null)
    }
}
