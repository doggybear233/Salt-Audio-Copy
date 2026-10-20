package com.doggybear233.saltaudiocopy

import androidx.compose.ui.graphics.Color
import com.doggybear233.saltaudiocopy.ui.NeutralTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 面板自带的中性主题：不许有任何色相（就是不许再出现那层淡紫），
 * 并且正文/次要文字/按钮文字要够对比。
 */
class NeutralThemeTest {

    private val palettes = listOf("dark" to NeutralTheme.palette(true), "light" to NeutralTheme.palette(false))

    @Test
    fun noChannelHasHue() {
        for ((name, p) in palettes) {
            val colors = listOf(
                "surface" to p.surface,
                "surfaceAlt" to p.surfaceAlt,
                "text" to p.text,
                "subText" to p.subText,
                "stroke" to p.stroke,
                "buttonBg" to p.buttonBg,
                "buttonFg" to p.buttonFg
            )
            // error 例外：错误态本来就该是红色，不参与「无色相」判定
            for ((field, c) in colors) {
                val spread = NeutralTheme.channelSpread(c)
                assertTrue(spread <= 6, "$name.$field 有颜色（通道差 $spread）：$c")
            }
        }
    }

    @Test
    fun defaultMaterialPurpleIsGone() {
        // Material3 缺省浅色的 surfaceContainerHigh，之前面板渲染成这个色（淡紫）
        val m3Purple = Color(0xFFECE6F0)
        val light = NeutralTheme.palette(false)
        assertTrue(NeutralTheme.channelSpread(m3Purple) > 6, "对照值本身应当是有色相的")
        assertTrue(light.surface != m3Purple)
        assertTrue(NeutralTheme.channelSpread(light.surface) <= 6)
    }

    @Test
    fun textContrastIsReadable() {
        for ((name, p) in palettes) {
            assertTrue(NeutralTheme.contrast(p.text, p.surface) >= 7.0, "$name 正文对比度不足")
            assertTrue(NeutralTheme.contrast(p.subText, p.surface) >= 4.5, "$name 次要文字对比度不足")
            assertTrue(NeutralTheme.contrast(p.buttonFg, p.buttonBg) >= 4.5, "$name 按钮文字对比度不足")
            assertTrue(NeutralTheme.contrast(p.stroke, p.surface) >= 1.2, "$name 描边太淡看不见")
        }
    }

    @Test
    fun appearanceMapping() {
        assertEquals(false, NeutralTheme.resolveDark("light", systemDark = true))
        assertEquals(true, NeutralTheme.resolveDark("dark", systemDark = false))
        assertEquals(true, NeutralTheme.resolveDark("auto", systemDark = true))
        assertEquals(false, NeutralTheme.resolveDark("auto", systemDark = false))
        assertEquals(false, NeutralTheme.resolveDark("", systemDark = false))
        // null = 未知（读不到宿主外观），跟随系统
        assertEquals(true, NeutralTheme.resolveDark(null as String?, systemDark = true))
        assertEquals(false, NeutralTheme.resolveDark(null as String?, systemDark = false))
    }

    @Test
    fun hostHintNeverThrows() {
        // 宿主没加载时也必须是字符串，不能抛
        assertTrue(NeutralTheme.hostDarkHint().isNotEmpty())
    }
}
