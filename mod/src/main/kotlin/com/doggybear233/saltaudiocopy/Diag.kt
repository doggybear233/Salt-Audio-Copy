package com.doggybear233.saltaudiocopy

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 最小诊断日志：写 `<pluginPath>/logs/saltaudiocopy.log`，同时保留内存环形缓冲供面板展示。
 * 刻意不依赖任何第三方库（宿主只保证 API + stdlib + Compose 可用）。
 */
object Diag {

    private const val MAX_LINES = 500
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val ring = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()

    @Volatile
    private var logFile: Path? = null

    fun init(pluginPath: String) {
        runCatching {
            val dir = Path.of(pluginPath).resolve("logs")
            Files.createDirectories(dir)
            logFile = dir.resolve("saltaudiocopy.log")
        }.onFailure { System.err.println("[SaltAudioCopy] init log failed: $it") }
        log("---- session start ----")
    }

    fun log(message: String) {
        val line = "${LocalDateTime.now().format(fmt)}  $message"
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > MAX_LINES) ring.removeFirst()
        }
        println("[SaltAudioCopy] $message")
        val file = logFile ?: return
        runCatching {
            Files.writeString(
                file, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
        }
    }

    fun log(message: String, t: Throwable) {
        log("$message: ${t.javaClass.simpleName}: ${t.message}")
        runCatching {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            log(sw.toString())
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }

    fun logPath(): String = logFile?.toString() ?: "(未初始化)"

    /** 插件数据目录（`<pluginPath>`），缓存/诊断包都放这里，不污染用户音乐目录。 */
    fun dataDir(): Path? = logFile?.parent?.parent
}
