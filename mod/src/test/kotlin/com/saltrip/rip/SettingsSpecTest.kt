package com.saltrip.rip

import com.saltrip.rip.meta.Json
import com.saltrip.rip.settings.RipSettings
import com.saltrip.rip.ui.SettingKind
import com.saltrip.rip.ui.SettingsAccess
import com.saltrip.rip.ui.SettingsSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 设置结构表的自检：面板（[SettingsSpec]）和宿主配置页（`preference_config.json`）必须同源同序。
 *
 * 这两份数据是手写维护的，靠人记住「改一边也要改另一边」迟早出事；这里用测试把它们钉死。
 */
class SettingsSpecTest {

    private fun configs(): List<Any?> {
        val text = javaClass.getResourceAsStream("/preference_config.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        assertNotNull(text, "找不到 preference_config.json")
        return Json.arr(Json.path(Json.parse(text), "configs"))
    }

    @Test
    fun pagesWithUniqueIds() {
        assertEquals(8, SettingsSpec.pages.size, "0.30.1 起是 8 个二级页（抓轨范围并入诊断）")
        val ids = SettingsSpec.pages.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "页 id 不能重复")
        assertTrue(ids.contains(SettingsSpec.DEFAULT_PAGE), "默认页 ${SettingsSpec.DEFAULT_PAGE} 必须存在")
        for (p in SettingsSpec.pages) {
            assertTrue(p.items.isNotEmpty(), "页 ${p.title} 不能是空的")
        }
    }

    @Test
    fun jsonGroupsMirrorSpec() {
        val groups = configs().map { Json.obj(it) }
        assertEquals(SettingsSpec.pages.size, groups.size, "配置页分组数应等于二级页数")

        for ((idx, page) in SettingsSpec.pages.withIndex()) {
            val group = groups[idx]
            // 标题形如「2 · 输出位置」
            val title = Json.str(group["title"]).orEmpty()
            assertTrue(
                title.endsWith(page.title),
                "第 ${idx + 1} 组标题「$title」应以「${page.title}」结尾"
            )
            assertEquals("config.json", Json.str(group["config"]))

            val items = Json.arr(group["preferences"]).map { Json.obj(it) }
            val jsonKeys = items.mapNotNull { p ->
                val type = Json.str(p["type"])
                if (type == "button") null else Json.str(p["key"])
            }
            val specKeys = page.items.filter { it.kind != SettingKind.ACTION }.map { it.key }
            assertEquals(specKeys, jsonKeys, "「${page.title}」的键顺序/内容与 SettingsSpec 不一致")

            // list 的 options 必须逐项一致（值 + 文案），否则面板与宿主页会给出不同选项
            for (item in page.items) {
                if (item.kind != SettingKind.LIST) continue
                val p = items.first { Json.str(it["key"]) == item.key }
                val values = Json.arr(p["entry_values"]).map { Json.str(it) }
                assertEquals(
                    item.options.map { it.value }, values,
                    "list ${item.key} 的可选值与配置页不一致"
                )
            }

            // 按钮：宿主页里出现的动作必须都在 spec 里
            val jsonActions = items.mapNotNull { p ->
                if (Json.str(p["type"]) == "button") Json.str(p["on_click"])?.substringAfterLast('.') else null
            }
            val specActions = page.items.filter { it.kind == SettingKind.ACTION }.map { it.key }
            assertEquals(specActions, jsonActions, "「${page.title}」的按钮与 SettingsSpec 不一致")
        }
    }

    @Test
    fun everySettingKeyIsReadableAndWritable() {
        val base = RipSettings()
        for (key in SettingsSpec.settingKeys) {
            val item = SettingsSpec.item(key)
            assertNotNull(item, "缺少条目 $key")
            when (item.kind) {
                SettingKind.SWITCH -> {
                    val now = SettingsAccess.bool(base, key)
                    val flipped = SettingsAccess.withBool(base, key, !now)
                    assertEquals(!now, SettingsAccess.bool(flipped, key), "开关 $key 写入后读不到")
                }
                SettingKind.LIST -> {
                    val current = SettingsAccess.text(base, key)
                    assertTrue(
                        item.options.any { it.value == current },
                        "list $key 的默认值「$current」不在可选项里"
                    )
                    val other = item.options.last().value
                    val changed = SettingsAccess.withText(base, key, other)
                    assertEquals(other, SettingsAccess.text(changed, key), "list $key 写入后读不到")
                }
                else -> {
                    val marker = if (SettingsAccess.text(base, key).toIntOrNull() != null) "7" else "zz-$key"
                    val changed = SettingsAccess.withText(base, key, marker)
                    val readBack = SettingsAccess.text(changed, key)
                    if (SettingsAccess.text(base, key).toIntOrNull() != null) {
                        assertEquals("7", readBack, "数字项 $key 应解析为数字")
                    } else {
                        assertEquals(marker, readBack, "文本项 $key 写入后读不到")
                    }
                }
            }
        }
        assertEquals(47, SettingsSpec.settingKeys.size, "47 个设置键（0.39.0 移除自带编码器相关 4 项）")
        assertEquals(
            SettingsSpec.settingKeys.size,
            SettingsSpec.settingKeys.toSet().size,
            "设置键不能重复"
        )
    }

    @Test
    fun conditionalPagesHideWhatDoesNotApply() {
        val wav = RipSettings(format = "wav")
        val flac = RipSettings(format = "flac")

        val p4 = SettingsSpec.page("p4")
        fun visible(key: String, s: RipSettings) =
            p4.items.first { it.key == key }.visibleWhen(s)

        assertTrue(!visible("flacLevel", wav))
        assertTrue(visible("flacLevel", flac))

        val p5 = SettingsSpec.page("p5")
        fun p5Visible(key: String, s: RipSettings) =
            p5.items.first { it.key == key }.visibleWhen(s)
        assertTrue(!p5Visible("writeTags", wav), "WAV 不该出现「写入 FLAC 标签」")
        assertTrue(p5Visible("writeTags", flac))
        assertTrue(p5Visible("writeWavTags", wav))
        assertTrue(!p5Visible("annotateCueCrc", RipSettings(writeCue = false)))

        // 整盘镜像与 prepend/leaveout 间隙、部分音轨互斥：置灰而不是静默忽略
        val imageItem = p4.items.first { it.key == "imageMode" }
        assertTrue(imageItem.enabledWhen(RipSettings(gapMode = "append")))
        assertTrue(!imageItem.enabledWhen(RipSettings(gapMode = "prepend")))
        assertTrue(!imageItem.enabledWhen(RipSettings(trackSelection = "1-3")))
    }

    @Test
    fun actionsExistOnConfigActions() {
        for (name in SettingsSpec.actionKeys) {
            val method = runCatching { ConfigActions::class.java.getMethod(name) }.getOrNull()
            assertNotNull(method, "ConfigActions 缺少设置页按钮 $name")
            assertTrue(
                java.lang.reflect.Modifier.isStatic(method.modifiers),
                "$name 必须是 static"
            )
        }
        assertEquals(14, SettingsSpec.actionKeys.size, "全站 14 个按钮（1 个目录选择器 + 诊断页 13 个）")
    }
}
