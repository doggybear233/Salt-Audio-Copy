package com.saltrip.rip.album

import com.saltrip.rip.ui.Strings
import com.saltrip.rip.Diag
import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.meta.DiscId
import com.saltrip.rip.meta.MetaOverride
import com.saltrip.rip.meta.MetadataService
import com.saltrip.rip.meta.TrackMeta
import com.saltrip.rip.naming.TemplateEngine
import com.saltrip.rip.rip.RipEngine
import com.saltrip.rip.rip.RipProgress
import com.saltrip.rip.rip.ScanResult
import com.saltrip.rip.rip.TrackSource
import com.saltrip.rip.encode.FlacEncoder
import com.saltrip.rip.encode.FlacMeta
import com.saltrip.rip.naming.TrackSelection
import com.saltrip.rip.scsi.CdDevice
import com.saltrip.rip.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * 整张专辑的抓轨流程：
 * 1. 取元数据（可关）
 * 2. 按模板算出专辑目录与每轨文件名
 * 3. 逐轨抓取（可选双读校验）
 * 4. 落封面、CUE、日志
 *
 * 所有失败都在这里聚合成 [AlbumRipResult]，由调用方转成 UI 提示。
 */
/** 面板上的限速值 → 真正下发给 SET CD SPEED 的 KB/s。 */
internal fun speedRequestKbps(driveSpeed: Int): Int = if (driveSpeed > 0) driveSpeed else UNLIMITED_KBPS

/**
 * 「不限速」时发一个比任何光驱都高的值，让驱动器自己压到上限。
 *
 * 不能用 0xFFFF（MMC 里表示「不改变」），也不能用 0（部分驱动器当「默认低速」），
 * 所以显式要 40x，由驱动器钳制。
 */
internal const val UNLIMITED_KBPS: Int = 7056

object AlbumRipper {

    /** 版本号只从 [com.saltrip.rip.Version] 取，别再硬编码（会让日志抬头长期停在旧版本）。 */
    const val APP_VERSION = com.saltrip.rip.Version.VALUE

