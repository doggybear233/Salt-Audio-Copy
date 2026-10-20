package com.saltrip.rip.album

import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.scsi.DiscToc
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** 单轨抓取结果（含 CUE 需要的起始位置与 CRC）。 */
data class AlbumTrackResult(
    val number: Int,
    val title: String,
    val artist: String?,
    val file: Path,
    val startLba: Int,
    val sectors: Int,
    val crc32: Long,
    val errors: Int,
    val elapsedMs: Long,
    val skipped: Boolean = false,
    val isrc: String? = null,
    /** INDEX 00 前间隙扇区数（已计入 sectors 时用于写 INDEX 01 偏移）。 */
    val gapSectors: Int = 0,
    /** 峰值电平 0.0–1.0。 */
    val peakLevel: Double = 0.0,
    /** Test&Copy 时测试通道的 CRC32；只抓一次时为 null。 */
    val testCrc32: Long? = null,
    /** 因校验不一致而重读过的扇区区间 */
    val retried: List<IntRange> = emptyList()
) {
    /**
     * 轨道质量（EAC 风格百分数）：按「重试次数 / 扇区数」估算一次通过率。
     *
     * EAC 的 quality 是「一次读对的扇区占比」；我们只在块级别知道重试，
     * 所以这里是最坏情况估计（每次都当作整块坏），数字只会比 EAC 更悲观。
     */
    val qualityPct: Double
        get() = if (sectors <= 0) 100.0 else (100.0 * (1.0 - errors.toDouble() / sectors)).coerceIn(0.0, 100.0)

    val lengthMs: Long get() = sectors * 1000L / 75
}

data class AlbumRipResult(
    val outDir: Path,
    val album: AlbumMeta?,
    val tracks: List<AlbumTrackResult>,
    val elapsedMs: Long,
    val cancelled: Boolean,
    val cueFile: Path?,
    val logFile: Path?,
    val coverFile: Path?,
    val notes: List<String>,
    /** 本次运行**新建**的文件（取消时只删这些，不碰用户原有的文件）。 */
    val createdFiles: List<Path> = emptyList()
)

/**
 * CUE 写出器。EAC 的「Multiple WAV Files」风格：每轨一个 `FILE`，`INDEX 01 00:00:00`。
 * 编码固定 **UTF-8 with BOM + CRLF**：没有 BOM 时不少播放器会按本地代码页解释，CJK 曲名会乱码。
 */
object CueWriter {

    private const val BOM = "\uFEFF"

    fun build(
        album: AlbumMeta?,
        tracks: List<AlbumTrackResult>,
        relativeNames: List<String>,
        comment: String = "Salt Audio Copy",
        /** CD-TEXT 的 UPC/EAN（MCN）；有就写 CUE 的 CATALOG 行 */
        catalog: String? = null,
        /** 轨号 → 词曲作者；CD-TEXT 0x82 有就写 SONGWRITER 行 */
        songwriters: Map<Int, String> = emptyMap()
    ): String {
        val sb = StringBuilder()
        sb.append(BOM)
        fun line(s: String = "") { sb.append(s).append("\r\n") }
        album?.genre?.let { line("REM GENRE $it") }
        album?.year?.let { line("REM DATE $it") }
        album?.discId?.takeIf { it.isNotBlank() }?.let { line("REM DISCID $it") }
        album?.cddbId?.takeIf { it.isNotBlank() }?.let { line("REM CDDBID $it") }
        catalog?.takeIf { it.isNotBlank() }?.let { line("CATALOG $it") }
        line("REM COMMENT \"$comment\"")
        album?.albumArtist?.let { line("PERFORMER \"${q(it)}\"") }
        album?.albumTitle?.let { line("TITLE \"${q(it)}\"") }
        line()
        tracks.forEachIndexed { i, t ->
            line("FILE \"${q(relativeNames.getOrElse(i) { t.file.fileName.toString() })}\" WAVE")
            line("  TRACK %02d AUDIO".format(t.number))
            line("    TITLE \"${q(t.title)}\"")
            t.artist?.takeIf { it.isNotBlank() }?.let { line("    PERFORMER \"${q(it)}\"") }
            t.isrc?.takeIf { it.isNotBlank() }?.let { line("    ISRC $it") }
            songwriters[t.number]?.takeIf { it.isNotBlank() }?.let { line("    SONGWRITER \"${q(it)}\"") }
            if (t.gapSectors > 0) {
                line("    INDEX 00 00:00:00")
                line("    INDEX 01 ${msf(t.gapSectors)}")
            } else {
                line("    INDEX 01 00:00:00")
            }
        }
        return sb.toString()
    }

