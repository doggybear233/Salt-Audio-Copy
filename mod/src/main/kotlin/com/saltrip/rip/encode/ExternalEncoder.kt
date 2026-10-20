package com.saltrip.rip.encode

import com.saltrip.rip.Diag
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 通用外部编码器（EAC 的 "External compressor" 思路）。
 *
 * EAC 不内置所有编码器，而是让用户填「可执行文件 + 参数模板」，用 `%s` 之类的占位符传文件名。
 * 这里用更可读的 `%in%` / `%out%` / `%tracknr2%` 等占位符，模板本身与命名模板同一套变量。
 */
object ExternalEncoder {

    /** 默认模板：flac 的命令行（`%out%` 决定扩展名之外的语义）。 */
    const val DEFAULT_ARGS = "-f -s -5 -o \"%out%\" \"%in%\""

    /**
     * 按 shell 习惯切分参数模板：双引号内允许空格，`""` 表示一个字面引号。
     * 不做变量替换——替换在 [plan] 里先做，再切分，这样文件名里的空格不会把参数切开。
     */
    fun splitArgs(template: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        var hasToken = false
        while (i < template.length) {
            val c = template[i]
            when {
                c == '"' -> {
                    if (inQuote && i + 1 < template.length && template[i + 1] == '"') {
                        sb.append('"'); i++
                    } else {
                        inQuote = !inQuote
                        hasToken = true
                    }
                }

                c.isWhitespace() && !inQuote -> {
                    if (hasToken || sb.isNotEmpty()) {
                        out += sb.toString(); sb.setLength(0); hasToken = false
                    }
                }

                else -> {
                    sb.append(c); hasToken = true
                }
            }
            i++
        }
        if (hasToken || sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** 展开占位符并切分成 argv。 */
    fun plan(template: String, vars: Map<String, String>): List<String> {
        var expanded = template
        // 长的先替换，避免 %tracknr2% 被 %tracknr% 咬掉。
        for (key in vars.keys.sortedByDescending { it.length }) {
            expanded = expanded.replace("%$key%", vars.getValue(key))
        }
        return splitArgs(expanded)
    }

    /** 找可执行文件：先按给出的绝对/相对路径，再按 PATH。 */
    fun locate(exe: String): String? {
        if (exe.isBlank()) return null
        val direct = File(exe)
        if (direct.isFile) return direct.absolutePath
        val path = System.getenv("PATH").orEmpty()
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (name in listOf(exe, "$exe.exe")) {
                val f = File(dir, name)
                if (f.isFile) return f.absolutePath
            }
        }
        return null
    }

    /**
     * 跑编码器并返回产物字节数。失败时删除半成品并抛出——半截文件比没有文件更坑人。
     */
    fun encode(exe: String, argv: List<String>, outFile: Path, timeoutSec: Long = 1800): Long {
        Files.createDirectories(outFile.parent)
        Files.deleteIfExists(outFile)
        val cmd = ArrayList<String>(argv.size + 1)
        cmd += exe
        cmd += argv
        Diag.log("external encode: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            throw IllegalStateException("无法启动编码器 $exe：${e.message}", e)
        }
        val buf = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (buf.length < 4000) buf.appendLine(line)
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val ok = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!ok) {
            p.destroyForcibly()
            Files.deleteIfExists(outFile)
            throw IllegalStateException("编码器超时（${timeoutSec}s）")
        }
        reader.join(2000)
        if (p.exitValue() != 0) {
            Files.deleteIfExists(outFile)
            throw IllegalStateException("编码器退出码 ${p.exitValue()}：${buf.toString().trim().take(400)}")
        }
        if (!Files.exists(outFile) || Files.size(outFile) <= 0L) {
            throw IllegalStateException("编码器报告成功但没有产出文件（检查参数模板里的 %out%）")
        }
        return Files.size(outFile)
    }
}
