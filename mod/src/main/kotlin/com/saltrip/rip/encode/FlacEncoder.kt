package com.saltrip.rip.encode

import com.saltrip.rip.Diag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException

/**
 * FLAC 编码器调用层。
 *
 * 优先用用户指定的 `flac.exe`（PATH / 绝对路径 / 常见安装目录），
 * 都没有时回落到**随插件内置**的 Xiph 1.4.3（见 [BundledFlac]）——用户不必自己装。
 *
 * 设计要点：
 * - 参数顺序固定为 `-f -s -<level> -o <out> <in>`，`-f` 覆盖、`-s` 静默；
 * - 走 ProcessBuilder 传参数数组，**不经 shell**，中文/日文路径不会被代码页破坏；
 * - 失败时删除半成品 .flac，保证目录里不会留下坏文件；
 * - 超时默认 10 分钟（一张 74 分钟盘在慢机器上也够）。
 */
object FlacEncoder {

    fun buildArgs(input: Path, output: Path, level: Int): List<String> = listOf(
        "-f", "-s", "-${level.coerceIn(0, 8)}", "-o", output.toString(), input.toString()
    )

    /** @return 生成的 FLAC 字节数 */
    fun encode(
        exe: String,
        input: Path,
        output: Path,
        level: Int,
        timeoutSec: Long = 600,
        runner: ProcessRunner = runnerOverride ?: SystemProcessRunner
    ): Long {
        require(Files.exists(input)) { "输入 WAV 不存在: $input" }
        Files.createDirectories(output.parent)
        val cmd = ArrayList<String>()
        cmd += exe
        cmd += buildArgs(input, output, level)
        Diag.log("flac: " + cmd.joinToString(" "))
        val res = try {
            runner.run(cmd, timeoutSec)
        } catch (e: TimeoutException) {
            runCatching { Files.deleteIfExists(output) }
            error("FLAC 编码超时（${timeoutSec}s）：$exe")
        } catch (e: java.io.IOException) {
            runCatching { Files.deleteIfExists(output) }
            error("无法启动 FLAC 编码器 $exe：${e.message}（把设置里的 flac.exe 留空可用内置编码器）")
        }
        if (res.exitCode != 0) {
            runCatching { Files.deleteIfExists(output) }
            error("FLAC 编码失败（exit=${res.exitCode}）${res.output.take(400)}")
        }
        if (!Files.exists(output) || Files.size(output) == 0L) {
            runCatching { Files.deleteIfExists(output) }
            error("FLAC 编码器没有产出文件：$exe")
        }
        return Files.size(output)
    }

    /**
     * 测试缝：非空时所有编码调用都走它（见 [ExternalEncoder.runnerOverride] 的说明）。
     */
    @Volatile
    internal var runnerOverride: ProcessRunner? = null

    /**
     * 找编码器：给定路径 → PATH → 常见安装目录 → 内置的 flac.exe。
     *
     * 内置兜底永远最后，用户自己的编码器（可能是更快的 1.5 或带自定义补丁）优先。
     */
    fun locate(exe: String): String? {
        locateExternal(exe)?.let { return it }
        return BundledFlac.ensure()?.toString()
    }

    /** 只找外部编码器；[locate] 里内置兜底之外的逻辑都在这里（便于单测旁路内置件）。 */
    fun locateExternal(exe: String): String? {
        val direct = Path.of(exe)
        if (Files.isRegularFile(direct)) return direct.toAbsolutePath().toString()
        val exts = if (exe.lowercase().endsWith(".exe")) listOf("") else listOf(".exe", "")
        val pathVar = System.getenv("PATH") ?: ""
        val dirs = pathVar.split(';') + COMMON_DIRS
        for (dir in dirs) {
            if (dir.isBlank()) continue
            for (ext in exts) {
                val cand = Path.of(dir, exe + ext)
                if (Files.isRegularFile(cand)) return cand.toAbsolutePath().toString()
            }
        }
        return null
    }

    /**
     * 常见安装位置：用户装了 FLAC 但没加 PATH 时也能用。
     * EAC 自带的编码器目录按 EAC 版本不同，这里只收「标准安装」的几种。
     */
    private val COMMON_DIRS = listOf(
        "C:\\Program Files\\flac",
        "C:\\Program Files (x86)\\flac",
        "C:\\Program Files\\FLAC",
        "C:\\Program Files (x86)\\FLAC",
        "C:\\flac"
    )
}
