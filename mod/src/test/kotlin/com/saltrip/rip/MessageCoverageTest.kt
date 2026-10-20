package com.saltrip.rip

import com.saltrip.rip.ui.SettingsSpec
import com.saltrip.rip.ui.Strings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 翻译覆盖率（用户反馈 A3：**所有**用户可见文案都要翻译）。
 *
 * 只看「字典里有几条」没用——漏翻的句子跑起来才露馅。这里从源码里把
 * `Strings.message("…")` / `Strings.fmt("…", …)` 的第一参数字面量全抓出来，
 * 逐个查字典，漏一条就失败。设置页的页名、下拉选项标签也一并查。
 */
class MessageCoverageTest {

    @AfterTest
    fun reset() {
        Strings.override(null)
    }

    private fun ktFiles(): List<Path> {
        val root = Path.of("src/main/kotlin")
        if (!Files.isDirectory(root)) return emptyList()
        Files.walk(root).use { s ->
            return s.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
        }
    }

    /** 源码里出现的中文/英文原文（已反转义）。 */
    private fun literals(): Set<String> {
        val re = Regex("""Strings\s*\.\s*(?:message|fmt)\(\s*"((?:[^"\\]|\\.)*)"""")
        val out = LinkedHashSet<String>()
        for (f in ktFiles()) {
            val text = Files.readString(f, Charsets.UTF_8)
            for (m in re.findAll(text)) {
                out += m.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\\$", "$")
            }
        }
        return out
    }

    @Test
    fun everyMessageLiteralHasBothTranslations() {
        val found = literals()
        assertTrue(found.size > 40, "只抓到 ${found.size} 条消息字面量，正则或目录不对")
        val missing = found.filterNot { Strings.hasMessage(it) }
        assertTrue(
            missing.isEmpty(),
            "这些消息没有 en/ja 译文（补进 mod/src/main/resources/messages-i18n.json）：\n" +
                missing.sorted().joinToString("\n") { "  - $it" }
        )
    }

    @Test
    fun pageTitlesAndOptionLabelsAreTranslated() {
        val wanted = LinkedHashSet<String>()
        for (page in SettingsSpec.pages) {
            wanted += page.title
            for (item in page.items) {
                if (item.label.isNotBlank()) wanted += item.label
                for (o in item.options) if (o.label.isNotBlank()) wanted += o.label
            }
        }
        // 盘符、像素尺寸、"FLAC"、"READ(10)" 这类是技术值，本来就不该翻
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val missing = wanted.filter { cjk.containsMatchIn(it) }.filterNot { Strings.hasMessage(it) }
        assertTrue(
            missing.isEmpty(),
            "设置页这些文案没有 en/ja 译文：\n" + missing.sorted().joinToString("\n") { "  - $it" }
        )
    }
}
