package com.saltrip.rip.ui

import com.saltrip.rip.meta.Json

/**
 * 选项文案的唯一来源：宿主配置页用的 `preference_config.json`。
 *
 * 面板和宿主页读同一份 `title` / `summary`，改 JSON 就同时改了两边；
 * 之前面板自己编文案（`SettingsSpec` 里留空，靠 `Strings["opt_xxx"]` 兜底），
 * 结果没翻译的项直接露出 `opt_gapMethod` 这种 key——这就是用户看到的乱码标题。
 */
internal object OptionText {

    private val labels = HashMap<String, String>()
    private val hints = HashMap<String, String>()

    init {
        runCatching { load() }
    }

    private fun load() {
        val text = OptionText::class.java.getResourceAsStream("/preference_config.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return
        val root = Json.parse(text)
        for (g in Json.arr(Json.path(root, "configs"))) {
            for (p in Json.arr(Json.path(g, "preferences"))) {
                val title = Json.str(Json.path(p, "title")) ?: continue
                val summary = Json.str(Json.path(p, "summary")).orEmpty()
                // 按钮只有 on_click，没有 key：用方法名当 key，面板的动作项按名字查。
                val id = Json.str(Json.path(p, "key"))
                    ?: Json.str(Json.path(p, "on_click"))?.substringAfterLast('.')?.removeSuffix("()")
                    ?: continue
                labels[id] = title
                if (summary.isNotBlank()) hints[id] = summary
            }
        }
    }

    fun label(key: String, fallback: String = ""): String =
        labels[key] ?: fallback.ifBlank { key }

    fun hint(key: String, fallback: String = ""): String = hints[key] ?: fallback
}
