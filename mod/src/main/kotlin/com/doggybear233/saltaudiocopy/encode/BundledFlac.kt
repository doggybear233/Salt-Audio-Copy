package com.doggybear233.saltaudiocopy.encode

import com.doggybear233.saltaudiocopy.Diag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 随插件分发的 FLAC 编码器（Xiph 官方 Windows 64-bit 构建，FLAC 1.4.3）。
 *
 * 为什么内置：用户机器上普遍没有 `flac.exe`，而「输出 FLAC」是最常用的功能之一；
 * 让用户自己去翻网页装编码器是最容易劝退的一步（用户反馈也是「能不能内置」）。
 * 放在 `native/win64` 资源里，第一次用时解压到插件数据目录的 `bin/`，
 * 之后直接复用。
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
        val dir = dirOverride
            ?: Diag.dataDir()?.resolve("bin")?.resolve("flac-1.4.3")
            ?: Path.of(System.getProperty("java.io.tmpdir"), "saltac-flac-1.4.3")
        // 每个文件应有的字节数：解压后要逐个核对，否则「写失败但旧文件还在」
        // 会让 ensure() 把大小不对的 exe 交出去，用户看到的是 0xC0000135。
        val expectedSizes = HashMap<String, Long>()
        runCatching {
            Files.createDirectories(dir)
            for (name in FILES) {
                val target = dir.resolve(name)
                val url = BundledFlac::class.java.getResource("$RESOURCE_DIR/$name") ?: return@runCatching
                // 「文件存在」不等于「文件完整」：上次解压到一半被杀、磁盘写失败、
                // 或用户/杀毒软件动了 bin 目录，都会留下一个大小不对的文件，
                // 而 flac.exe 与 DLL 版本不匹配时会以 0xC0000135 崩掉，很难查。
                // 这里按资源本身的字节数校验，大小不符就重新解压。
                val expected = url.openStream().use { it.readBytes() }
                expectedSizes[name] = expected.size.toLong()
                if (Files.isRegularFile(target) && Files.size(target) == expected.size.toLong()) continue
                val tmp = dir.resolve("$name.tmp")
                Files.write(tmp, expected)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                Diag.log("bundled flac: extracted $name (${Files.size(target)} B)")
            }
        }.onFailure { Diag.log("bundled flac extract failed", it) }

        val exe = dir.resolve(EXE)
        if (!Files.isRegularFile(exe) || Files.size(exe) <= 0) return null
        // 解压失败时旧文件可能还在：大小与资源不一致就不能用，否则错误会推迟到运行期
        if (expectedSizes.isNotEmpty()) {
            val bad = expectedSizes.filter { (name, size) ->
                val f = dir.resolve(name)
                !Files.isRegularFile(f) || Files.size(f) != size
            }
            if (bad.isNotEmpty()) {
                Diag.log("bundled flac: 解压结果不完整，拒绝使用 $exe（$bad）")
                return null
            }
        }
        resolved = exe.toAbsolutePath()
        return resolved
    }

    /** 测试用：清掉缓存，让下一次 [ensure] 重新解压。 */
    internal fun resetForTest() {
        resolved = null
    }
}
