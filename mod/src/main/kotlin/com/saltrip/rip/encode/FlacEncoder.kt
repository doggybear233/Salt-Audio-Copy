package com.saltrip.rip.encode

import com.saltrip.rip.Diag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
    fun encode(exe: String, input: Path, output: Path, level: Int, timeoutSec: Long = 600): Long {
        require(Files.exists(input)) { "输入 WAV 不存在: $input" }
        Files.createDirectories(output.parent)
        val cmd = ArrayList<String>()
        cmd += exe
        cmd += buildArgs(input, output, level)
        Diag.log("flac: " + cmd.joinToString(" "))
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (e: java.io.IOException) {
            error("无法启动 FLAC 编码器 $exe：${e.message}（请在设置里填写 flac.exe 的绝对路径）")
        }
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        if (out.length < 4000) out.append(line).append('\n')
                        line = r.readLine()
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val ok = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!ok) {
            proc.destroyForcibly()
            runCatching { Files.deleteIfExists(output) }
            error("FLAC 编码超时（${timeoutSec}s）：$exe")
        }
        reader.join(2000)
        if (proc.exitValue() != 0) {
            runCatching { Files.deleteIfExists(output) }
            error("FLAC 编码失败（exit=${proc.exitValue()}）${out.toString().trim().take(400)}")
        }
        if (!Files.exists(output) || Files.size(output) == 0L) {
            runCatching { Files.deleteIfExists(output) }
            error("FLAC 编码器没有产出文件：$exe")
        }
        return Files.size(output)
    }

    /** 在 PATH 与给定绝对路径里找编码器，找到返回可用命令。 */
    fun locate(exe: String): String? {
        val direct = Path.of(exe)
        if (Files.isRegularFile(direct)) return direct.toAbsolutePath().toString()
        val exts = if (exe.lowercase().endsWith(".exe")) listOf("") else listOf(".exe", "")
        val pathVar = System.getenv("PATH") ?: return null
        for (dir in pathVar.split(';')) {
            if (dir.isBlank()) continue
            for (ext in exts) {
                val cand = Path.of(dir, exe + ext)
                if (Files.isRegularFile(cand)) return cand.toAbsolutePath().toString()
            }
        }
        return null
    }
}
