package com.saltrip.rip.naming

import java.text.Normalizer

/**
 * 命名模板：`%变量%` 展开 + 文件系统安全化。
 *
 * 变量来源是 [TemplateVars]；未知变量保持原样（便于用户发现拼写错误，而不是静默消失）。
 * 路径分隔用 `/`，每个片段单独安全化（不会把用户写入的 `..` 变成目录穿越）。
 */
object TemplateEngine {

    private val VAR = Regex("%([a-zA-Z][a-zA-Z0-9_]*)%")

    /** EAC 常见变量名 + 本项目扩展。 */
    private val ALIASES = mapOf(
        "tracknumber" to "tracknr",
        "trackno" to "tracknr"
    )

    fun expand(template: String, vars: Map<String, String>): String =
        VAR.replace(template) { m ->
            val key = m.groupValues[1]
            vars[key] ?: ALIASES[key]?.let { vars[it] } ?: m.value
        }

    /**
     * 展开为路径片段列表。空片段与 `.`/`..` 会被丢弃，防止模板把文件写到目录外。
     * 片段数上限 16，避免异常模板造成深路径。
     */
    fun expandToSegments(template: String, vars: Map<String, String>): List<String> =
        expand(template, vars)
            .split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .take(16)
            .map { sanitizeSegment(it) }

    /**
     * 单个文件名/目录名安全化：
     * - 去掉 Windows 非法字符与控制字符
     * - 折叠空白、去尾部点与空格（资源管理器不允许）
     * - 保留设备名加前缀 `_`
     * - NFC 归一（macOS/网络盘更友好），按码点截断到 [maxCodepoints]
     */
    fun sanitizeSegment(raw: String, maxCodepoints: Int = 120): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        for (ch in nfc) {
            when {
                ch.code < 0x20 || ch.code == 0x7F -> continue
                ch in ILLEGAL -> sb.append('_')
                else -> sb.append(ch)
            }
        }
        var s = sb.toString().replace(Regex("[ ]{2,}"), " ").trim().trimEnd('.', ' ')
        if (s.isEmpty()) s = "_"
        if (s.substringBefore('.').uppercase() in RESERVED) s = "_$s"
        return truncateCodepoints(s, maxCodepoints)
    }

    fun truncateCodepoints(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max)
        return s.substring(0, end)
    }

    /** 完整路径长度保护（Windows MAX_PATH 留余量）。 */
    fun fitsPathLimit(path: String, limit: Int = 240): Boolean = path.length <= limit

    private val ILLEGAL = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
}

/** 模板变量容器：变量名全部小写、不带 `%`。 */
class TemplateVars(private val map: Map<String, String>) {
    fun toMap(): Map<String, String> = map

    companion object {
        fun of(vararg pairs: Pair<String, String>): TemplateVars = TemplateVars(mapOf(*pairs))
    }
}
