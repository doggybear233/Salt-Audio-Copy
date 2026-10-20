package com.saltrip.rip.encode

import com.saltrip.rip.Diag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 随插件分发的 FLAC 编码器（Xiph 官方 Windows 64-bit 构建，FLAC 1.4.3）。
 *
 * 为什么内置：用户机器上普遍没有 `flac.exe`，而「输出 FLAC」是最常用的功能之一；
 * 让用户自己去翻网页装编码器是最容易劝退的一步（用户反馈也是「能不能内置」）。
 * 放在 `native/win64` 资源里，第一次用时解压到插件数据目录的 `bin/`，
 * 之后直接复用；用户填了自己的 `flac.exe` 则优先用用户的。
 *
 * 许可：FLAC 是 Xiph 的 BSD 许可，允许再分发；`FLAC-COPYING.txt` 随资源一起带上。
 */
internal object BundledFlac {

    private const val RESOURCE_DIR = "/native/win64"

    /** flac.exe 依赖这两个 DLL，缺一个就 0xC0000135（DLL not found）。 */
    private val FILES = listOf("flac.exe", "libFLAC.dll", "libFLAC++.dll")

    private const val EXE = "flac.exe"

    @Volatile
    private var resolved: Path? = null

    /** 解压后的目录；测试里可指向临时目录。 */
    @Volatile
    internal var dirOverride: Path? = null

    /**
     * 确保资源已解压，返回可执行的 `flac.exe` 绝对路径；没有内置资源（比如非 Windows 构建）返回 null。
     */
    fun ensure(): Path? {
        resolved?.let { if (Files.isRegularFile(it)) return it }
        val dir = (dirOverride ?: Diag.dataDir()?.resolve("bin")?.resolve("flac-1.4.3")) ?: return null
        runCatching {
            Files.createDirectories(dir)
            for (name in FILES) {
                val target = dir.resolve(name)
                val url = BundledFlac::class.java.getResource("$RESOURCE_DIR/$name") ?: return@runCatching
                // 已经解压过就跳过：每次都覆盖会让插件升级后多写 1 MB
                if (Files.isRegularFile(target) && Files.size(target) > 0) continue
                url.openStream().use { input ->
                    val tmp = dir.resolve("$name.tmp")
                    Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
                Diag.log("bundled flac: extracted $name (${Files.size(target)} B)")
            }
        }.onFailure { Diag.log("bundled flac extract failed", it) }

        val exe = dir.resolve(EXE)
        if (!Files.isRegularFile(exe)) return null
        resolved = exe.toAbsolutePath()
        return resolved
    }

    /** 测试用：清掉缓存，让下一次 [ensure] 重新解压。 */
    internal fun resetForTest() {
        resolved = null
    }
}