    /**
     * @param onlyTracks 只抓指定轨号（1 起）；null 表示整盘。用于「抓选中的轨」与集成测试。
     */
    fun rip(
        scan: ScanResult,
        settings: RipSettings,
        cancel: () -> Boolean,
        onProgress: (RipProgress) -> Unit,
        onStage: (String) -> Unit = {},
        onlyTracks: Set<Int>? = null,
        /** 仅测试用：给定假驱动器后，所有真实 IOCTL 路径（CD-TEXT / 换碟守卫 / 弹托盘）自动跳过。 */
        source: TrackSource? = null
    ): AlbumRipResult {
        // 盘符设置优先：多光驱时把「第一台」当成目标太容易抓错盘
        val scan = RipEngine.selectScan(settings.driveLetter, scan)
        val toc = scan.toc
        if (settings.driveSpeed > 0) {
            val kbps = speedRequestKbps(settings.driveSpeed)
        runCatching { CdDevice(scan.path).use { it.setSpeed(kbps) } }
                .onSuccess { Diag.log("drive speed limited to ${settings.driveSpeed} KB/s") }
                .onFailure { Diag.log("set speed failed", it) }
        }
        RipEngine.requireAudioCd(toc)
        val startedAt = System.nanoTime()
        val notes = ArrayList<String>()
        // 「用户需要知道的问题」单独收一份：笔记里有不少是提示性的（用了缓存、错误报告路径…），
        // 混在一起会让「全绿」的完成提示盖住真正的失败（比如 FLAC 没编出来、CUE 没写出来）。
        val warnings = ArrayList<String>()
        fun warn(msg: String) {
            notes += msg
            warnings += msg
        }

        // 读命令偏好要在任何 CdDevice 之前生效（抓轨、预扫、CD-TEXT 都会新建设备）
        com.saltrip.rip.scsi.ReadCommandPreference.apply(settings.readCommand)

        onStage(Strings.message("读取元数据…"))
        // CD-TEXT 直接来自盘片，先读它；读不到（绝大多数压制盘）就静默跳过，不额外打扰用户。
        val cdtext = if (settings.useCdText && source == null) {
            runCatching { CdDevice(scan.path).use { it.readCdText() } }
                .onFailure {
                    Diag.log("cd-text read failed", it)
                    warn(Strings.fmt("CD-TEXT 读取失败: %s", it.message))
                }
                .getOrNull()
        } else null
        // 上次抓过同一张盘时，先看有没有我们自己存的 album.json：
        // 批量/断点重抓不用联网，用户手改过名称也能直接被复用。
        val cachedMeta = if (settings.reuseAlbumJson) {
            runCatching {
                val discId = DiscId.musicBrainz(toc)
                DiscCache.load(settings.effectiveRoot, discId)
            }.onFailure { Diag.log("album.json cache read failed", it) }.getOrNull()
        } else null
        if (cachedMeta != null) notes += Strings.message("使用上次保存的元数据（album.json）")

        val album = cachedMeta ?: if (settings.fetchMetadata) {
            runCatching { MetadataService.lookup(toc, settings, notes, cdtext) }
                .onFailure { Diag.log("metadata lookup failed", it); warn(Strings.fmt("元数据查询异常: %s", it.message)) }
                .getOrNull()
        } else {
            notes += Strings.message("已关闭联网元数据查询")
            cdtext?.takeIf { it.hasText }?.let {
                notes += Strings.message("使用盘片 CD-TEXT 命名")
                it.toAlbumMeta(toc)
            }
        }

        val albumArtist = album?.albumArtist?.takeIf { it.isNotBlank() } ?: "未知艺术家"
        val albumTitle = album?.albumTitle?.takeIf { it.isNotBlank() } ?: "未知专辑"
        val albumVars = mapOf(
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
            "cdnumber" to (settings.discNumberOverride.takeIf { it > 0 } ?: album?.discNumber ?: 1).toString(),
            "totaldiscs" to (settings.totalDiscsOverride.takeIf { it > 0 } ?: album?.totalDiscs ?: 1).toString(),
            "totalcds" to (settings.totalDiscsOverride.takeIf { it > 0 } ?: album?.totalDiscs ?: 1).toString(),
            "freedb_id" to (album?.cddbId ?: ""),
            "freedbid" to (album?.cddbId ?: ""),
            "upc" to (cdtext?.upc ?: ""),
            "albumcomposer" to "",
            "catalognumber" to (cdtext?.upc ?: "")
        )

        var dir = settings.effectiveRoot
        TemplateEngine.expandToSegments(settings.folderTemplate, albumVars)
            .forEach { dir = dir.resolve(it) }
        val totalDiscsEff = settings.totalDiscsOverride.takeIf { it > 0 } ?: album?.totalDiscs ?: 1
        if (totalDiscsEff > 1 && !settings.folderTemplate.contains("%discnumber%")) {
            TemplateEngine.expandToSegments(settings.multiDiscFolder, albumVars)
                .forEach { dir = dir.resolve(it) }
        }
        // 记录抓轨前专辑目录里已有的文件名：结束时差集就是「本次新建」，
        // 取消时只删这些，绝不碰用户之前抓好的文件。
        val beforeRun: Set<String> = runCatching {
            java.nio.file.Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() }
        }.getOrDefault(emptySet())
        Files.createDirectories(dir)
        Diag.log("album outDir=$dir (template=${settings.folderTemplate})")

