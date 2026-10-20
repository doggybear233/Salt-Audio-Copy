package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.OptionText
import com.doggybear233.saltaudiocopy.ui.Strings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 多语言覆盖（用户反馈 A3：面板里的设置和提示都要跟着语言走）。
 *
 * 宿主配置页本身是静态 JSON、宿主不翻译，所以英文/日文只作用于**面板**；
 * 这里守住两件事：① 设置页每个 key 都有 en/ja 覆盖；② 消息字典能按语言查到。
 */
class I18nTest {

    @AfterTest
    fun reset() {
        Strings.override(null)
    }

    @Test
    fun everySettingKeyIsTranslated() {
        val zh = mutableSetOf<String>()
        val resource = OptionText::class.java.getResourceAsStream("/preference_config.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        for (m in Regex("\"key\"\\s*:\\s*\"([A-Za-z0-9_]+)\"").findAll(resource)) zh += m.groupValues[1]
        // 按钮的 on_click 是 "com.doggybear233.saltaudiocopy.ConfigActions.xxx"（没有括号）
        for (m in Regex("\"on_click\"\\s*:\\s*\"[^\"]*\\.([A-Za-z0-9_]+)\"").findAll(resource)) {
            zh += m.groupValues[1]
        }
        // 期望条数跟着 SettingsSpec 走，不再写死（加/删一个选项不该改这里）
        val expected = com.doggybear233.saltaudiocopy.ui.SettingsSpec.settingKeys.size +
            com.doggybear233.saltaudiocopy.ui.SettingsSpec.actionKeys.size
        assertTrue(zh.size >= expected, "解析到的选项太少（$zh.size < $expected），正则可能失效了")
        for (lang in listOf("en", "ja")) {
            val have = OptionText.overlayKeys(lang)
            val missing = zh - have
            assertTrue(missing.isEmpty(), "$lang 缺少这些选项的翻译：${missing.sorted()}")
        }
    }

    @Test
    fun labelsSwitchWithLanguage() {
        Strings.override(Strings.Lang.ZH)
        assertEquals("输出根目录", OptionText.label("outputRoot"))
        Strings.override(Strings.Lang.EN)
        assertEquals("Output folder", OptionText.label("outputRoot"))
        Strings.override(Strings.Lang.JA)
        assertEquals("出力フォルダ", OptionText.label("outputRoot"))
    }

    @Test
    fun hintsAlsoSwitch() {
        Strings.override(Strings.Lang.EN)
        assertTrue(OptionText.hint("driveSpeed").contains("8x"), OptionText.hint("driveSpeed"))
        Strings.override(Strings.Lang.JA)
        assertTrue(OptionText.hint("driveSpeed").contains("8x"), OptionText.hint("driveSpeed"))
    }

    @Test
    fun messagesTranslateAndFallBack() {
        Strings.override(Strings.Lang.ZH)
        assertEquals("当前没有抓轨任务", Strings.message("当前没有抓轨任务"))
        Strings.override(Strings.Lang.EN)
        assertEquals("No rip is running", Strings.message("当前没有抓轨任务"))
        Strings.override(Strings.Lang.JA)
        assertEquals("実行中のリッピングはありません", Strings.message("当前没有抓轨任务"))
        // 没收录的消息要原样返回中文，不能露出 key
        assertEquals("这句没有翻译", Strings.message("这句没有翻译"))
    }

    @Test
    fun messageCatalogIsNotEmpty() {
        assertTrue(Strings.messageCount(Strings.Lang.EN) >= 45, "英文消息条数偏少：${Strings.messageCount(Strings.Lang.EN)}")
        assertTrue(Strings.messageCount(Strings.Lang.JA) >= 45, "日文消息条数偏少：${Strings.messageCount(Strings.Lang.JA)}")
    }
}
