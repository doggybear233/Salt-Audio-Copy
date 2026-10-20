package com.saltrip.rip.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 面板自带的中性主题。
 *
 * 为什么不用宿主配色：宿主用的是 `com.moriafly.salt.ui` 的 SaltTheme，没有给插件组合提供
 * `MaterialTheme`；而且宿主里 salt-ui 的类除 MaterialType 外全部被混淆改名，
 * `SaltTheme` / `SaltColors` 在运行时根本不存在这些类名。于是 `MaterialTheme.colorScheme`
 * 落到 Material3 缺省方案（就是那一层淡紫 `#ECE6F0`）。
 *
 * 这里只做黑白灰：任何颜色的 RGB 通道差都不超过 4，不会出现紫/蓝/绿色相。
 */
object NeutralTheme {

    /** 上次写日志的深浅值；只在变化时写，避免每次合成刷日志。 */
    @Volatile
    internal var lastLogged: Boolean? = null

    data class Neutral(
        val dark: Boolean,
        val surface: Color,
        val surfaceAlt: Color,
        val text: Color,
        val subText: Color,
        val stroke: Color,
        val buttonBg: Color,
        val buttonFg: Color,
        val track: Color,
        val error: Color
    )

    fun palette(dark: Boolean): Neutral = if (dark) {
        Neutral(
            dark = true,
            surface = Color(0xFF1B1B1E),
            surfaceAlt = Color(0xFF27272B),
            text = Color(0xFFF0F0F2),
            subText = Color(0xFFA2A2A8),
            stroke = Color(0xFF3C3C42),
            buttonBg = Color(0xFFF0F0F2),
            buttonFg = Color(0xFF1B1B1E),
            track = Color(0x29F0F0F2),
            error = Color(0xFFF2B8B5)
        )
    } else {
        Neutral(
            dark = false,
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFF4F4F5),
            text = Color(0xFF16161A),
            subText = Color(0xFF6B6B6F),
            stroke = Color(0xFFDCDCE0),
            buttonBg = Color(0xFF16161A),
            buttonFg = Color(0xFFFFFFFF),
            track = Color(0x2416161A),
            error = Color(0xFFB3261E)
        )
    }

    /** 显式外观（light/dark）→ 是否深色；null / 其他值跟随给定兜底。 */
    fun resolveDark(appearance: String?, systemDark: Boolean): Boolean =
        when (appearance?.lowercase()) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }

    /**
     * 宿主当前是不是深色。
     *
     * 读 `AppConfig.INSTANCE.lightDarkTheme`：枚举字段名被混淆，但枚举常量名（Enum.name）没被改，
     * 仍是 `Dark` / `Light` / `FollowSystem`，据此判定；`FollowSystem` 或读不到返回 null（调用方跟系统）。
     */
    fun hostDark(): Boolean? = runCatching {
        val cls = Class.forName("com.xuncorp.voxzen.util.AppConfig")
        val inst = cls.getDeclaredField("INSTANCE").get(null)
        val theme = cls.getMethod("getLightDarkTheme").invoke(inst) ?: return@runCatching null
        // 注意：Kotlin 的 Enum.name 指向私有字段，这里走 getMethod("name") 拿字符串
        val name = theme.javaClass.getMethod("name").invoke(theme) as String
        when (name) {
            "Dark" -> true
            "Light" -> false
            else -> null
        }
    }.getOrNull()

    /**
     * 仅用于日志：把宿主外观的原始值写进日志（分辨不了时也能看到输出了什么）。
     */
    fun hostDarkHint(): String = runCatching {
        val cls = Class.forName("com.xuncorp.voxzen.util.AppConfig")
        val inst = cls.getDeclaredField("INSTANCE").get(null)
        val theme = cls.getMethod("getLightDarkTheme").invoke(inst)
        "${theme?.javaClass?.simpleName}:$theme"
    }.getOrElse { "unknown" }

    fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** 通道最大差；中性灰应 <= 4，有颜色就会更大。 */
    fun channelSpread(c: Color): Int {
        val r = (c.red * 255f).toInt()
        val g = (c.green * 255f).toInt()
        val b = (c.blue * 255f).toInt()
        return maxOf(r, g, b) - minOf(r, g, b)
    }
}

/**
 * 把面板包进中性主题。
 *
 * 深浅来源：显式 [appearance]（测试/调试用）→ 宿主 [NeutralTheme.hostDark] → 操作系统。
 *
 * 不做轮询：每次合成（打开 CD 工具箱、面板状态变化、回到该页面）重新读一次宿主外观。
 * 宿主的 `AppConfig.lightDarkTheme` 不是 Compose 快照状态，读它不会订阅，
 * 所以「刚切换完还停在旧值」只能靠下一次合成刷新——这也正是想要的行为。
 */
@Composable
fun SaltRipTheme(appearance: String? = null, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val host = if (appearance == null) NeutralTheme.hostDark() else null
    val dark = NeutralTheme.resolveDark(appearance, host ?: systemDark)
    if (NeutralTheme.lastLogged != dark) {
        NeutralTheme.lastLogged = dark
        com.saltrip.rip.Diag.log("theme composed: dark=$dark (host=${NeutralTheme.hostDarkHint()})")
    }
    val n = NeutralTheme.palette(dark)

    val scheme = if (dark) {
        darkColorScheme(
            primary = n.buttonBg,
            onPrimary = n.buttonFg,
            background = n.surface,
            onBackground = n.text,
            surface = n.surface,
            onSurface = n.text,
            surfaceVariant = n.surfaceAlt,
            onSurfaceVariant = n.subText,
            outline = n.stroke,
            outlineVariant = n.stroke,
            error = n.error,
            onError = n.surface
        )
    } else {
        lightColorScheme(
            primary = n.buttonBg,
            onPrimary = n.buttonFg,
            background = n.surface,
            onBackground = n.text,
            surface = n.surface,
            onSurface = n.text,
            surfaceVariant = n.surfaceAlt,
            onSurfaceVariant = n.subText,
            outline = n.stroke,
            outlineVariant = n.stroke,
            error = n.error,
            onError = n.surface
        )
    }

    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