        // 轨选择：显式参数优先，其次是设置里的 "1-5,8" 规则
        val availableTracks = toc.tracks.map { it.track }.toSet()
        val fromSpec = TrackSelection.parse(settings.trackSelection, availableTracks)
        val effectiveOnly = onlyTracks ?: fromSpec
        val selected = toc.tracks.indices.filter { effectiveOnly == null || toc.tracks[it].track in effectiveOnly }
        require(selected.isNotEmpty()) {
            Strings.fmt(
                "没有选中任何音轨（规则：%s，盘上有 %s 轨）",
                settings.trackSelection.ifBlank { Strings.message("全部") },
                availableTracks.size
            )
        }
        if (fromSpec != null) {
            val picked = selected.map { toc.tracks[it].track }
            notes += Strings.fmt("按选择规则抓取：%s", TrackSelection.describe(picked))
            Diag.log("trackSelection='${settings.trackSelection}' -> ${TrackSelection.describe(picked)}")
        }
        // 预扫整盘间隙：每轨至多扫 maxScan 个扇区，换来抓轨阶段不再重复扫 Q 子通道。
        var preScanned: Map<Int, Int> = emptyMap()
        val needPrescan = settings.analyzeBeforeRip || settings.gapMode.lowercase() != "append"
        if (needPrescan) {
            onStage(Strings.message("探测 INDEX 00 间隙…"))
            val collect = { src: com.saltrip.rip.rip.TrackSource ->
                val gaps = DiscAnalysis.gapsFor(toc, src, gapMethod = settings.gapMethod) { Diag.log(it) }
                preScanned = gaps.associate { it.number to it.gapSectors }
                Diag.log("gap prescan: " + DiscAnalysis.describe(gaps).replace("\n", " | "))
            }
            if (source != null) {
                collect(source)
            } else {
                runCatching { CdDevice(scan.path).use { collect(it) } }.onFailure {
                    Diag.log("gap prescan failed", it)
                    warn(Strings.fmt("间隙预扫失败（抓轨时逐轨再试）: %s", it.message))
                }
            }
        }

        val results = ArrayList<AlbumTrackResult>(selected.size)
        val fileNames = ArrayList<String>(selected.size)
        val totalAll = selected.sumOf { toc.sectorsOf(it).second }

        // 余量检查放在写第一个字节之前：写到一半空间耗尽留下的半截 WAV 无法续抓（大小不合法）
        FreeSpace.check(
            FreeSpace.usableBytes(dir), totalAll.toLong(), selected.size, settings.keepWav
        )?.let {
            Diag.log("free space check failed: $it")
            error(it)
        }
        var doneBase = 0
        var bytesBase = 0L
        var cancelled = false
        val eta = com.saltrip.rip.rip.EtaEstimator()

        // Test & Copy：先用「只读不写」的测试通道跑一遍，拿到每轨 CRC 作为对照
        val testCrc = HashMap<Int, Long>()
        if (settings.testAndCopy) {
            for (i in selected) {
                if (cancel()) { cancelled = true; break }
                val entry = toc.tracks[i]
                onStage(Strings.fmt("测试读取第 %s 轨…", entry.track))
                val r = RipEngine.ripTrack(
                    scan, i, dir.resolve("__test__.tmp"), cancel, { },
                    verify = settings.secureMode,
                    maxRetries = settings.maxRetries,
                    retryDelayMs = settings.retryDelayMs,
                    useC2 = settings.useC2,
                        defeatCache = settings.defeatCache,
                    writeFile = false,
                    knownGapSectors = preScanned[entry.track],
                    gapMethod = settings.gapMethod,
                    gapMode = settings.gapMode,
                    nextGapSectors = if (preScanned.isEmpty()) null else {
                        val nextIdx = toc.tracks.indexOfFirst { it.track == entry.track + 1 }
                        if (nextIdx >= 0) preScanned[toc.tracks[nextIdx].track] else null
                    },
                    source = source
                )
                testCrc[entry.track] = r.crc32
            }
            Diag.log("test&copy: 测试通道完成 ${testCrc.size} 轨")
        }

