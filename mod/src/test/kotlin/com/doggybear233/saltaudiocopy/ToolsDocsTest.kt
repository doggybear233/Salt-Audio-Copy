package com.doggybear233.saltaudiocopy

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 对外文案与随包许可的一致性。
 *
 * 两个真实事故：
 * 1. 三档校验在实现里是 1/2/5 次一致，四份对外文案却都写「2/3/5」——用户按 3 次理解 medium，
 *    对照日志里的 `identical=2` 会以为是 bug。
 * 2. 插件内置了 GPL-2.0-or-later 的 `flac.exe`，但包内只有 MIT 与 BSD 文本，没有 GPL 全文。
 *
 * 测试工作目录是 `mod/`，仓库根是 `..`；找不到文件就跳过（IDE 里可能用别的目录）。
 */
class ToolsDocsTest {

    private fun root(): Path? {
        val dir = Path.of("..").toAbsolutePath().normalize()
        return if (Files.isDirectory(dir.resolve("docs"))) dir else null
    }

    private fun read(path: Path): String? = if (Files.isRegularFile(path)) Files.readString(path) else null

    @Test
    fun outwardDocsStateTheRealMatchCounts() {
        val root = root() ?: return
        val files = listOf(
            "README.md",
            "release/github/RELEASE-NOTES.md",
            "release/steam/description.txt",
            "release/steam/description.en.txt"
        )
        val stale = listOf("2/3/5", "2、3、5", "2 / 3 / 5")
        for (rel in files) {
            val text = read(root.resolve(rel)) ?: continue
            for (bad in stale) {
                assertFalse(
                    text.contains(bad),
                    "$rel 仍写 $bad；实现是 low/medium/high -> 1/2/5 次一致（见 SecureRip.policyFor）"
                )
            }
        }
    }

    @Test
    fun modReadmeDoesNotDescribeTheOldFourGroupWorld() {
        val readme = read(Path.of("README.md").toAbsolutePath()) ?: return
        val stale = listOf(
            "共四组",
            "69 个用例",
            "不打包 GPL 二进制",
            "面板 UI 还没做",
            "medium 要求连续 3 次一致"
        )
        for (s in stale) {
            assertFalse(readme.contains(s), "mod/README.md 仍含过期描述「$s」；1.0.0 是 8 组 46 项、内置 FLAC、有面板")
        }
    }

    @Test
    fun thirdPartyNoticeCoversTheBundledGplBinary() {
        val root = root() ?: return
        val notices = read(root.resolve("THIRD-PARTY-NOTICES.md"))
            ?: throw AssertionError("缺少 THIRD-PARTY-NOTICES.md：包内有 GPL 二进制就必须有第三方声明")
        assertTrue(notices.contains("GPL-2.0"), "声明里必须写明 flac.exe 是 GPL-2.0-or-later")
        assertTrue(notices.contains("BSD-3-Clause"), "声明里必须写明 libFLAC 是 BSD-3-Clause")
        assertTrue(
            notices.contains("xiph.org/flac") || notices.contains("github.com/xiph/flac"),
            "必须给出 GPL 要求的对应源码获取地址"
        )
        for (f in listOf("COPYING.GPL", "COPYING.FDL", "AUTHORS")) {
            val p = Path.of("src/main/resources/native/win64/$f")
            assertTrue(Files.isRegularFile(p), "包内缺少 $f（GPL 要求随二进制分发许可全文）")
        }
        val gpl = Files.readString(Path.of("src/main/resources/native/win64/COPYING.GPL"))
        assertTrue(gpl.contains("GNU GENERAL PUBLIC LICENSE"), "COPYING.GPL 内容不像 GPL 全文")
    }

    @Test
    fun optionsTableListsEveryExposedSetting() {
        val root = root() ?: return
        val table = read(root.resolve("options-table.md")) ?: return
        val rows = Regex("""^\|\s*\d+\s*\|[^|]*\|\s*`([A-Za-z0-9_]+)`""", RegexOption.MULTILINE)
            .findAll(table).map { it.groupValues[1] }.toSet()
        // 用反射读字段，不再 grep 源码：以前改个缩进/换行就红，测的是格式不是行为（F-415）
        val fields = com.doggybear233.saltaudiocopy.settings.RipSettings::class.java.declaredFields
            .map { it.name }
            .filter { !it.startsWith("$") }   // Compose 编译器加的 $stable 之类
            .filter { it != "appearance" }   // 0.29.7 起不提供界面外观选项，表里故意没有
            .toSet()

        val missing = fields - rows
        assertTrue(missing.isEmpty(), "options-table.md 缺少这些设置项的行：$missing")
        val extra = rows - fields
        assertTrue(extra.isEmpty(), "options-table.md 有 RipSettings 里不存在的键：$extra")
        assertTrue(
            table.contains("**8 组 / ${fields.size} 个设置键"),
            "表头声明的键数要与实际 ${fields.size} 个一致（F-425：46 键 / 45 行 曾互相矛盾）"
        )
    }

    @Test
    fun releaseNotesUseAbsoluteImageUrls() {
        val root = root() ?: return
        val notes = read(root.resolve("release/github/RELEASE-NOTES.md")) ?: return
        assertFalse(
            notes.contains("](screenshots/"),
            "Release 正文里的相对路径在 GitHub 上会 404，必须用 raw.githubusercontent 绝对链接"
        )
        if (notes.contains("![")) {
            assertTrue(
                notes.contains("https://raw.githubusercontent.com/"),
                "有图片就必须是绝对链接"
            )
        }
    }
}
