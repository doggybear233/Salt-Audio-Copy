package com.saltrip.rip.ui

import com.saltrip.rip.meta.Json

/**
 * 选项文案的唯一来源：宿主配置页用的 `preference_config.json`。
 *
 * 面板和宿主页读同一份 `title` / `summary`，改 JSON 就同时改了两边；
 * 之前面板自己编文案（`SettingsSpec` 里留空，靠 `Strings["opt_xxx"]` 兜底），
 * 结果没翻译的项直接露出 `opt_gapMethod` 这种 key——这就是用户看到的乱码标题。
 *
 * 宿主配置页是静态 JSON、宿主也不会替我们翻译，所以英文/日文只用覆盖文件
 * `/options-i18n.json` 作用在**面板**上（用户 A3 反馈：面板里的设置必须跟着语言走）。
 */
internal object OptionText {

    private val labels = HashMap<String, String>()
    private val hints = HashMap<String, String>()

    /** lang → key → (label, hint) */
    private val overlay = HashMap<String, HashMap<String, Pair<String, String>>>()

    init {
        runCatching { load() }
        runCatching { loadOverlay() }
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

    private fun loadOverlay() {
        val text = OptionText::class.java.getResourceAsStream("/options-i18n.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return
        val root = Json.parse(text)
        for (lang in listOf("en", "ja")) {
            val table = Json.obj(Json.path(root, lang))
            if (table.isEmpty()) continue
            val m = HashMap<String, Pair<String, String>>()
            for ((key, value) in table) {
                val pair = Json.arr(value)
                if (pair.size < 2) continue
                val label = Json.str(pair[0]) ?: continue
                m[key] = label to Json.str(pair[1]).orEmpty()
            }
            overlay[lang] = m
        }
    }

    private fun overlayFor(key: String): Pair<String, String>? =
        overlay[Strings.lang().name.lowercase()]?.get(key)

    fun label(key: String, fallback: String = ""): String =
        overlayFor(key)?.first?.takeIf { it.isNotBlank() }
            ?: labels[key]
            ?: fallback.ifBlank { key }

    fun hint(key: String, fallback: String = ""): String {
        val over = overlayFor(key)?.second
        if (!over.isNullOrBlank()) return over
        return hints[key] ?: fallback
    }

    /** 仅测试用：覆盖文件里有多少条、是否覆盖到某个 key。 */
    internal fun overlayKeys(lang: String): Set<String> = overlay[lang]?.keys ?: emptySet()
}
