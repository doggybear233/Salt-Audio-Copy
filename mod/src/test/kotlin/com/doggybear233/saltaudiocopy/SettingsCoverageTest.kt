package com.doggybear233.saltaudiocopy

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 设置项的「存得住」静态检查。
 *
 * 宿主配置不可用时没法做真往返，但可以确认三件事同时存在：
 * 1. `RipSettings` 的每个字段都有一个 KEY_* 常量指向它；
 * 2. 该常量在 `load()` 与 `save()` 里都被用到（漏一处就会「重启后回默认」）；
 * 3. 常量名没有重复指向同一字段（复制粘贴常见错误）。
 *
 * 0.3x 版本真实踩过：`embedCover`、`imageMode`、`readCommand`、`reuseAlbumJson`、
 * `writeAlbumJson`、`analyzeBeforeRip` 六个字段只有内存值，重启后静默丢设置。
 */
class SettingsCoverageTest {

    private val source: String by lazy {
        val file = Path.of("src/main/kotlin/com/doggybear233/saltaudiocopy/settings/RipSettings.kt")
        if (!Files.exists(file)) return@lazy ""
        Files.readString(file)
    }

    @Test
    fun everySettingsFieldIsPersisted() {
        if (source.isEmpty()) return // 从别的目录跑测试时跳过

        val body = source.substringAfter("data class RipSettings(", "")
            .substringBefore("\n) {")
        val fields = Regex("""^\s*val (\w+)\s*:""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toList()
        assertTrue(fields.size > 40, "字段解析失败，只找到 ${fields.size} 个")

        val constants = Regex("""(?:private )?const val (KEY_[A-Z0-9_]+) = "([^"]+)"""")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        val missing = fields.filter { f -> constants.none { it.second == f } }
        if (missing.isNotEmpty()) fail("这些字段没有对应的 KEY_* 常量：$missing")

        val notBoth = fields.mapNotNull { f ->
            val name = constants.first { it.second == f }.first
            val uses = Regex("""\b$name\b""").findAll(source).count()
            // 1 次声明 + load + save = 3
            if (uses < 3) "$f($name 只出现 $uses 次)" else null
        }
        if (notBoth.isNotEmpty()) fail("这些字段没有同时出现在 load/save：$notBoth")
    }

    @Test
    fun noTwoConstantsPointAtTheSameField() {
        if (source.isEmpty()) return
        val constants = Regex("""(?:private )?const val (KEY_[A-Z0-9_]+) = "([^"]+)"""")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        val dup = constants.groupBy { it.second }.filterValues { it.size > 1 }
        assertTrue(dup.isEmpty(), "多个常量指向同一字段：$dup")
    }
}