        for (i in selected) {
            if (cancel()) { cancelled = true; break }
            val entry = toc.tracks[i]
            if (settings.guardDisc && results.isNotEmpty() && source == null) {
                val now = runCatching { CdDevice(scan.path).use { it.readToc() } }.getOrNull()
                if (now != null && !DiscGuard.sameDisc(toc, now)) {
                    warn(
                        DiscGuard.mismatchMessage(
                            toc.leadOutLba, now.leadOutLba,
                            Strings.fmt("第 %s 轨之前", entry.track)
                        )
                    )
                    Diag.log("disc changed before track ${entry.track}: leadOut ${toc.leadOutLba} -> ${now.leadOutLba}")
                    cancelled = true
                    break
                }
            }
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
            // 续抓打开时，半截文件必须原位续写（否则 0.5.0 的 suffix 策略会把它变成 (2).wav，
            // 断点续抓就永远失效了）
            val gapBefore = preScanned[entry.track] ?: 0
            val gapAfter = if (preScanned.isEmpty()) 0 else {
                val nextIdx = toc.tracks.indexOfFirst { it.track == entry.track + 1 }
                if (nextIdx >= 0) preScanned[toc.tracks[nextIdx].track] ?: 0 else 0
            }
            val expectedSectors = when (settings.gapMode.lowercase()) {
                "prepend" -> sectors + gapBefore
                "leaveout" -> (sectors - gapAfter).coerceAtLeast(1)
                else -> sectors
            }
            val expectedData = expectedSectors.toLong() * 2352L
            val target = Overwrite.resolve(
                dir, baseName, ".wav", settings.overwritePolicy,
                resumeExpectedBytes = if (settings.resume) expectedData else null
            )
            if (target == null) {
                notes += Strings.fmt("第 %s 轨目标文件已存在，按设置跳过", entry.track)
                Diag.log("skip existing track=${entry.track} base=$baseName")
                // 跳过的轨也计入「已完成」，否则进度条永远到不了 100%、剩余时间越算越离谱
                doneBase += sectors
                continue
            }
            val rel = target.fileName.toString()
            if (rel != baseName + ".wav") Diag.log("overwrite policy renamed '$baseName.wav' -> '$rel'")
            val outFile = target
            onStage(Strings.fmt("抓取第 %s 轨：%s", entry.track, title))
            val r = RipEngine.ripTrack(
                scan, i, outFile, cancel, { p ->
                    // 关键：elapsed 必须是**整盘累计**时间、done 是**整盘累计**扇区，
                    // 否则「本轨已用 / 全盘已完成」两个量纲混用，剩余时间会乱跳甚至变大
                    val nowMs = (System.nanoTime() - startedAt) / 1_000_000
                    val done = doneBase + p.sectorsDone
                    onProgress(
                        RipProgress(
                            done, totalAll, bytesBase + p.bytes, nowMs, p.errors,
                            eta.sample(done, totalAll, nowMs)
                        )
                    )
                },
                verify = settings.secureMode,
                maxRetries = settings.maxRetries,
                retryDelayMs = settings.retryDelayMs,
                useC2 = settings.useC2,
                detectGaps = settings.detectGaps && preScanned.isEmpty(),
                        knownGapSectors = preScanned[entry.track],
                        gapMethod = settings.gapMethod,
                // 下界用上一轨 INDEX 01：预间隙扇区属于上一轨范围，用「上一轨结束」会扫不到
                gapFloorLba = if (i > 0) toc.tracks[i - 1].lba else startLba,
                errorRecovery = settings.errorRecovery,
                gapMode = settings.gapMode,
                nextGapSectors = if (gapAfter > 0) gapAfter else null,
                resume = settings.resume,
                source = source
            )
            if (settings.testAndCopy && !r.cancelled) {
                val expected = testCrc[entry.track]
                if (expected != null && expected != r.crc32) {
                    Diag.log(
                        "test&copy mismatch track=${entry.track} test=0x%08X copy=0x%08X，重抓一次"
                            .format(expected, r.crc32)
                    )
                    warn(
                        Strings.fmt(
                            "第 %s 轨测试/复制 CRC 不一致（0x%08X vs 0x%08X），已重抓",
                            entry.track, expected, r.crc32
                        )
                    )
                    val again = RipEngine.ripTrack(
                        scan, i, outFile, cancel, { },
                        verify = settings.secureMode,
                        maxRetries = settings.maxRetries,
                        retryDelayMs = settings.retryDelayMs,
                        useC2 = settings.useC2,
                        resume = false,
                        source = source
                    )
                    if (again.crc32 != expected) {
                        warn(Strings.fmt("第 %s 轨重抓后仍与测试通道不一致（0x%08X）", entry.track, again.crc32))
                    } else {
                        notes += Strings.fmt("第 %s 轨重抓后与测试通道一致", entry.track)
                    }
                }
            }
            if (settings.offsetSamples != 0 && !r.cancelled) {
                runCatching { PcmOffset.apply(outFile, settings.offsetSamples) }
                    .onFailure { Diag.log("offset shift failed on $outFile", it); warn(Strings.fmt("offset 校正失败: %s", it.message)) }
            }
            // WAV 的 LIST/INFO 标签：资源管理器/播放器读它显示标题、艺术家、专辑
            //（反馈 6：抓下来的文件在资源管理器里没有标签）。追加在 data 之后，
            // 不影响 PCM 字节，因此也不影响 CRC 与编码器输入。
            if (!r.cancelled) {
                runCatching {
                    com.saltrip.rip.encode.WavTags.write(
                        outFile,
                        mapOf(
                            "title" to title,
                            "artist" to (album?.track(entry.track)?.artist ?: artist),
                            "album" to (album?.albumTitle ?: ""),
                            "date" to (album?.year?.toString() ?: ""),
                            "genre" to (album?.genre ?: ""),
                            "track" to "%d/%d".format(entry.track, availableTracks.size),
                            "comment" to "Salt Audio Copy $APP_VERSION"
                        )
                    )
                }.onFailure {
                    Diag.log("wav tag failed track=${entry.track}", it)
                    warn(Strings.fmt("第 %s 轨 WAV 标签写入失败（音频不受影响）: %s", entry.track, it.message))
                }
            }
            var finalFile = outFile
            var finalRel = rel
            val encodeAllowed = !r.cancelled && !(settings.imageMode && settings.gapMode.lowercase() == "append")
            if (encodeAllowed && settings.format.equals("flac", true)) {
                val flacRel = rel.removeSuffix(".wav") + ".flac"
                val flacFile = dir.resolve(flacRel)
                try {
                    val exe = FlacEncoder.locate()
                        ?: error("内置 FLAC 编码器不可用（插件包可能损坏，重新安装一次）")
                    val size = FlacEncoder.encode(exe, outFile, flacFile, settings.flacLevel)
                    finalFile = flacFile
                    finalRel = flacRel
                    Diag.log("flac track=${entry.track} -> $flacFile ($size B)")
                    runCatching {
                        val isrc = album?.track(entry.track)?.isrc
                        // 专辑名/艺术家一律传「已解析」的值：没有元数据时用占位名，
                        // 免得目录叫「未知专辑」而标签里根本没有 ALBUM 字段——
                        // 播放器会把这些文件散成「未知专辑」而聚合不起来。
                        val tags = FlacMeta.trackTags(
                            albumTitle = albumTitle,
                            albumArtist = albumArtist,
                            year = album?.year,
                            genre = album?.genre,
                            trackNumber = entry.track,
                            trackTitle = title,
                            trackArtist = artist,
                            isrc = isrc,
                            trackTotal = availableTracks.size,
                            discNumber = album?.discNumber,
                            totalDiscs = album?.totalDiscs,
                            comment = "Salt Audio Copy $APP_VERSION",
                            composer = cdtext?.songwriters?.get(entry.track)
                                ?: cdtext?.albumSongwriter
                        )
                        FlacMeta.writeTags(flacFile, tags, "Salt Audio Copy $APP_VERSION")
                        Diag.log("flac tags track=${entry.track}: ${tags.size}")
                    }.onFailure {
                        Diag.log("flac tag failed track=${entry.track}", it)
                        warn(Strings.fmt("第 %s 轨标签写入失败（音频不受影响）: %s", entry.track, it.message))
                    }
                    if (!settings.keepWav) runCatching { Files.deleteIfExists(outFile) }
                } catch (e: Throwable) {
                    Diag.log("flac encode failed track=${entry.track}", e)
                    warn(Strings.fmt("第 %s 轨 FLAC 编码失败（已保留 WAV）: %s", entry.track, e.message))
                }
            }
            fileNames += finalRel
            results += AlbumTrackResult(
                number = entry.track,
                title = title,
                artist = artist,
                file = finalFile,
                startLba = startLba,
                sectors = r.sectors,
                crc32 = r.crc32,
                errors = r.readErrors,
                elapsedMs = r.elapsedMs,
                skipped = r.cancelled,
                isrc = meta?.isrc,
                gapSectors = r.gapSectors,
                peakLevel = r.peakLevel,
                testCrc32 = if (settings.testAndCopy) testCrc[entry.track] else null,
                retried = r.retried
            )
            if (r.cancelled) { cancelled = true; break }
            doneBase += sectors
            bytesBase += r.bytes
        }

