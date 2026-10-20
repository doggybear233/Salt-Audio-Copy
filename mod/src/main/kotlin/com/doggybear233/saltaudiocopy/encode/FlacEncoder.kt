package com.doggybear233.saltaudiocopy.encode

import com.doggybear233.saltaudiocopy.Diag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException

/**
 * FLAC 编码器调用层。
 *
 * 只用**随插件内置**的 Xiph 1.4.3（见 [BundledFlac]）——用户不必自己装、也不必填路径。
 * 之所以不再支持外部 flac.exe：内置件与测试夹具一致，用户少一个会填错的选项。
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
            error("无法启动内置 FLAC 编码器 $exe：${e.message}（插件包可能损坏，重新安装一次）")
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

    /** 测试缝：非空时所有编码调用都走它。 */
    @Volatile
    internal var runnerOverride: ProcessRunner? = null

    /**
     * 编码器位置：解出内置 flac.exe，返回其绝对路径。
     *
     * 解包目录是 `%APPDATA%\…\plugins\<插件目录>\bin\flac-1.4.3`，第二次调用直接复用。
     */
    fun locate(): String? = BundledFlac.ensure()?.toString()
}
