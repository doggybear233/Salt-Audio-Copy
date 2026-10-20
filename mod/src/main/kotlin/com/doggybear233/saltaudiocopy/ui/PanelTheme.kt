package com.doggybear233.saltaudiocopy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.doggybear233.saltaudiocopy.Diag

/**
 * 面板配色：**优先跟随宿主**，做不到才用自带的深/浅两套。
 *
 * 背景：0.22.x 用 `isSystemInDarkTheme()`，它读的是操作系统主题，而宿主有自己的
 * 「浅色/深色/跟随系统」开关，于是用户在宿主要求深色时面板仍是白的（反馈 2）。
 * 宿主基于 material3，`MaterialTheme.colorScheme` 在其组合树里是真值；
 * 如果宿主没提供（拿到的是 material3 默认的浅色 baseline `#FEF7FF` 一类），
 * 就退回 [PanelStyle] 的调色板。
 *
 * 毛玻璃：宿主的模糊是窗口级效果（salt-ui 的 `*WindowProc`），插件调不到。
 * 但宿主开着模糊时，把面板背景做成半透明就能透出背后的模糊窗口 → 视觉上接近。
 */
object PanelTheme {

    /** material3 默认浅色背景，用来判定「宿主没提供配色」。 */
    private val M3_DEFAULT_LIGHT = Color(0xFFFFFBFE)
    private val M3_DEFAULT_BG = Color(0xFFFEF7FF)

    data class Resolved(val palette: PanelStyle.Palette, val fromHost: Boolean, val dark: Boolean)

    @Composable
    fun resolve(): Resolved {
        // 注意：composable 调用不能包在 runCatching 里（编译器直接报错），
        // 所以这里直接读；宿主没有 MaterialTheme 时拿到的是 material3 默认色。
        val scheme = MaterialTheme.colorScheme
        val bg = scheme.background
        val hostProvided = bg != M3_DEFAULT_BG && bg != M3_DEFAULT_LIGHT
        val dark = PanelStyle.luminance(bg) < 0.45

        if (!hostProvided) {
            return Resolved(PanelStyle.palette(dark), fromHost = false, dark = dark)
        }

        val surface = scheme.surface
        val onSurface = scheme.onSurface
        val primary = scheme.primary
        val onPrimary = scheme.onPrimary
        val outline = scheme.outline
        val muted = blend(onSurface, bg, 0.62f)

        val p = PanelStyle.Palette(
            background = surface,
            border = outline.copy(alpha = 0.55f),
            divider = blend(outline, surface, 0.5f).copy(alpha = 0.6f),
            title = onSurface,
            body = blend(onSurface, surface, 0.88f),
            faint = muted,
            accent = primary,
            accentSoft = primary.copy(alpha = if (dark) 0.22f else 0.14f),
            onAccent = onPrimary,
            ghost = if (dark) blend(surface, Color.White, 0.10f) else blend(surface, Color.Black, 0.05f),
            ghostBorder = outline.copy(alpha = 0.45f),
            disabledBg = if (dark) blend(surface, Color.White, 0.06f) else blend(surface, Color.Black, 0.03f),
            disabledFg = muted.copy(alpha = 0.55f)
        )
        return Resolved(p, fromHost = true, dark = dark)
    }

    /**
     * 宿主开启模糊时把背景改成半透明。alpha 由 [transparent] 控制，
     * 具体数值在 [glassAlpha] 里统一，避免各处各写一个。
     */
    fun applyGlass(p: PanelStyle.Palette, dark: Boolean, enabled: Boolean): PanelStyle.Palette {
        if (!enabled) return p
        val a = glassAlpha(dark)
        return p.copy(
            background = p.background.copy(alpha = a),
            ghost = p.ghost.copy(alpha = (a + 0.10f).coerceAtMost(1f)),
            disabledBg = p.disabledBg.copy(alpha = a)
        )
    }

    fun glassAlpha(dark: Boolean): Float = if (dark) 0.78f else 0.86f

    /** 线性混色：把 [a] 往 [b] 拉 [t]（0 = 全 a，1 = 全 b）。 */
    fun blend(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * k,
            green = a.green + (b.green - a.green) * k,
            blue = a.blue + (b.blue - a.blue) * k,
            alpha = a.alpha + (b.alpha - a.alpha) * k
        )
    }

    /** 宿主是否开了「模糊/多层模糊」；反射读，读不到就当没开。 */
    fun hostBlurEnabled(): Boolean = runCatching {
        val cls = Class.forName("com.xuncorp.voxzen.util.AppConfig")
        val inst = cls.getField("INSTANCE").get(null)
        val m = cls.getMethod("getMultilayerBlurEffect")
        m.invoke(inst) as? Boolean ?: false
    }.onFailure { Diag.log("read host blur flag failed", it) }.getOrDefault(false)
}