        // 整盘镜像：把逐轨 WAV 拼成一个文件。
        // 逐轨抓取（secure 双读 / C2 / gap / 续抓）的能力全部保留，拼接只做字节搬运。
        var imageRel: String? = null
        val imageAllowed = settings.imageMode && settings.gapMode.lowercase() == "append"
        if (settings.imageMode && !imageAllowed) {
            notes += Strings.fmt(
                "间隙处理为 %s 时音轨边界不连续，已跳过整盘镜像",
                settings.gapMode
            )
            Diag.log("image mode skipped: gapMode=${settings.gapMode}")
        }
        if (imageAllowed && !cancelled && results.isNotEmpty() && results.size == selected.size) {
            onStage(Strings.message("生成整盘镜像…"))
            val imgBase = TemplateEngine.sanitizeSegment("$albumArtist - $albumTitle", 120)
            val imgTarget = Overwrite.resolve(dir, imgBase, ".wav", settings.overwritePolicy)
            if (imgTarget == null) {
                notes += Strings.message("整盘镜像目标已存在，按设置跳过拼接（逐轨文件保留）")
            } else {
                runCatching {
                    val res = ImageSplicer.splice(results.map { it.file }, imgTarget)
                    Diag.log("image spliced: $imgTarget data=${res.dataBytes} sectors=${res.sectors} parts=${res.parts}")
                    for (k in results.indices) {
                        val part = results[k].file
                        results[k] = results[k].copy(file = imgTarget)
                        runCatching { Files.deleteIfExists(part) }
                    }
                    imageRel = imgTarget.fileName.toString()
                    if (settings.format.equals("flac", true)) {
                        val flacTarget = imgTarget.resolveSibling(imgTarget.fileName.toString().removeSuffix(".wav") + ".flac")
                        runCatching {
                            val exe = FlacEncoder.locate()
                                ?: error("内置 FLAC 编码器不可用（插件包可能损坏，重新安装一次）")
                            val size = FlacEncoder.encode(exe, imgTarget, flacTarget, settings.flacLevel)
                            Diag.log("flac image -> $flacTarget ($size B)")
                            if (!settings.keepWav) runCatching { Files.deleteIfExists(imgTarget) }
                            imageRel = flacTarget.fileName.toString()
                            // 结果里的 file 必须指向真实存在的文件：音频已转成 flac，
                            // WAV 也被删了，还指回 WAV 会让「打开目录/复制路径」指向空文件。
                            for (k in results.indices) results[k] = results[k].copy(file = flacTarget)
                        }.onFailure {
                            Diag.log("flac image encode failed", it)
                            warn(Strings.fmt("整盘镜像 FLAC 编码失败（已保留 WAV）: %s", it.message))
                        }
                    }
                }.onFailure {
                    Diag.log("image splice failed", it)
                    warn(Strings.fmt("整盘镜像拼接失败（逐轨文件保留）: %s", it.message))
                }
            }
        }

