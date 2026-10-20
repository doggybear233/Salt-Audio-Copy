package com.doggybear233.saltaudiocopy

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 设置项的「存得住」静态检查。
 *
 * 宿主配置不可用时没法做真往返，但源码能证明**值落到哪个字段**：
 * `load()` 里必须是 `KEY_X, d.<field>`，`save()` 里必须是 `h.set(KEY_X, s.<field>)`，
 * 且两处字段名相同。只统计 `KEY_*` 出现次数是没用的——把
 * `h.set(KEY_EMBED_COVER, s.writeCue)` 写错字段时，计数依旧满足。
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

    private fun fields(): List<String> {
        val body = source.substringAfter("data class RipSettings(", "").substringBefore("\n) {")
        return Regex("""^\s*val (\w+)\s*:""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toList()
    }

    private fun constants(): List<Pair<String, String>> =
        Regex("""(?:private )?const val (KEY_[A-Z0-9_]+) = "([^"]+)"""")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    private fun loadedPairs(): Map<String, String> =
        Regex("""read(?:Bool|Int|Str|String)?\(\s*h,\s*(KEY_[A-Z0-9_]+)\s*,\s*d\.(\w+)""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
            .let { m ->
                // 也有形如 h.get(KEY_X, d.field) 的写法
                Regex("""h\.get\(\s*(KEY_[A-Z0-9_]+)\s*,\s*d\.(\w+)""")
                    .findAll(source)
                    .associate { it.groupValues[1] to it.groupValues[2] } + m
            }

    private fun savedPairs(): Map<String, String> =
        Regex("""h\.set\(\s*(KEY_[A-Z0-9_]+)\s*,\s*s\.(\w+)""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun everySettingsFieldIsPersisted() {
        if (source.isEmpty()) return // 从别的目录跑测试时跳过

        val fields = fields()
        assertTrue(fields.size > 40, "字段解析失败，只找到 ${fields.size} 个")

        val constants = constants()
        val missing = fields.filter { f -> constants.none { it.second == f } }
        if (missing.isNotEmpty()) fail("这些字段没有对应的 KEY_* 常量：$missing")
    }

    @Test
    fun loadAndSaveUseTheSameFieldForEachKey() {
        if (source.isEmpty()) return
        val loaded = loadedPairs()
        val saved = savedPairs()
        // 只检查「一个常量对应一个字段」的键；includeGaps 这类历史迁移键没有对应字段
        val fieldOf = constants().filter { it.second in fields() }.associate { (key, field) -> key to field }

        val notLoaded = fieldOf.keys - loaded.keys
        if (notLoaded.isNotEmpty()) fail("这些键在 load() 里没有映射：$notLoaded")
        val notSaved = fieldOf.keys - saved.keys
        if (notSaved.isNotEmpty()) fail("这些键在 save() 里没有映射：$notSaved")

        val mismatches = fieldOf.mapNotNull { (key, expected) ->
            when {
                loaded[key] != expected -> "$key load 到 ${loaded[key]}，应为 $expected"
                saved[key] != expected -> "$key 存的是 ${saved[key]}，应为 $expected"
                else -> null
            }
        }
        if (mismatches.isNotEmpty()) fail("设置读写字段不一致：$mismatches")
    }

    @Test
    fun noTwoConstantsPointAtTheSameField() {
        if (source.isEmpty()) return
        val dup = constants().groupBy { it.second }.filterValues { it.size > 1 }
        assertTrue(dup.isEmpty(), "多个常量指向同一字段：$dup")
    }
}
