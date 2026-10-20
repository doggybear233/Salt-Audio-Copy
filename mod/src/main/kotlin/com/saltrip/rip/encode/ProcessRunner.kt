package com.saltrip.rip.encode

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 一次外部进程的结果：退出码 + 合并后的输出（stdout + stderr，取前 4000 字符）。 */
data class ProcessResult(val exitCode: Int, val output: String)

/**
 * 跑外部命令的最小抽象。
 *
 * 存在的唯一理由是可测：生产走 [SystemProcessRunner]（真起进程），
 * 测试注入「把预置 FLAC 复制到输出路径」的假 runner——
 * 这样 FLAC / 外部编码器这条主路径不依赖开发机是否装了 `flac.exe`。
 *
 * 约定：启动失败抛 [IOException]，超时抛 [TimeoutException]；
 * 非零退出码由调用方判断（便于各自给出不同的报错文案）。
 */
fun interface ProcessRunner {
    @Throws(IOException::class, TimeoutException::class)
    fun run(cmd: List<String>, timeoutSec: Long): ProcessResult
}

/** 真实实现：ProcessBuilder 直起进程，不经 shell（中文/日文路径不会被代码页破坏）。 */
object SystemProcessRunner : ProcessRunner {

    override fun run(cmd: List<String>, timeoutSec: Long): ProcessResult {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val proc = pb.start()
        val buf = StringBuilder()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        if (buf.length < 4000) buf.append(line).append('\n')
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
            throw TimeoutException("外部程序 ${timeoutSec}s 未结束：${cmd.firstOrNull().orEmpty()}")
        }
        reader.join(2000)
        return ProcessResult(proc.exitValue(), buf.toString().trim())
    }
}