        var coverFile: Path? = null
        val cover = album?.cover
        if (cover != null && !cancelled) {
            val mime = album?.coverMime.orEmpty().lowercase()
            val ext = when {
                mime.contains("png") -> ".png"
                mime.contains("webp") -> ".webp"
                mime.contains("bmp") -> ".bmp"
                mime.contains("gif") -> ".gif"
                else -> ".jpg"
            }
            val p = dir.resolve(TemplateEngine.sanitizeSegment(settings.coverFileName) + ext)
            runCatching { Files.write(p, cover) }
                .onSuccess { coverFile = p; Diag.log("cover written: $p (${cover.size} B)") }
                .onFailure { Diag.log("cover write failed", it); warn(Strings.fmt("封面写入失败: %s", it.message)) }
        }

        // 封面内嵌放在封面落盘之后：抓轨阶段 mp3/flac 标签已写好，这里只补 picture 块。
        if (settings.embedCover && settings.format.equals("flac", true) && coverFile != null && results.isNotEmpty()) {
            val mime = album?.coverMime ?: "image/jpeg"
            runCatching {
                val image = Files.readAllBytes(coverFile!!)
                var ok = 0
                results.map { it.file }.distinct().filter { it.toString().lowercase().endsWith(".flac") }.forEach { f ->
                    FlacMeta.writeCover(f, image, mime)
                    ok++
                }
                Diag.log("flac cover embedded in $ok file(s)")
            }.onFailure {
                Diag.log("flac cover embed failed", it)
                warn(Strings.fmt("封面内嵌失败（音频与封面文件不受影响）: %s", it.message))
            }
        }

