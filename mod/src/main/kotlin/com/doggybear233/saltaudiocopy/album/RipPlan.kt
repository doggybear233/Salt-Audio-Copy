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
        val coverName: String?
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
        if (settings.overwritePolicy.equals("overwrite", true)) warnings += "覆盖策略是「直接覆盖」，同名文件会被写掉"
        if (!settings.fileTemplate.contains("%tracknr") && !settings.fileTemplate.contains("%title")) {
            warnings += "文件名模板既没有轨号也没有标题，所有音轨会撞名"
        }

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
            val baseName = TemplateEngine.sanitizeSegment(base, 150)
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
            val resolved = Overwrite.resolve(dir, baseName, ".wav", settings.overwritePolicy)
            val exists = Files.exists(dir.resolve(baseName + ".wav"))
            val action = when {
                resolved == null -> "跳过（已存在）"
                resolved.fileName.toString() != baseName + ".wav" -> "另存为 ${resolved.fileName}"
                exists -> "覆盖"
                else -> "写入"
            }
            val fileName = resolved?.fileName?.toString() ?: (baseName + ".wav")
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
        FreeSpace.check(free, totalSectors, tracks.size, settings.keepWav, settings.format, settings.imageMode)
            ?.let { warnings += it }

        return Plan(
            albumDir = dir,
            albumArtist = albumArtist,
            albumTitle = albumTitle,
            tracks = tracks,
            totalBytes = total,
            freeBytes = free,
            warnings = warnings,
            cueName = if (settings.writeCue) "$albumArtist - $albumTitle.cue" else null,
            logName = if (settings.writeLog) "$albumArtist - $albumTitle.log" else null,
            coverName = if (settings.fetchMetadata) "${settings.coverFileName}.jpg" else null
        )
    }

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
