package com.saltrip.rip.ui

import androidx.compose.ui.graphics.Color

/**
 * 面板的视觉令牌与配色。
 *
 * 为什么单独抽出来：
 * - 面板是「宿主里的插件」，不能假设宿主一定是深色主题。之前把中性深色写死，
 *   在浅色主题的宿主里会变成一块突兀的黑卡片 —— 与「和原软件风格统一」相悖。
 *   这里按 `isSystemInDarkTheme()` 出两套调色板，其余排版逻辑共用。
 * - 配色不靠肉眼判断：每个前景/背景组合都有 WCAG 对比度断言（见 `PanelStyleTest`），
 *   改颜色时对比度不够会直接红，不会等用户在真机上发现「字看不清」。
 * - 只用系统的深/浅判定，不读宿主私有主题（`com/moriafly/salt/ui` 下的类被字形混淆，
 *   跨版本会碎），保持零耦合。
 *
 * 设计基调：中性灰底 + 单一蓝色强调（accent），层次靠 1px 描边与透明度，而不是多层阴影；
 * 圆角统一 14dp（卡片）/ 999dp（胶囊），间距 4 的倍数。
 */
object PanelStyle {

    /** 4dp 栅格。 */
    const val GAP_XS = 4
    const val GAP_S = 8
    const val GAP_M = 12
    const val GAP_L = 16

    data class Palette(
        /** 卡片背景（深色下带一点透明，让宿主底色透出来，融合感更好）。 */
        val background: Color,
        val border: Color,
        val divider: Color,
        /** 标题与主要信息。 */
        val title: Color,
        /** 次要信息（阶段文案、进度行）。 */
        val body: Color,
        /** 最弱的信息（副标题、耗时）。 */
        val faint: Color,
        /** 唯一强调色。 */
        val accent: Color,
        /** 强调色的低透明度底（徽标、进度槽）。 */
        val accentSoft: Color,
        /** 强调色实底上的文字色。 */
        val onAccent: Color,
        /** 次级按钮的底与描边。 */
        val ghost: Color,
        val ghostBorder: Color,
        val disabledBg: Color,
        val disabledFg: Color
    )

    /** 深色主题：贴近宿主深色界面的中性灰，强调色偏亮以保证在灰底上的可读性。 */
    val dark = Palette(
        background = Color(0xF21B1D21),
        border = Color(0x1FFFFFFF),
        divider = Color(0x1FFFFFFF),
        title = Color(0xFFF2F3F5),
        body = Color(0xFFC3C6CC),
        faint = Color(0xFF9AA0A8),
        accent = Color(0xFF7CB0FF),
        accentSoft = Color(0x2E7CB0FF),
        onAccent = Color(0xFF0B1220),
        ghost = Color(0x14FFFFFF),
        ghostBorder = Color(0x24FFFFFF),
        disabledBg = Color(0x12FFFFFF),
        disabledFg = Color(0xFF6E747C)
    )

    /** 浅色主题：白卡片 + 深灰文字，强调色加深（浅底上亮蓝对比度不够）。 */
    val light = Palette(
        background = Color(0xF2FFFFFF),
        border = Color(0x14000000),
        divider = Color(0x14000000),
        title = Color(0xFF14161A),
        body = Color(0xFF3E444C),
        faint = Color(0xFF666D76),
        accent = Color(0xFF1D5FD0),
        accentSoft = Color(0x1F1D5FD0),
        onAccent = Color(0xFFFFFFFF),
        ghost = Color(0x0A000000),
        ghostBorder = Color(0x1A000000),
        disabledBg = Color(0x0A000000),
        disabledFg = Color(0xFF9AA0A8)
    )

    fun palette(isDark: Boolean): Palette = if (isDark) dark else light

    /**
     * WCAG 2.1 相对亮度。Compose 的 [Color] 分量已是 0..1 的 sRGB 值。
     */
    fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    /** 对比度（1..21）。忽略 alpha，按不透明计算——卡片底本身是半透明叠在宿主底色上，取保守值。 */
    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