        val baseName = TemplateEngine.sanitizeSegment("$albumArtist - $albumTitle", 120)
        var cueFile: Path? = null
        if (settings.writeCue && results.isNotEmpty() && !cancelled) {
            val p = dir.resolve("$baseName.cue")
            runCatching {
                val text = if (imageRel != null) {
                    CueWriter.buildImage(
                        album, results, imageRel!!, toc.tracks.first().lba,
                        catalog = cdtext?.upc, songwriters = cdtext?.songwriters.orEmpty(),
                        annotateCrc = settings.annotateCueCrc
                    )
                } else {
                    CueWriter.build(
                        album, results, fileNames,
                        catalog = cdtext?.upc, songwriters = cdtext?.songwriters.orEmpty(),
                        annotateCrc = settings.annotateCueCrc
                    )
                }
                CueWriter.write(p, text)
            }.onSuccess { cueFile = p; Diag.log("cue written: $p") }
                .onFailure { Diag.log("cue write failed", it); warn(Strings.fmt("CUE 写入失败: %s", it.message)) }
        }

        var logFile: Path? = null
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        if (settings.writeLog && results.isNotEmpty()) {
            val p = dir.resolve("$baseName.log")
            runCatching {
                val text = LogWriter.build(
                    APP_VERSION, "${scan.inquiry.vendor} ${scan.inquiry.product} ${scan.inquiry.revision}".trim(),
                    toc, album, results, elapsedMs, cancelled,
                    listOf(
                        "secureMode" to settings.secureMode.toString(),
                        "maxRetries" to settings.maxRetries.toString(),
                        "retryDelayMs" to settings.retryDelayMs.toString(),
                        "offsetSamples" to settings.offsetSamples.toString(),
                        "folderTemplate" to settings.folderTemplate,
                        "fileTemplate" to settings.fileTemplate,
                        "format" to settings.format,
                        "readCommand" to settings.readCommand,
                        "imageMode" to settings.imageMode.toString(),
                                        "embedCover" to settings.embedCover.toString()
                    ),
                    notes,
                    hasCdText = cdtext?.hasText
                )
                LogWriter.write(p, text)
            }.onSuccess { logFile = p; Diag.log("log written: $p") }
                .onFailure { Diag.log("log write failed", it); warn(Strings.fmt("日志写入失败: %s", it.message)) }
        }