    /**
     * 整盘镜像 CUE：单个 `FILE`，INDEX 为**相对镜像起点的绝对时间**。
     *
     * 与逐轨 CUE 的区别不只是 FILE 数量：INDEX 不写 00:00:00，而是各轨在整盘中的位置，
     * 否则播放器会把每一轨都当成从 0 开始。
     */
    fun buildImage(
        album: AlbumMeta?,
        tracks: List<AlbumTrackResult>,
        imageName: String,
        firstLba: Int,
        comment: String = "Salt Audio Copy",
        catalog: String? = null,
        songwriters: Map<Int, String> = emptyMap()
    ): String {
        val sb = StringBuilder()
        sb.append(BOM)
        fun line(s: String = "") { sb.append(s).append("\r\n") }
        album?.genre?.let { line("REM GENRE $it") }
        album?.year?.let { line("REM DATE $it") }
        album?.discId?.takeIf { it.isNotBlank() }?.let { line("REM DISCID $it") }
        album?.cddbId?.takeIf { it.isNotBlank() }?.let { line("REM CDDBID $it") }
        catalog?.takeIf { it.isNotBlank() }?.let { line("CATALOG $it") }
        line("REM COMMENT \"$comment\"")
        album?.albumArtist?.let { line("PERFORMER \"${q(it)}\")") }
        album?.albumTitle?.let { line("TITLE \"${q(it)}\")") }
        line()
        line("FILE \"${q(imageName)}\" WAVE")
        tracks.forEach { t ->
            val start = (t.startLba - firstLba).coerceAtLeast(0)
            line("  TRACK %02d AUDIO".format(t.number))
            line("    TITLE \"${q(t.title)}\"")
            t.artist?.takeIf { it.isNotBlank() }?.let { line("    PERFORMER \"${q(it)}\")") }
            t.isrc?.takeIf { it.isNotBlank() }?.let { line("    ISRC $it") }
            songwriters[t.number]?.takeIf { it.isNotBlank() }?.let { line("    SONGWRITER \"${q(it)}\"") }
            if (t.gapSectors > 0) {
                line("    INDEX 00 ${msf((start - t.gapSectors).coerceAtLeast(0))}")
            }
            line("    INDEX 01 ${msf(start)}")
        }
        return sb.toString()
    }

    fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.write(path, content.toByteArray(UTF_8))
    }

    private fun q(s: String): String = s.replace("\"", "'")

    /** CUE 的 mm:ss:ff（75 帧/秒）。 */
    internal fun msf(sectors: Int): String {
        val m = sectors / (75 * 60)
        val sec = (sectors / 75) % 60
        val f = sectors % 75
        return "%02d:%02d:%02d".format(m, sec, f)
    }
}

/** 抓轨日志：可读、可对账，包含每轨 CRC 与设置摘要。 */
object LogWriter {

