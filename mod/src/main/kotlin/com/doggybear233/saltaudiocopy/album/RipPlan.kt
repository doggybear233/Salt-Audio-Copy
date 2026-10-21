package com.doggybear233.saltaudiocopy.album

import com.doggybear233.saltaudiocopy.ui.Strings
import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.naming.TemplateEngine
import com.doggybear233.saltaudiocopy.naming.TrackSelection
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * 抓轨计划（dry-run）：不写盘、不读盘，只算出「这次会生成哪些文件、会不会撞名、空间够不够」。
 *
 * 存在的价值：命名模板写错、覆盖策略选错、空间不够，都是**抓完整盘之后**才发现的错误。
 * 计划预览把它们提前到动手之前。
 *
 * 注意：这里的命名逻辑必须与 [AlbumRipper] 保持一致；
 * `RipPlanTest.planMatchesWhatRipperActuallyWrites` 用假驱动器抓一遍再逐名对账来守这条。
 */
object RipPlan {

    /** Windows 上资源管理器与多数播放器的心理上限（含盘符、分隔符与结尾的 \0）。 */
    private const val MAX_PATH = 250
    private const val MIN_NAME = 24

    /** 同一轨的两种可能产物名：判断「已存在」时必须都看。 */
    private val AUDIO_EXTS = listOf(".wav", ".flac")

    data class TrackPlan(
        val track: Int,
        val title: String,
        val startLba: Int,
        val sectors: Int,
        val gapBefore: Int,
        val gapAfter: Int,
        val fileName: String,
        val exists: Boolean,
        /** 写入 / 另存 / 覆盖 / 跳过 */
        val action: String,
        val bytes: Long
    )