        if (settings.writeAlbumJson && results.isNotEmpty()) {
            val p = dir.resolve("album.json")
            runCatching {
                // 写盘用的元数据要跟目录/文件名一致：抓不到元数据时落占位名，
                // 否则下次读回来是空标题，反而把用户已经修好的目录名带偏。
                val base = album ?: AlbumMeta(
                    albumArtist = albumArtist, albumTitle = albumTitle,
                    discId = "", cddbId = "", source = ""
                )
                val written = base.copy(
                    albumArtist = base.albumArtist.takeIf { s -> s.isNotBlank() } ?: albumArtist,
                    albumTitle = base.albumTitle.takeIf { s -> s.isNotBlank() } ?: albumTitle,
                    tracks = base.tracks.ifEmpty {
                        listOf(TrackMeta(1, "Track 01", albumArtist, 0, null))
                    }
                )
                val text = AlbumJsonWriter.build(written, toc)
                Files.write(p, text.toByteArray(Charsets.UTF_8))
                runCatching {
                    val cache = DiscCache.path(settings.effectiveRoot, DiscId.musicBrainz(toc))
                    Files.createDirectories(cache.parent)
                    Files.write(cache, text.toByteArray(Charsets.UTF_8))
                }.onFailure { Diag.log("album.json cache write failed", it) }
                notes += Strings.fmt("元数据副本: %s", p.fileName)
                Diag.log("album.json written: $p")
            }.onFailure { Diag.log("album.json write failed", it); warn(Strings.fmt("元数据副本写入失败: %s", it.message)) }
        }

        // 只有出现错误/重试/失败备注时才落错误报告，正常抓轨保持目录干净
        val badTracks = results.filter { it.errors > 0 }
        if ((badTracks.isNotEmpty() || warnings.isNotEmpty()) && results.isNotEmpty()) {
            val p = dir.resolve("$baseName.errors.txt")
            runCatching {
                ErrReport.write(p, ErrReport.build("$albumArtist - $albumTitle", toc, results, notes))
                notes += Strings.fmt("错误报告: %s", p.fileName)
                Diag.log("error report written: $p")
            }.onFailure { Diag.log("error report failed", it) }
        }

        // 全部落盘之后才弹托盘：中途弹出会让后续轨道直接失败
        if (settings.ejectAfterRip && !cancelled) {
            if (source == null) runCatching { CdDevice(scan.path).use { it.eject() } }
                .onSuccess { Diag.log("tray ejected") }
                .onFailure { Diag.log("eject failed", it); notes += Strings.fmt("弹出托盘失败: %s", it.message) }
        }

        Diag.log(
            "album done: ${results.size}/${
                if (onlyTracks == null) toc.tracks.size else selected.size
            } tracks, cancelled=$cancelled, " +
                "elapsed=${elapsedMs}ms dir=$dir cue=$cueFile log=$logFile"
        )
        val created: List<java.nio.file.Path> = runCatching {
            java.nio.file.Files.list(dir).use { s ->
                s.filter { it.fileName.toString() !in beforeRun }.toList()
            }
        }.getOrDefault(emptyList())
        return AlbumRipResult(
            dir, album, results, elapsedMs, cancelled, cueFile, logFile, coverFile, notes, created,
            warnings
        )
    }

    private fun mmss(sectors: Int): String {
        val totalSec = sectors / 75
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
