package com.saltrip.rip.encode

import com.saltrip.rip.Diag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException

/**
 * 外部 FLAC 编码器（与 EAC 同策略：不内嵌编码库，调用 flac.exe）。
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
            error("无法启动 FLAC 编码器 $exe：${e.message}（请在设置里填写 flac.exe 的绝对路径）")
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

    /** 在 PATH、给定绝对路径与常见安装位置里找编码器，找到返回可用命令。 */
    fun locate(exe: String): String? {
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
