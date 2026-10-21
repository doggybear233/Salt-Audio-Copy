package com.doggybear233.saltaudiocopy.album

import com.doggybear233.saltaudiocopy.ui.Strings
import com.doggybear233.saltaudiocopy.Diag
import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.DiscId
import com.doggybear233.saltaudiocopy.meta.MetaOverride
import com.doggybear233.saltaudiocopy.meta.MetadataService
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import com.doggybear233.saltaudiocopy.naming.TemplateEngine
import com.doggybear233.saltaudiocopy.rip.RipEngine
import com.doggybear233.saltaudiocopy.rip.RipProgress
import com.doggybear233.saltaudiocopy.rip.RipResult
import com.doggybear233.saltaudiocopy.rip.ScanResult
import com.doggybear233.saltaudiocopy.rip.TrackSource
import com.doggybear233.saltaudiocopy.encode.FlacEncoder
import com.doggybear233.saltaudiocopy.encode.FlacMeta
import com.doggybear233.saltaudiocopy.naming.TrackSelection
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.settings.RipSettings
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

    /** 版本号只从 [com.doggybear233.saltaudiocopy.Version] 取，别再硬编码（会让日志抬头长期停在旧版本）。 */
    const val APP_VERSION = com.doggybear233.saltaudiocopy.Version.VALUE

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
        // 0（不限速）也要下发：以前这里是 if (driveSpeed > 0)，选「不限速」等于完全不发命令，
        // 驱动器停在默认低速，用户看到的一直是 4x。
        val kbps = speedRequestKbps(settings.driveSpeed)
        runCatching { CdDevice(scan.path).use { it.setSpeed(kbps) } }
            .onSuccess {
                Diag.log(
                    if (settings.driveSpeed > 0) "drive speed limited to ${settings.driveSpeed} KB/s"
                    else "drive speed: unlimited, requested $kbps KB/s (drive clamps)"
                )
            }
            .onFailure { Diag.log("set speed failed", it) }
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
        com.doggybear233.saltaudiocopy.scsi.ReadCommandPreference.apply(settings.readCommand)

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
            // 与 RipPlan 用同一个兜底：模板留空时如果原样展开就是空串，
            // CD1/CD2 会落进同一个目录、互相覆盖。
            val md = settings.multiDiscFolder.ifBlank { "CD%discnumber%" }
            TemplateEngine.expandToSegments(md, albumVars)
                .forEach { dir = dir.resolve(it) }
        }
        // 记录抓轨前专辑目录里已有的文件名：结束时差集就是「本次新建」，
        // 取消时只删这些，绝不碰用户之前抓好的文件。
        Files.createDirectories(dir)
        // 列不出来就当「未知」（null）：以前 runCatching 吞成空集，等于「目录里原本什么都没有」，
        // 一旦权限/占用导致列举失败，取消清理就会把用户上一批抓好的音轨一起删掉。
        val beforeRun: Set<String>? = try {
            java.nio.file.Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() }
        } catch (e: Throwable) {
            Diag.log("cannot list output dir before run: $dir", e)
            warn(Strings.fmt("无法读取输出目录（清理时会保守处理）: %s", e.message ?: e.javaClass.simpleName))
            null
        }
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
            val collect = { src: com.doggybear233.saltaudiocopy.rip.TrackSource ->
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
            FreeSpace.usableBytes(dir), totalAll.toLong(), selected.size,
            settings.format, settings.imageMode
        )?.let {
            Diag.log("free space check failed: $it")
            error(it)
        }
        var doneBase = 0
        var bytesBase = 0L
        var cancelled = false
        val eta = com.doggybear233.saltaudiocopy.rip.EtaEstimator()

        // Test&Copy 的「测试」「复制」「重抓」三条通道必须用**完全相同**的读取参数：
        // 以前重抓漏了间隙参数，边界一挪音频就不同，CRC 永远对不上，用户看到的是
        // 「重抓后仍不一致」，其实是自己跟自己比错了位置。
        fun ripOnce(
            index: Int,
            target: Path,
            write: Boolean,
            resume: Boolean,
            onProg: (RipProgress) -> Unit
        ): RipResult {
            val e = toc.tracks[index]
            val nextIdx = toc.tracks.indexOfFirst { it.track == e.track + 1 }
            val gapAfterHere = if (preScanned.isEmpty() || nextIdx < 0) null
            else preScanned[toc.tracks[nextIdx].track]?.takeIf { it > 0 }
            return RipEngine.ripTrack(
                scan, index, target, cancel, onProg,
                verify = settings.secureMode,
                maxRetries = settings.maxRetries,
                retryDelayMs = settings.retryDelayMs,
                useC2 = settings.useC2,
                defeatCache = settings.defeatCache,
                writeFile = write,
                detectGaps = settings.detectGaps && preScanned.isEmpty(),
                knownGapSectors = preScanned[e.track],
                gapMethod = settings.gapMethod,
                // 下界用上一轨 INDEX 01：预间隙扇区属于上一轨范围，用「上一轨结束」会扫不到
                gapFloorLba = if (index > 0) toc.tracks[index - 1].lba else toc.sectorsOf(index).first,
                errorRecovery = settings.errorRecovery,
                gapMode = settings.gapMode,
                nextGapSectors = gapAfterHere,
                resume = resume,
                source = source,
                speedKbps = kbps
            )
        }

        // Test & Copy：先用「只读不写」的测试通道跑一遍，拿到每轨 CRC 作为对照
        val testCrc = HashMap<Int, Long>()
        if (settings.testAndCopy) {
            // 测试通道也要有进度：整盘测一遍可能要好几分钟，以前进度条一直停在 0，
            // 用户以为程序死了。这段进度算在「测试」名下，进入复制后重新从 0 开始。
            var testDoneBase = 0
            val testEta = com.doggybear233.saltaudiocopy.rip.EtaEstimator()
            for (i in selected) {
                if (cancel()) { cancelled = true; break }
                val entry = toc.tracks[i]
                onStage(Strings.fmt("测试读取第 %s 轨…", entry.track))
                // 测试通道失败不该毁掉整次抓轨：这一轨只是没有 CRC 参照，
                // 复制通道仍然照常跑（用户要的是文件，不是「因为测试读不出来就全放弃」）。
                val r = runCatching {
                    ripOnce(i, dir.resolve("__test__.tmp"), write = false, resume = false) { p ->
                        val nowMs = (System.nanoTime() - startedAt) / 1_000_000
                        val done = testDoneBase + p.sectorsDone
                        onProgress(
                            RipProgress(
                                done, totalAll, 0L, nowMs, p.errors,
                                testEta.sample(done, totalAll, nowMs)
                            )
                        )
                    }
                }.onFailure {
                    Diag.log("test read failed track=${entry.track}", it)
                    warn(Strings.fmt("第 %s 轨测试读取失败，本轨不做 Test&Copy 比对: %s", entry.track, it.message))
                }.getOrNull()
                if (r != null) {
                    testCrc[entry.track] = r.crc32
                    testDoneBase += r.sectors
                }
            }
            Diag.log("test&copy: 测试通道完成 ${testCrc.size} 轨")
            // 复制阶段自己的剩余时间从零估，别带上测试通道的速率
            eta.reset()
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
            // 同时看 .wav 和 .flac：FLAC 模式的产物是 .flac，只看 .wav 会把上一版
            // FLAC 判成「不存在同名文件」然后原地覆盖（与用户选的 suffix 策略相反）。
            val target = Overwrite.resolveAudio(
                dir, baseName, settings.overwritePolicy,
                resumeExpectedBytes = if (settings.resume) expectedData else null
            )
            if (target == null) {
                // 跳过是「用户需要知道」的结果：不写进 warnings 的话，完成提示只说「已抓完 N 轨」，
                // 用户会以为少的那几轨是抓失败了。
                warn(Strings.fmt("第 %s 轨目标文件已存在，按设置跳过（该轨没有抓）", entry.track))
                Diag.log("skip existing track=${entry.track} base=$baseName")
                // 跳过的轨也计入「已完成」，否则进度条永远到不了 100%、剩余时间越算越离谱。
                // 只加 doneBase 不发回调也不行：面板只在回调里刷新，进度条会一直停在上一轨。
                doneBase += sectors
                val skipMs = (System.nanoTime() - startedAt) / 1_000_000
                onProgress(
                    RipProgress(
                        doneBase, totalAll, bytesBase, skipMs, 0,
                        eta.sample(doneBase, totalAll, skipMs)
                    )
                )
                continue
            }
            val rel = target.fileName.toString()
            if (rel != baseName + ".wav") Diag.log("overwrite policy renamed '$baseName.wav' -> '$rel'")
            val outFile = target
            onStage(Strings.fmt("抓取第 %s 轨：%s", entry.track, title))
            var r = try {
                ripOnce(i, outFile, write = true, resume = settings.resume) { p ->
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
                }
            } catch (e: Throwable) {
                // 抓轨中途出错时不能只留一个半截文件：说清楚它是「可续抓的半截」还是
                // 「已删掉的废文件」，否则用户下次看到 0 字节/半长文件完全不知道怎么办。
                val size = runCatching { Files.size(outFile) }.getOrDefault(0L)
                val partial = size > 44L
                val keep = partial && settings.resume
                if (partial && !keep) runCatching { Files.deleteIfExists(outFile) }
                val what = when {
                    !partial -> Strings.message("没有留下不完整文件")
                    keep -> Strings.fmt("已保留半截文件（%s 字节），开着断点续抓，再抓一次会接着写", size - 44L)
                    else -> Strings.message("半截文件已删除（断点续抓没开）")
                }
                Diag.log("track ${entry.track} aborted: $what", e)
                throw IllegalStateException(
                    Strings.fmt("第 %s 轨抓取中断：%s；%s", entry.track, e.message ?: e.javaClass.simpleName, what),
                    e
                )
            }
            if (r.unstableChunks > 0) {
                warn(
                    Strings.fmt(
                        "第 %s 轨有 %s 个块读不稳（已用最后一次结果，可能有杂音）",
                        entry.track, r.unstableChunks
                    )
                )
            }
            if (r.droppedExisting) {
                warn(Strings.fmt("第 %s 轨的旧文件不是这一轨（续抓校验不符），已整轨重抓", entry.track))
            }
            if (r.finishFailed) {
                warn(Strings.fmt("第 %s 轨的 WAV 头部回填失败，文件可能打不开", entry.track))
            }
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
                    val again = ripOnce(i, outFile, write = true, resume = false) { }
                    // 文件内容现在是重抓的结果：后续的 CRC、峰值、写入标签都必须按它记账，
                    // 否则日志里的整盘 CRC 与实际音频对不上。
                    r = again
                    if (again.crc32 != expected) {
                        warn(Strings.fmt("第 %s 轨重抓后仍与测试通道不一致（0x%08X）", entry.track, again.crc32))
                    } else {
                        notes += Strings.fmt("第 %s 轨重抓后与测试通道一致", entry.track)
                    }
                }
            }
            if (settings.offsetSamples != 0 && !r.cancelled && !r.completedBefore) {
                runCatching { PcmOffset.apply(outFile, settings.offsetSamples) }
                    .onSuccess {
                        // 偏移把采样整体挪了几十个字节：文件内容变了，CRC 与峰值必须重算，
                        // 否则日志与封面/目录里的校验值对不上真实音频。
                        runCatching { wavStats(outFile) }
                            .onSuccess { (crc, peak) -> r = r.copy(crc32 = crc, peakLevel = peak) }
                            .onFailure { Diag.log("recompute crc after offset failed", it) }
                    }
                    .onFailure { Diag.log("offset shift failed on $outFile", it); warn(Strings.fmt("offset 校正失败: %s", it.message)) }
            } else if (r.completedBefore) {
                // 断点续抓命中「上一轮已经抓完」的文件：这次没读盘，peak 是 0，
                // 日志会把它写成 -inf dBFS（看起来像整轨静音）。按文件重算一遍统计值。
                runCatching { wavStats(outFile) }
                    .onSuccess { (crc, peak) -> r = r.copy(crc32 = crc, peakLevel = peak) }
                    .onFailure { Diag.log("recompute stats for completed file failed", it) }
            }
            // WAV 的 LIST/INFO 标签：资源管理器/播放器读它显示标题、艺术家、专辑
            //（反馈 6：抓下来的文件在资源管理器里没有标签）。追加在 data 之后，
            // 不影响 PCM 字节，因此也不影响 CRC 与编码器输入。
            // FLAC 模式且不保留 WAV 时，这个文件马上会被删掉：整文件重写标签纯属浪费
            //（几千万字节的读写），跳过即可。
            // 整盘镜像模式下逐轨文件只是拼接的中间产物，随后会被删掉：
            // 给它们打标签是白写一遍几十 MB，且镜像本身反而没有标签。
            val trackIsIntermediate = settings.imageMode && settings.gapMode.lowercase() == "append"
            val wavTags = mapOf(
                    "title" to title,
                    "artist" to (album?.track(entry.track)?.artist ?: artist),
                    "albumartist" to albumArtist,
                    "album" to albumTitle,
                    "date" to (album?.year?.toString() ?: ""),
                    "genre" to (album?.genre ?: ""),
                    "track" to "%d/%d".format(entry.track, availableTracks.size),
                    "disc" to album?.discNumber?.let { d ->
                        "%d/%d".format(d, album?.totalDiscs ?: 1)
                    }.orEmpty(),
                "comment" to "Salt Audio Copy $APP_VERSION"
            )
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
            // 按「文件是否真的留下」决定打不打 WAV 标签：
            // FLAC 成功且不保留 WAV → 文件已删，跳过；编码失败/保留 WAV/镜像中间产物 → 该文件还在。
            if (!r.cancelled && !trackIsIntermediate && java.nio.file.Files.exists(outFile)) {
                val why = com.doggybear233.saltaudiocopy.encode.WavTags.writeResult(outFile, wavTags)
                if (why != null) {
                    Diag.log("wav tag failed track=${entry.track}: $why")
                    warn(Strings.fmt("第 %s 轨 WAV 标签写入失败（音频不受影响）: %s", entry.track, why))
                }
            }
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

        // CUE / 日志 / 镜像都与音频同名：共用同一个后缀，不要让 CUE 去指上一张盘的文件。
        // null = 覆盖策略为 skip 且同名产物已存在 → 这一组附属文件一个都不写。
        val baseName = TemplateEngine.sanitizeSegment("$albumArtist - $albumTitle", 120)
        val artifactSuffix: String? = Overwrite.pickSuffix(
            dir, baseName, listOf(".cue", ".log", ".wav", ".flac"), settings.overwritePolicy
        )

        // 整盘镜像：把逐轨 WAV 拼成一个文件。
        // 逐轨抓取（secure 双读 / C2 / gap / 续抓）的能力全部保留，拼接只做字节搬运。
        var imageRel: String? = null
        val imageAllowed = settings.imageMode && settings.gapMode.lowercase() == "append"
        if (settings.imageMode && !imageAllowed) {
            warn(Strings.fmt(
                "间隙处理为 %s 时音轨边界不连续，已跳过整盘镜像（逐轨文件不受影响）",
                settings.gapMode
            ))
            Diag.log("image mode skipped: gapMode=${settings.gapMode}")
        }
        if (imageAllowed && !cancelled && results.isNotEmpty() && results.size == selected.size) {
            onStage(Strings.message("生成整盘镜像…"))
            val imgBase = TemplateEngine.sanitizeSegment("$albumArtist - $albumTitle", 120)
            val imgTarget = dir.resolve("$imgBase${artifactSuffix.orEmpty()}.wav")
            if (artifactSuffix == null) {
                notes += Strings.message("整盘镜像目标已存在，按设置跳过拼接（逐轨文件保留）")
            } else {
                runCatching {
                    val res = ImageSplicer.splice(results.map { it.file }, imgTarget) { cancel() }
                    if (res == null) {
                        // 拼接中途取消：`.part` 已由 splice 自己清掉，上一次的镜像原样保留。
                        throw ImageSplicer.SpliceCancelled()
                    }
                    Diag.log("image spliced: $imgTarget data=${res.dataBytes} sectors=${res.sectors} parts=${res.parts}")
                    // 中间产物先留着：等镜像确实转成 FLAC、标签也写好之后再删。
                    // 以前一拼完就删，随后 FLAC 编码或打标失败时用户只剩一个没标签的 WAV，
                    // 逐轨源文件已经找不回来了。
                    val parts = results.map { it.file }
                    for (k in results.indices) results[k] = results[k].copy(file = imgTarget)
                    imageRel = imgTarget.fileName.toString()
                    var finalImage: java.nio.file.Path = imgTarget
                    if (settings.format.equals("flac", true)) {
                        val flacTarget = imgTarget.resolveSibling(imgTarget.fileName.toString().removeSuffix(".wav") + ".flac")
                        runCatching {
                            val exe = FlacEncoder.locate()
                                ?: error("内置 FLAC 编码器不可用（插件包可能损坏，重新安装一次）")
                            val size = FlacEncoder.encode(exe, imgTarget, flacTarget, settings.flacLevel)
                            Diag.log("flac image -> $flacTarget ($size B)")
                            if (!settings.keepWav) runCatching { Files.deleteIfExists(imgTarget) }
                            imageRel = flacTarget.fileName.toString()
                            finalImage = flacTarget
                            // 结果里的 file 必须指向真实存在的文件：音频已转成 flac，
                            // WAV 也被删了，还指回 WAV 会让「打开目录/复制路径」指向空文件。
                            for (k in results.indices) results[k] = results[k].copy(file = flacTarget)
                        }.onFailure {
                            Diag.log("flac image encode failed", it)
                            warn(Strings.fmt("整盘镜像 FLAC 编码失败（已保留 WAV）: %s", it.message))
                        }
                    }
                    // 镜像才是用户真正留下的文件：逐轨的标签已跳过，这里必须补上，
                    // 否则整盘 rip 出来的文件在播放器里没有专辑名/艺术家。
                    runCatching { tagImage(finalImage, albumTitle, albumArtist, album) }
                        .onFailure {
                            Diag.log("image tag failed", it)
                            warn(Strings.fmt("整盘镜像标签写入失败（音频不受影响）: %s", it.message))
                        }
                    // 镜像（或其 FLAC）已经落盘：这时才删逐轨中间产物
                    if (Files.exists(finalImage)) {
                        parts.forEach { part -> runCatching { Files.deleteIfExists(part) } }
                    }
                }.onFailure {
                    if (it is ImageSplicer.SpliceCancelled) {
                        cancelled = true
                        Diag.log("image splice cancelled by user")
                        // 这里不碰面板状态：AlbumRipper 是纯逻辑层，取消由调用方统一呈现

                    } else {
                        Diag.log("image splice failed", it)
                        warn(Strings.fmt("整盘镜像拼接失败（逐轨文件保留）: %s", it.message))
                    }
                    // 镜像没成，逐轨 WAV 会留下来：它们此前按「中间产物」跳过了标签，
                    // 现在必须补上，否则保留下来的文件在播放器里没有专辑信息（R4-10）。
                    tagRetainedParts(results, album, albumArtist, albumTitle, availableTracks.size)
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
            // 封面也遵守覆盖策略：以前同名就原地覆盖，用户选「另存」却丢了上一张封面。
            val coverBase = TemplateEngine.sanitizeSegment(settings.coverFileName)
            val coverSuffix = Overwrite.pickSuffix(dir, coverBase, listOf(ext), settings.overwritePolicy)
            // 策略=skip 且同名封面已存在时 pickSuffix 返回 null：这时必须**不写**，
            // 以前的 `?: ""` 会退化成原地覆盖，正好是 skip 想避免的事。
            if (coverSuffix == null) {
                warn(Strings.fmt("封面已存在，按设置跳过：%s", dir.resolve(coverBase + ext).fileName))
                Diag.log("cover skipped by policy: $coverBase$ext")
            } else {
                val p = dir.resolve(coverBase + coverSuffix + ext)
                runCatching { Files.write(p, cover) }
                    .onSuccess { coverFile = p; Diag.log("cover written: $p (${cover.size} B)") }
                    .onFailure { Diag.log("cover write failed", it); warn(Strings.fmt("封面写入失败: %s", it.message)) }
            }
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

        var cueFile: Path? = null
        if (settings.writeCue && results.isNotEmpty() && !cancelled && artifactSuffix != null) {
            val p = dir.resolve("$baseName$artifactSuffix.cue")
            runCatching {
                val text = if (imageRel != null) {
                    // 起点必须取「实际拼进去的第一轨」：只抓部分音轨时 track 1 可能不在里面，
                    // 用整盘第一轨的 LBA 会让所有 INDEX 整体偏移。
                    CueWriter.buildImage(
                        album, results, imageRel!!, results.first().startLba,
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
        if (settings.writeLog && results.isNotEmpty() && artifactSuffix != null) {
            val p = dir.resolve("$baseName$artifactSuffix.log")
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
            // album.json 是「这个目录这张盘」的固定副本名（MetaOverride 按此名查找），
            // 不能加后缀，否则下次读不到刚存的手填信息。
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
            val errSuffix = Overwrite.pickSuffix(
                dir, baseName, listOf(".errors.txt"), settings.overwritePolicy
            ) ?: ""
            val p = dir.resolve("$baseName$errSuffix.errors.txt")
            runCatching {
                ErrReport.write(p, ErrReport.build("$albumArtist - $albumTitle", toc, results, notes))
                notes += Strings.fmt("错误报告: %s", p.fileName)
                Diag.log("error report written: $p")
            }.onFailure { Diag.log("error report failed", it) }
        }

        // 全部落盘之后才弹托盘：中途弹出会让后续轨道直接失败
        if (settings.ejectAfterRip && !cancelled && results.isNotEmpty()) {
            if (source == null) runCatching { CdDevice(scan.path).use { it.eject() } }
                .onSuccess { Diag.log("tray ejected") }
                .onFailure { Diag.log("eject failed", it); warn(Strings.fmt("弹出托盘失败（文件都已写好）: %s", it.message)) }
        }

        Diag.log(
            "album done: ${results.size}/${
                if (onlyTracks == null) toc.tracks.size else selected.size
            } tracks, cancelled=$cancelled, " +
                "elapsed=${elapsedMs}ms dir=$dir cue=$cueFile log=$logFile"
        )
        val created: List<java.nio.file.Path> = runCatching {
            java.nio.file.Files.list(dir).use { s ->
                // 只看普通文件：`.saltaudiocopy/` 是放续抓守卫的目录，取消清理时
                // 既删不掉（非空目录）也不该出现在「本次新建」清单里。
                val known = beforeRun
                if (known == null) emptyList()
                else s.filter { java.nio.file.Files.isRegularFile(it) && it.fileName.toString() !in known }.toList()
            }
        }.getOrDefault(emptyList())
        return AlbumRipResult(
            dir, album, results, elapsedMs, cancelled, cueFile, logFile, coverFile, notes, created,
            warnings
        )
    }

    /**
     * 整盘镜像的专辑级标签。
     *
     * 镜像模式（imageMode + gapMode=append）下逐轨文件是拼接用的中间产物、随后全部删除，
     * 逐轨标签一律跳过；最终留下的那一个镜像文件必须自己带专辑信息，
     * 否则整盘抓轨的产物在播放器里没有专辑名/艺术家，会被散成「未知专辑」。
     */
    /**
     * 镜像没拼成/被取消时，逐轨 WAV 会留在输出目录里。
     *
     * 抓轨循环按「整盘镜像模式」跳过了逐轨标签，所以这些文件必须在这里补写；
     * 只处理真实存在的 .wav（FLAC 已在编码时写入标签）。
     */
    internal fun tagRetainedParts(
        results: List<AlbumTrackResult>,
        album: AlbumMeta?,
        albumArtist: String,
        albumTitle: String,
        totalTracks: Int
    ) {
        for (r in results) {
            val f = r.file
            if (!Files.isRegularFile(f)) continue
            if (!f.fileName.toString().lowercase().endsWith(".wav")) continue
            val why = com.doggybear233.saltaudiocopy.encode.WavTags.writeResult(
                f,
                mapOf(
                    "title" to r.title,
                    "artist" to (album?.track(r.number)?.artist ?: r.artist ?: albumArtist),
                    "albumartist" to albumArtist,
                    "album" to albumTitle,
                    "date" to (album?.year?.toString() ?: ""),
                    "genre" to (album?.genre ?: ""),
                    "track" to "%d/%d".format(r.number, totalTracks),
                    "disc" to album?.discNumber?.let { d -> "%d/%d".format(d, album?.totalDiscs ?: 1) }.orEmpty(),
                    "comment" to "Salt Audio Copy $APP_VERSION"
                )
            )
            if (why != null) Diag.log("retag retained part failed ${f.fileName}: $why")
        }
    }

    private fun tagImage(file: java.nio.file.Path, title: String, artist: String, album: AlbumMeta?) {
        val name = file.fileName.toString().lowercase()
        if (name.endsWith(".flac")) {
            val tags = FlacMeta.trackTags(
                albumTitle = title,
                albumArtist = artist,
                year = album?.year,
                genre = album?.genre,
                trackNumber = 1,
                trackTitle = title,
                trackArtist = artist,
                isrc = null,
                trackTotal = 1,
                discNumber = album?.discNumber,
                totalDiscs = album?.totalDiscs,
                comment = "Salt Audio Copy $APP_VERSION"
            )
            FlacMeta.writeTags(file, tags, "Salt Audio Copy $APP_VERSION")
            Diag.log("flac image tags: ${tags.size}")
            return
        }
        val why = com.doggybear233.saltaudiocopy.encode.WavTags.writeResult(
            file,
            mapOf(
                "title" to title,
                "artist" to artist,
                "albumartist" to artist,
                "album" to title,
                "date" to (album?.year?.toString() ?: ""),
                "genre" to (album?.genre ?: ""),
                "track" to "1/1",
                "disc" to album?.discNumber?.let { d -> "%d/%d".format(d, album?.totalDiscs ?: 1) }.orEmpty(),
                "comment" to "Salt Audio Copy $APP_VERSION"
            )
        )
        if (why != null) error(why)
        Diag.log("wav image tags written")
    }

    private fun mmss(sectors: Int): String {
        val totalSec = sectors / 75
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /**
     * 按文件实际内容算 CRC32 与峰值（跳过 44 字节头）。
     *
     * offset 校正改写的是 PCM 字节，抓轨过程中累计的 CRC 说的是「校正前」的音频；
     * 日志与播放器看到的是校正后的文件，两者必须一致，所以这里重算一次。
     */
    internal fun wavStats(path: java.nio.file.Path): Pair<Long, Double> {
        val crc = java.util.zip.CRC32()
        var peak = 0.0
        // 只算 data 区：抓轨后还会追加 LIST/INFO 与 ID3 标签，
        // 那些字节不属于音频，混进来会让 CRC 与播放器/日志对不上。
        val limit = com.doggybear233.saltaudiocopy.rip.WavInfo.read(path)?.dataBytes
            ?: Long.MAX_VALUE
        java.nio.file.Files.newInputStream(path).use { ins ->
            var skip = 44L
            while (skip > 0) {
                val n = ins.skip(skip)
                if (n <= 0) break
                skip -= n
            }
            var left = limit
            val buf = ByteArray(1 shl 20)
            while (left > 0) {
                val want = minOf(buf.size.toLong(), left).toInt()
                val n = ins.read(buf, 0, want)
                if (n <= 0) break
                crc.update(buf, 0, n)
                peak = maxOf(peak, com.doggybear233.saltaudiocopy.rip.SecureRip.peakLevel(buf, n))
                left -= n
            }
        }
        return crc.value to peak
    }
}
