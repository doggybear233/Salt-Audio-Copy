package com.saltrip.rip

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/** 在字节数组里找子串；全部按 UTF-8 编码比较。 */
private fun ByteArray.countBytes(s: String): Int {
    val pattern = s.toByteArray(Charsets.UTF_8)
    if (pattern.isEmpty() || pattern.size > size) return 0
    var n = 0
    for (start in 0..size - pattern.size) {
        var hit = true
        for (j in pattern.indices) {
            if (this[start + j] != pattern[j]) { hit = false; break }
        }
        if (hit) n++
    }
    return n
}

/**
 * 宿主挂载点的离线校验：面板挂载依赖宿主的**混淆类名**，宿主升级就可能变。
 *
 * 直接读宿主的 `ffmpeg-x64.dll`（宿主把全部 class 以 zip 追加在文件尾部），检查目标类、
 * 方法描述符与调用点是否仍存在。宿主未安装时跳过，所以任何机器都能跑。
 *
 * 失败的含义：宿主版本变了，需要用 `verify_hook_target.py` 重新定位类名，
 * 而不是「插件坏了」。注意类文件内字符串是 modified UTF-8，这两处标识符都含非 ASCII 字符，
 * 必须按字节比对——decode 成 String 再 contains 会永远匹配不上（今天踩过）。
 */
class HostPanelTargetTest {

    private val descriptor = "(Landroidx/compose/ui/\u0998;Landroidx/compose/runtime/Composer;II)V"
    private val pageClass = "androidx/compose/ui/\u0769.class"
    private val methodName = "\u037f"
    private val caller = "com/xuncorp/voxzen/ui/navigation/ComposableSingletons\$AppNavigation_desktopKt.class"

    private fun hostFile(): Path? {
        val candidates = listOfNotNull(
            System.getenv("SALTRIP_HOST_DLL")?.takeIf { it.isNotBlank() }?.let { Path.of(it) },
            Path.of("D:\\Steam\\steamapps\\common\\Salt Player for Windows\\app\\ffmpeg-x64.dll")
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    @Test
    fun cdPageTargetStillResolvesInInstalledHost() {
        val host = hostFile()
        org.junit.jupiter.api.Assumptions.assumeTrue(host != null, "本机未安装宿主，跳过挂载点校验")

        ZipFile(host!!.toFile()).use { zip ->
            val page = zip.getEntry(pageClass)
            assertTrue(page != null, "宿主里找不到 CD 页类 $pageClass —— 宿主升级后类名变了")

            val pageBytes = zip.getInputStream(page).readBytes()
            assertTrue(
                pageBytes.countBytes(descriptor) >= 1,
                "CD 页类里找不到目标方法描述符 $descriptor —— 方法签名变了"
            )
            assertTrue(pageBytes.countBytes(methodName) >= 1, "CD 页类里找不到方法名 $methodName")

            val callerEntry = zip.getEntry(caller)
            assertTrue(callerEntry != null, "宿主里找不到导航调用点类 $caller")
            val callerBytes = zip.getInputStream(callerEntry).readBytes()
            // 常量池里方法名与描述符是两条独立的 UTF8 条目，不会相邻；
            // 这里退一步分别确认「调用点引用了这个类 + 该方法名 + 该描述符」。
            val pageType = pageClass.removeSuffix(".class")
            assertTrue(
                callerBytes.countBytes(pageType) >= 1,
                "调用点类里没有引用 $pageType —— 面板挂载会失败"
            )
            assertTrue(callerBytes.countBytes(methodName) >= 1, "调用点类里没有方法名 $methodName")
            assertTrue(
                callerBytes.countBytes(descriptor) >= 1,
                "调用点类里没有目标描述符 $descriptor"
            )
        }
    }

    /**
     * 页内区块（D′）能否挂上去，取决于能否从 CD 页类的常量池里解析出
     * 「页面 Column 内部调用的子组件」。解析不到就只能退回底部坞。
     */
    @Test
    fun inlineTargetIsParsedFromPageConstantPool() {
        val host = hostFile()
        org.junit.jupiter.api.Assumptions.assumeTrue(host != null, "本机未安装宿主，跳过")

        val resolved = ZipFile(host!!.toFile()).use { zip ->
            val entry = zip.getEntry(pageClass) ?: return@use emptyList()
            zip.getInputStream(entry).use { com.saltrip.rip.ui.HostTargets.parse(it) }
        }
        assertTrue(
            resolved.isNotEmpty(),
            "没能从 CD 页常量池里解析出页面内的子组件 —— 页内区块会挂不上，只能底部坞"
        )
        assertTrue(
            resolved.all { it.owner.startsWith("com/xuncorp/voxzen/ui/") },
            "解析出的目标应在宿主 UI 包里，实际：$resolved"
        )
    }
}
