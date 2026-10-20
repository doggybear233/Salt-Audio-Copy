package com.doggybear233.saltaudiocopy

import java.nio.file.Files
import java.nio.file.Path

/**
 * 环境自检：语言、代码页、路径编码。
 *
 * 为什么需要它：插件的用户分布在中/日/韩/英各种 Windows 上，
 * 「文件名字是乱码」「标签是乱码」「路径写不进去」这类问题只看行为无法判断根因。
 * 这个对象把决定编码行为的几个开关全部摊开，并真的做一次中日韩路径写入测试。
 *
 * 只读 + 临时目录，不改任何用户文件。
 */
object EnvProbe {

    data class Report(
        val osName: String,
        val osVersion: String,
        val osLocale: String,
        val userLanguage: String,
        val userCountry: String,
        val ansiCharset: String,
        val fileEncoding: String,
        val jnuEncoding: String,
        val tempDir: String,
        val musicDir: String,
        val cjkPathRoundTrip: Boolean,
        val cjkFailure: String = ""
    )

    /** Windows 系统代码页对应到 JVM 字符集（WAV 的 INFO 标签按它编码）。 */
    fun collect(): Report {
        val music = runCatching { com.doggybear233.saltaudiocopy.scsi.Win32.musicFolder().toString() }.getOrDefault("")
        val trip = runCatching { cjkRoundTrip() }
        return Report(
            osName = System.getProperty("os.name").orEmpty(),
            osVersion = System.getProperty("os.version").orEmpty(),
            osLocale = runCatching { java.util.Locale.getDefault().toString() }.getOrDefault(""),
            userLanguage = System.getProperty("user.language").orEmpty(),
            userCountry = System.getProperty("user.country").orEmpty(),
            ansiCharset = runCatching { com.doggybear233.saltaudiocopy.encode.WavTags.ansiCharset().name() }.getOrDefault("?"),
            fileEncoding = System.getProperty("file.encoding").orEmpty(),
            jnuEncoding = System.getProperty("sun.jnu.encoding").orEmpty(),
            tempDir = System.getProperty("java.io.tmpdir").orEmpty(),
            musicDir = music,
            cjkPathRoundTrip = trip.getOrDefault(false),
            cjkFailure = trip.exceptionOrNull()?.let { it.message.orEmpty() }.orEmpty()
        )
    }

    /**
     * 在临时目录里建一个含中日韩文字的目录与文件名，写内容再读回来。
     *
     * 失败通常意味着 `sun.jnu.encoding` 不是 UTF-8：那种环境下中文/日文路径
     * 根本传不到文件系统层，只能靠这个自检暴露出来。
     */
    fun cjkRoundTrip(): Boolean {
        val base = Files.createTempDirectory("saltac-env-")
        return try {
            val dir = base.resolve("盐专辑-十明-変身のレシピ")
            Files.createDirectories(dir)
            val file = dir.resolve("01 灰かぶり - 사랑.wav")
            val payload = byteArrayOf(82, 73, 70, 70)
            Files.write(file, payload)
            val back = Files.readAllBytes(file)
            back.contentEquals(payload) && Files.isDirectory(dir)
        } finally {
            runCatching { deleteTree(base) }
        }
    }

    /** 面板/日志里给人看的一行结论。 */
    fun summary(r: Report): String {
        val head = "环境自检：${r.osLocale} · ANSI ${r.ansiCharset} · 路径编码 ${r.jnuEncoding}"
        return if (r.cjkPathRoundTrip) {
            "$head · 中日韩路径可写"
        } else {
            "$head · 中日韩路径写入失败（${r.cjkFailure.ifBlank { "未知原因" }}）"
        }
    }

    /** 完整报告，写日志用。 */
    fun details(r: Report): String = buildString {
        appendLine("os=${r.osName} ${r.osVersion} locale=${r.osLocale}")
        appendLine("user.language=${r.userLanguage} user.country=${r.userCountry}")
        appendLine("file.encoding=${r.fileEncoding} sun.jnu.encoding=${r.jnuEncoding}")
        appendLine("ansi charset（WAV INFO 用）=${r.ansiCharset}")
        appendLine("temp=${r.tempDir}")
        appendLine("music=${r.musicDir}")
        append("cjk path round trip=${r.cjkPathRoundTrip}")
        if (r.cjkFailure.isNotBlank()) append(" reason=${r.cjkFailure}")
    }

    private fun deleteTree(root: Path) {
        runCatching {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { p -> runCatching { Files.deleteIfExists(p) } }
        }
    }
}
