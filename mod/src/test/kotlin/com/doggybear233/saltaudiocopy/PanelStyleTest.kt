package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelStyle
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 面板配色的可读性约束。
 *
 * 面板会出现在深色与浅色两种宿主主题里，而我看不到用户屏幕——所以用 WCAG 对比度把
 * 「字看不清」变成编译期之外可自动验证的断言：改颜色时如果可读性掉了，这里先红。
 *
 * 阈值取 WCAG 2.1 AA：正文 4.5:1，大字/次要信息 3:1，图标与强调色 3:1。
 */
class PanelStyleTest {

    private val palettes = listOf("dark" to PanelStyle.dark, "light" to PanelStyle.light)

    @Test
    fun titleIsHighContrastOnCardBackground() {
        for ((name, p) in palettes) {
            val r = PanelStyle.contrast(p.title, p.background)
            assertTrue(r >= 7.0, "$name 主题标题对比度只有 %.2f:1，至少要 7:1".format(r))
        }
    }

    @Test
    fun bodyAndFaintTextMeetAaLevel() {
        for ((name, p) in palettes) {
            val body = PanelStyle.contrast(p.body, p.background)
            assertTrue(body >= 4.5, "$name 主题正文对比度 %.2f:1 < 4.5:1".format(body))
            val faint = PanelStyle.contrast(p.faint, p.background)
            assertTrue(faint >= 3.0, "$name 主题最弱文字对比度 %.2f:1 < 3:1".format(faint))
        }
    }

    @Test
    fun accentIsVisibleAndButtonLabelIsReadableOnIt() {
        for ((name, p) in palettes) {
            val accent = PanelStyle.contrast(p.accent, p.background)
            assertTrue(accent >= 3.0, "$name 主题强调色在卡片上只有 %.2f:1".format(accent))
            val onAccent = PanelStyle.contrast(p.onAccent, p.accent)
            assertTrue(onAccent >= 4.5, "$name 主题主按钮文字在强调色上只有 %.2f:1".format(onAccent))
        }
    }

    @Test
    fun disabledAndBorderTokensStaySubtleButVisible() {
        for ((name, p) in palettes) {
            // 禁用态要「弱」，但仍需与背景可区分（否则按钮像是消失了）
            val disabled = PanelStyle.contrast(p.disabledFg, p.background)
            assertTrue(disabled >= 2.0, "$name 主题禁用文字对比度 %.2f:1 太低，看不清".format(disabled))
            assertTrue(disabled < 4.5, "$name 主题禁用文字对比度 %.2f:1 太亮，看不出禁用".format(disabled))
            // 分隔线/描边只需要「可感知」，不参与文字对比度
            val border = PanelStyle.contrast(p.border, p.background)
            assertTrue(border > 1.02, "$name 主题描边 %.3f:1 与背景几乎同色".format(border))
        }
    }

    @Test
    fun twoThemesAreActuallyDifferentAndOrdered() {
        val darkBg = PanelStyle.luminance(PanelStyle.dark.background)
        val lightBg = PanelStyle.luminance(PanelStyle.light.background)
        assertTrue(darkBg < 0.05, "深色主题背景亮度 %.3f 不够深".format(darkBg))
        assertTrue(lightBg > 0.8, "浅色主题背景亮度 %.3f 不够浅".format(lightBg))
        assertTrue(
            PanelStyle.palette(true) === PanelStyle.dark && PanelStyle.palette(false) === PanelStyle.light,
            "palette(dark) 必须返回对应调色板"
        )
    }
}
