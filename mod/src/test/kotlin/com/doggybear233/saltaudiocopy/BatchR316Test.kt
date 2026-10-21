package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.SettingsSpec
import com.doggybear233.saltaudiocopy.ui.Strings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 第三轮延后项里「测试有效性」那批的回归测试。
 *
 * 覆盖 F-416（宿主语言探测）、F-421（面板逻辑没有任何用例）、F-432（版本文案）：
 * 前两条以前完全没有测试，第三条以前靠注释保证。
 */
class BatchR316Test {

    @AfterTest
    fun restoreLanguage() {
        Strings.override(null)
    }

    /** F-416：没有宿主时按 JVM 的 user.language 判断；有宿主时用 override。 */
    @Test
    fun languageProbeFallsBackToJvmLocale() {
        Strings.override(null)
        // build.gradle.kts 把测试进程钉成 zh-CN，所以这里必须得到中文
        assertEquals(Strings.Lang.ZH, Strings.lang(), "没有宿主时应按 user.language 落到中文")
        Strings.override(Strings.Lang.JA)
        assertEquals(Strings.Lang.JA, Strings.lang(), "override 应优先于 JVM 语言")
        Strings.override(Strings.Lang.EN)
        assertEquals(Strings.Lang.EN, Strings.lang())
    }

    /** F-421：面板设置页的结构不变量（以前 Compose 侧零用例）。 */
    @Test
    fun settingsSpecIsInternallyConsistent() {
        val pages = SettingsSpec.pages
        assertTrue(pages.size >= 6, "至少 6 个二级页，实际 ${pages.size}")
        assertEquals(pages.map { it.id }.distinct().size, pages.size, "页 id 不能重复")
        for (p in pages) {
            assertTrue(p.title.isNotBlank(), "页 ${p.id} 没有标题")
            assertTrue(p.items.isNotEmpty(), "页 ${p.id} 没有任何条目")
            for (item in p.items) {
                assertTrue(item.key.isNotBlank(), "页 ${p.id} 有空的条目 key")
                // label 允许为空：文案真实来源是 preference_config.json（OptionText）
                assertTrue(
                    item.label.isNotBlank() ||
                        com.doggybear233.saltaudiocopy.ui.OptionText.label(item.key).isNotBlank(),
                    "条目 ${item.key} 既没有内联 label，也没有配置文案"
                )
                if (item.options.isNotEmpty()) {
                    assertEquals(
                        item.options.map { it.value }.distinct().size,
                        item.options.size,
                        "条目 ${item.key} 的下拉值有重复"
                    )
                }
            }
        }
        val declared = pages
            .flatMap { p -> p.items.map { it.key } }
            .filter { it !in SettingsSpec.actionKeys }
            .distinct()
        val expected: Set<String> = SettingsSpec.settingKeys.toSet()
        val actual: Set<String> = declared.toSet()
        assertEquals(
            expected, actual,
            "settingKeys 应与页面上的设置条目（不含按钮动作）一一对应"
        )
    }

    /** F-432：README 不能再说「同源」，必须写明两处手写 + 测试兜底。 */
    @Test
    fun readmeDoesNotClaimASingleSourceForTheVersion() {
        val readme = java.nio.file.Path.of("README.md").toAbsolutePath()
        val modReadme = java.nio.file.Path.of("src/main/../../README.md").toAbsolutePath().normalize()
        val text = listOf(readme, modReadme)
            .firstOrNull { java.nio.file.Files.isRegularFile(it) }
            ?.let { java.nio.file.Files.readString(it) } ?: return
        assertTrue(!text.contains("同源"), "版本号不是同源，两处手写 + VersionTest 校验")
    }
}