    fun build(
        appVersion: String,
        drive: String,
        toc: DiscToc,
        album: AlbumMeta?,
        tracks: List<AlbumTrackResult>,
        elapsedMs: Long,
        cancelled: Boolean,
        settingsSummary: List<Pair<String, String>>,
        notes: List<String>,
        /** 这张盘有没有 CD-TEXT；null 表示没检测（例如离线/预扫流程） */
        hasCdText: Boolean? = null
    ): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append("\r\n") }
        line("Salt Audio Copy $appVersion — CD 抓轨日志")
        line("=".repeat(60))
        line("驱动器        : $drive")
        line("光盘          : ${album?.albumArtist ?: "未知艺术家"} - ${album?.albumTitle ?: "未知专辑"}")
        album?.year?.let { line("年份          : $it") }
        album?.genre?.let { line("流派          : $it") }
        album?.discId?.takeIf { it.isNotBlank() }?.let { line("MusicBrainz   : $it") }
        album?.cddbId?.takeIf { it.isNotBlank() }?.let { line("CDDB          : $it") }
        album?.source?.let { line("元数据来源    : $it") }
        line("音轨数        : " + if (tracks.size == toc.tracks.size) "${toc.tracks.size}" else "${tracks.size} / ${toc.tracks.size}（部分）")
        line("Lead-out LBA  : ${toc.leadOutLba}")
        line("总时长        : ${hms((toc.leadOutLba - (toc.tracks.firstOrNull()?.lba ?: 0)).toLong())}")
        line("CD-TEXT       : " + when (hasCdText) {
            true -> "有"
            false -> "无"
            null -> "未检测"
        })
        line("用时          : ${hms(elapsedMs / 1000L * 75)}${if (cancelled) "（已取消）" else ""}")
        line()
        line("TOC")
        line("-".repeat(60))
        line("轨  起始LBA      时长")
        toc.tracks.forEachIndexed { i, e ->
            val next = if (i + 1 < toc.tracks.size) toc.tracks[i + 1].lba else toc.leadOutLba
            val len = (next - e.lba).toLong()
            line("%02d  %9d  %02d:%02d:%02d".format(
                e.track, e.lba, len / 75 / 60, (len / 75) % 60, len % 75
            ))
        }
        line()
        line("设置摘要")
        line("-".repeat(60))
        settingsSummary.forEach { (k, v) -> line("%-14s: %s".format(k, v)) }
        line()
        line("轨道")
        line("-".repeat(60))
        val withTest = tracks.any { it.testCrc32 != null }
        line(
            "轨 起始LBA   扇区数   时长      峰值dBFS  质量    CRC32     重试  用时" +
                (if (withTest) "  测试CRC" else "")
        )
        for (t in tracks) {
            val base = "%02d %9d %7d  %-9s  %6s  %6.1f%%  %08X  %4d  %5.1fs%s".format(
                t.number, t.startLba, t.sectors, mmss(t.sectors),
                if (t.peakLevel > 0.0) "%6.1f".format(20.0 * kotlin.math.log10(t.peakLevel)) else "  -inf",
                t.qualityPct, t.crc32, t.errors, t.elapsedMs / 1000.0,
                if (t.skipped) "  [跳过]" else ""
            )
            line(
                if (withTest) {
                    base + (t.testCrc32?.let { "  %08X".format(it) } ?: "  --------") +
                        if (t.testCrc32 != null && t.testCrc32 != t.crc32) "（不一致）" else ""
                } else base
            )
        }
        line()
        val totalErr = tracks.sumOf { it.errors }
        line("合计重试      : $totalErr")
        line("整体质量      : %.2f%%".format(
            if (tracks.isEmpty()) 100.0 else tracks.sumOf { it.qualityPct } / tracks.size
        ))
        val peak = tracks.maxOfOrNull { it.peakLevel } ?: 0.0
        line("最高峰值      : " + if (peak > 0.0) "%.2f dBFS".format(20.0 * kotlin.math.log10(peak)) else "-inf（静音）")
        line("全盘 CRC 合并 : %08X".format(foldCrc(tracks)))
        val retriedAll = tracks.flatMap { it.retried }
        if (retriedAll.isNotEmpty()) {
            line("重试位置      : " + retryRanges(retriedAll))
        }
        if (notes.isNotEmpty()) {
            line()
            line("备注")
            line("-".repeat(60))
            notes.forEach { line("- $it") }
        }
        line()
        line("由 Salt Audio Copy 生成。CRC32 为每轨 PCM 数据（不含 WAV 头）的校验值。")
        return sb.toString()
    }

    fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.write(path, content.toByteArray(UTF_8))
    }

    /** 每轨 CRC 串联成整盘指纹：把各轨 crc 的字节按顺序再算一次 CRC32。 */
    fun foldCrc(tracks: List<AlbumTrackResult>): Long {
        val c = java.util.zip.CRC32()
        tracks.forEach { t ->
            for (shift in intArrayOf(24, 16, 8, 0)) c.update((t.crc32 ushr shift).toInt() and 0xFF)
        }
        return c.value
    }

    private fun mmss(sectors: Int): String {
        val totalSec = sectors / 75
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun hms(sectors: Long): String {
        val totalSec = sectors / 75
        return "%d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }
}

/**
 * 抓轨错误报告：只在有错误/重试/失败备注时生成，便于用户回传定位。
 * 格式刻意做成纯文本、每行一条，便于粘贴到 issue。
 */
object ErrReport {

    fun build(albumName: String, toc: DiscToc, tracks: List<AlbumTrackResult>, notes: List<String>): String {
        val sb = StringBuilder()
        sb.append("Salt Audio Copy 抓轨错误报告\r\n")
        sb.append("专辑: ").append(albumName).append("\r\n")
        sb.append("盘片: ").append(toc.tracks.size).append(" 轨, leadOut LBA ").append(toc.leadOutLba).append("\r\n")
        sb.append("时间: ").append(java.time.LocalDateTime.now()).append("\r\n")
        sb.append("\r\n")

        val bad = tracks.filter { it.errors > 0 }
        if (bad.isEmpty()) {
            sb.append("没有被标记错误的音轨（以下是流程备注）\r\n")
        } else {
            sb.append("异常音轨:\r\n")
            for (t in bad) {
                sb.append("  第 %02d 轨 %s  errors=%d crc=%08X startLba=%d sectors=%d\r\n"
                    .format(t.number, t.title, t.errors, t.crc32, t.startLba, t.sectors))
                if (t.retried.isNotEmpty()) {
                    sb.append("    重试位置: ").append(retryRanges(t.retried)).append("\r\n")
                }
            }
        }
        if (notes.isNotEmpty()) {
            sb.append("\r\n流程备注:\r\n")
            for (n in notes) sb.append("  - ").append(n).append("\r\n")
        }
        sb.append("\r\n提示: 若同一轨反复出错，通常是盘面划伤或驱动器读该区域能力不足。\r\n")
        return sb.toString()
    }

    fun write(path: java.nio.file.Path, text: String) {
        java.nio.file.Files.write(path, text.toByteArray(Charsets.UTF_8))
    }
}

/** 把重读过的扇区区间格式化成 `LBA 1200-1215(16)`，最多列 [limit] 段。 */
internal fun retryRanges(ranges: List<IntRange>, limit: Int = 8): String {
    if (ranges.isEmpty()) return ""
    val shown = ranges.take(limit).joinToString(", ") { r ->
        "LBA ${r.first}-${r.last}(${r.count()})"
    }
    return if (ranges.size > limit) "$shown …共 ${ranges.size} 段" else shown
}