    data class Plan(
        val albumDir: Path,
        val albumArtist: String,
        val albumTitle: String,
        val tracks: List<TrackPlan>,
        val totalBytes: Long,
        val freeBytes: Long?,
        val warnings: List<String>,
        val cueName: String?,
        val logName: String?,
        val coverName: String?,
        /** 整盘镜像文件名（imageMode 且间隙可拼接时）；逐轨文件只是中间产物。 */
        val imageName: String? = null
    ) {
        fun describe(): String {
            val sb = StringBuilder()
            sb.appendLine(Strings.fmt("输出目录：%s", albumDir))
            sb.appendLine(Strings.fmt("专辑：%s - %s", albumArtist, albumTitle))
            sb.appendLine(
                Strings.fmt(
                    "轨数：%s（合计 %s 扇区，约 %s）",
                    tracks.size, tracks.sumOf { it.sectors }, gb(totalBytes)
                )
            )
            freeBytes?.let {
                sb.appendLine(
                    Strings.fmt("可用空间：%s", gb(it)) +
                        if (it < totalBytes) "  " + Strings.message("← 不足") else ""
                )
            }
            sb.appendLine()
            sb.appendLine("  轨  起始LBA   扇区   间隙前/后  文件名                                  动作")
            for (t in tracks) {
                sb.appendLine(
                    "  %02d  %8d  %6d  %4d/%-4d  %-38s  %s".format(
                        t.track, t.startLba, t.sectors, t.gapBefore, t.gapAfter,
                        t.fileName.take(38), t.action
                    )
                )
            }
            imageName?.let {
                sb.appendLine()
                sb.appendLine(Strings.fmt("整盘镜像：%s", it))
                sb.appendLine(Strings.message("（逐轨文件只作为拼接中间产物，合成后删除）"))
            }
            val extras = listOfNotNull(
                cueName?.let { Strings.fmt("CUE：%s", it) },
                logName?.let { Strings.fmt("日志：%s", it) },
                coverName?.let { Strings.fmt("封面：%s", it) }
            )
            if (extras.isNotEmpty()) {
                sb.appendLine()
                extras.forEach { sb.appendLine(it) }
            }
            if (warnings.isNotEmpty()) {
                sb.appendLine()
                warnings.forEach { sb.appendLine("⚠ $it") }
            }
            return sb.toString().trimEnd()
        }

        private fun gb(bytes: Long): String = "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }

    fun build(
        toc: DiscToc,
        settings: RipSettings,
        album: AlbumMeta?,
        gaps: Map<Int, Int> = emptyMap(),
        onlyTracks: Set<Int>? = null
    ): Plan {
        val available = toc.tracks.map { it.track }.toSet()
        val fromSpec = TrackSelection.parse(settings.trackSelection, available)
        val effectiveOnly = onlyTracks ?: fromSpec
        val selected = toc.tracks.indices.filter { effectiveOnly == null || toc.tracks[it].track in effectiveOnly }

        val albumArtist = album?.albumArtist?.takeIf { it.isNotBlank() } ?: "未知艺术家"
        val albumTitle = album?.albumTitle?.takeIf { it.isNotBlank() } ?: "未知专辑"
        val albumVars = albumVars(album, settings, albumArtist, albumTitle)

        var dir = settings.effectiveRoot
        TemplateEngine.expandToSegments(settings.folderTemplate, albumVars).forEach { dir = dir.resolve(it) }
        val totalDiscsEff = settings.totalDiscsOverride.takeIf { it > 0 } ?: album?.totalDiscs ?: 1
        if (totalDiscsEff > 1 && !settings.folderTemplate.contains("%discnumber%")) {
            // 空模板会让多碟专辑直接把 CD1/CD2 两张混进同一个目录：空就退回默认命名。
            val md = settings.multiDiscFolder.ifBlank { "CD%discnumber%" }
            TemplateEngine.expandToSegments(md, albumVars).forEach { dir = dir.resolve(it) }
        }

        val warnings = ArrayList<String>()
        if (dir.toString().length > MAX_PATH - 60) {
            warnings += "输出目录层级很深（%d 字符），文件名会被自动截短以免超过 Windows 的 260 字符限制".format(dir.toString().length)
        }
        if (settings.overwritePolicy.equals("overwrite", true)) warnings += "覆盖策略是「直接覆盖」，同名文件会被写掉"
        if (!settings.fileTemplate.contains("%tracknr") && !settings.fileTemplate.contains("%title")) {
            warnings += "文件名模板既没有轨号也没有标题，所有音轨会撞名"
        }

        // 预览里的扩展名必须与抓轨最终落盘的一致：FLAC 模式写完 WAV 会转成 .flac
        //（keepWav=false 时 WAV 还会被删掉），所以这里统一按 format 取扩展名。
        val ext = if (settings.format.equals("flac", true)) ".flac" else ".wav"

        val tracks = ArrayList<TrackPlan>(selected.size)
        var total = 0L
        for (i in selected) {
            val entry = toc.tracks[i]
            val meta = album?.track(entry.track)
            val title = meta?.title?.takeIf { it.isNotBlank() } ?: "Track %02d".format(entry.track)
            val artist = meta?.artist?.takeIf { it.isNotBlank() } ?: albumArtist
            val (startLba, sectors) = toc.sectorsOf(i)
            val vars = albumVars + mapOf(
                "tracknr" to entry.track.toString(),
                "tracknr1" to entry.track.toString(),
                "tracknr2" to "%02d".format(entry.track),
                "tracknr3" to "%03d".format(entry.track),
                "title" to title,
                "artist" to artist,
                "tracklen" to mmss(sectors),
                "tracklength" to mmss(sectors)
            )
            val base = TemplateEngine.expand(settings.fileTemplate, vars).ifBlank { "%02d".format(entry.track) }
            // Windows 的资源管理器/多数播放器仍然受 260 字符限制：
            // 目录本身很长时，150 字符的文件名会让整条路径超限（写盘失败但日志只有 IOException）。
            val room = (MAX_PATH - dir.toString().length - ext.length - 1).coerceAtLeast(MIN_NAME)
            val baseName = TemplateEngine.sanitizeSegment(base, minOf(150, room))
            val gapBefore = gaps[entry.track] ?: 0
            val gapAfter = if (gaps.isEmpty()) 0 else {
                val nextIdx = toc.tracks.indexOfFirst { it.track == entry.track + 1 }
                if (nextIdx >= 0) gaps[toc.tracks[nextIdx].track] ?: 0 else 0
            }
            val expectedSectors = when (settings.gapMode.lowercase()) {
                "prepend" -> sectors + gapBefore
                "leaveout" -> (sectors - gapAfter).coerceAtLeast(1)
                else -> sectors
            }
            val bytes = expectedSectors.toLong() * 2352L
            // 同名判断要看两种扩展名：上一轮抓成 WAV、这一轮选 FLAC 时，
            // 只查 .flac 会显示「写入」，实际覆盖策略却已经撞上了那份 WAV。
            val exists = AUDIO_EXTS.any { Files.exists(dir.resolve(baseName + it)) }
            val existingWav = dir.resolve(baseName + ".wav")
            val existingData = runCatching {
                com.doggybear233.saltaudiocopy.rip.WavInfo.read(existingWav)?.dataBytes ?: 0L
            }.getOrDefault(0L)
            // 续抓开关打开且已有一份「写了一半」的同名 WAV 时，真实行为是接着写，
            // 既不是「覆盖」也不是「跳过」——预览必须说清楚，否则用户以为这一轨要重抓。
            val resumable = settings.resume && existingData > 0 && existingData < bytes
            val targetWav = Overwrite.resolveAudio(
                dir, baseName, settings.overwritePolicy,
                resumeExpectedBytes = if (resumable) bytes else null
            )
            // resolveAudio 给的是「先写哪个 WAV」；FLAC 模式最终文件名是 .flac。
            // 续抓时仍写 WAV，所以只在非续抓时换扩展名。
            val resolved = targetWav?.let {
                if (ext == ".flac" && !resumable) {
                    it.resolveSibling(it.fileName.toString().removeSuffix(".wav") + ".flac")
                } else {
                    it
                }
            }
            val action = when {
                resolved == null -> "跳过（已存在）"
                resolved.fileName.toString() != baseName + ext -> "另存为 ${resolved.fileName}"
                resumable -> "续抓（接着已有的 %d 扇区写）".format(existingData / 2352)
                exists -> "覆盖"
                else -> "写入"
            }
            val fileName = resolved?.fileName?.toString() ?: (baseName + ext)
            if (resolved != null) total += bytes
            tracks += TrackPlan(
                entry.track, title, startLba, sectors, gapBefore, gapAfter,
                fileName, exists, action, bytes
            )
        }

        val free = FreeSpace.usableBytes(dir)
        // 注意单位：FreeSpace 要的是扇区数，以前把字节总量传进去了，
        // 结果 1.15 倍后变成一个离谱的数字（动不动就报「需要 200 GB」）。
        val totalSectors = tracks.sumOf { it.sectors.toLong() + it.gapBefore }
        FreeSpace.check(free, totalSectors, tracks.size, settings.format, settings.imageMode)
            ?.let { warnings += it }

        return Plan(
            albumDir = dir,
            albumArtist = albumArtist,
            albumTitle = albumTitle,
            tracks = tracks,
            totalBytes = total,
            freeBytes = free,
            warnings = warnings,
            // 名字必须与 AlbumRipper 实际写出的文件一致：CUE/日志用同一个 baseName 清洗规则，
            // 封面按 coverFileName 清洗（扩展名由图片 MIME 决定，预览按最常见的 .jpg 显示）。
            cueName = if (settings.writeCue) cueBase(albumArtist, albumTitle) + ".cue" else null,
            logName = if (settings.writeLog) cueBase(albumArtist, albumTitle) + ".log" else null,
            coverName = if (settings.fetchMetadata) {
                TemplateEngine.sanitizeSegment(settings.coverFileName, 150) + ".jpg"
            } else null,
            imageName = if (settings.imageMode && settings.gapMode.lowercase() == "append") {
                TemplateEngine.sanitizeSegment("$albumArtist - $albumTitle", 120) + ext
            } else null
        )
    }

    private fun cueBase(artist: String, title: String): String =
        TemplateEngine.sanitizeSegment("$artist - $title", 120)

    internal fun albumVars(
        album: AlbumMeta?,
        settings: RipSettings,
        albumArtist: String,
        albumTitle: String
    ): Map<String, String> = mapOf(
        "albumartist" to albumArtist,
        "albuminterpret" to albumArtist,
        "albumtitle" to albumTitle,
        "album" to albumTitle,
        "year" to (album?.year ?: ""),
        "date" to (album?.year ?: ""),
        "genre" to (album?.genre ?: ""),
        "discid" to (album?.discId ?: ""),
        "cddbid" to (album?.cddbId ?: ""),
        "discnumber" to (settings.discNumberOverride.takeIf { it > 0 } ?: album?.discNumber ?: 1).toString(),
        "totaldiscs" to (settings.totalDiscsOverride.takeIf { it > 0 } ?: album?.totalDiscs ?: 1).toString(),
        "albumcomposer" to "",
        "catalognumber" to ""
    )

    private fun mmss(sectors: Int): String {
        val totalSec = sectors / 75
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
