package com.doggybear233.saltaudiocopy.rip

import com.doggybear233.saltaudiocopy.Diag
import com.doggybear233.saltaudiocopy.scsi.C2
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.scsi.GapDetector
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.InquiryData
import com.doggybear233.saltaudiocopy.scsi.ScsiException
import com.doggybear233.saltaudiocopy.scsi.SectorBuffer
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

data class ScanResult(val path: String, val inquiry: InquiryData, val toc: DiscToc)

data class RipProgress(
    val sectorsDone: Int,
    val sectorsTotal: Int,
    val bytes: Long,
    val elapsedMs: Long,
    val errors: Int,
    /**
     * 由 [EtaEstimator] 用「最近窗口速度」算好的剩余毫秒。
     * 整盘流程会填；单轨流程留空则退回累计平均（单轨场景两者等价）。
     */
    val etaOverrideMs: Long? = null
) {
    val fraction: Float get() = if (sectorsTotal <= 0) 0f else sectorsDone.toFloat() / sectorsTotal
    val speedX: Double
        get() {
            if (elapsedMs <= 0) return 0.0
            val bps = bytes * 1000.0 / elapsedMs
            return bps / (44100.0 * 2 * 2)
        }

    /** 剩余毫秒；`-1` 表示「还算不出来」（样本不足），界面显示「正在估算…」。 */
    val etaMs: Long
        get() {
            etaOverrideMs?.let { return it }
            if (sectorsDone <= 0 || elapsedMs <= 0) return -1
            val perSector = elapsedMs.toDouble() / sectorsDone
            return ((sectorsTotal - sectorsDone) * perSector).toLong()
        }
}

data class RipResult(
    val track: Int,
    val outFile: Path,
    val sectors: Int,
    val bytes: Long,
    val crc32: Long,
    val elapsedMs: Long,
    val cancelled: Boolean,
    val readErrors: Int,
    val gapSectors: Int = 0,
    val peakLevel: Double = 0.0,
    /** 因校验不一致而重读过的扇区区间（诊断用，最多保留 64 段） */
    val retried: List<IntRange> = emptyList(),
    /** 用尽重试仍未达到一致读的块数（>0 说明这轨的音频不是 100% 可信） */
    val unstableChunks: Int = 0
)

/**
 * 抓轨引擎：同步实现，线程模型交给调用方。
 *
 * 读策略（对应 EAC 的 secure 基础）：
 * - 每块读一次；`verify=true` 时再读一次比对，不一致就重读，直到两次一致或用尽重试
 * - 失败重试之间有延迟，避免驱动器连续报错
 * - 介质类硬错误（CIRC/L-EC、非法模式）立即抛出，不做无意义重试
 */
object RipEngine {

    /**
     * 读一次盘。
     *
     * [log] 只在「真的需要记录」时开：换盘监视每 2 秒调一次，
     * 每次都写一行会把日志刷成扫描流水（上次排查元数据问题时 13 KB 里九成是这个）。
     */
    fun scan(devicePath: String, log: Boolean = false): ScanResult {
        CdDevice(devicePath).use { dev ->
            val inq = dev.inquiry()
            val toc = dev.readToc()
            if (log) {
                Diag.log("scan: $devicePath -> $inq, ${toc.tracks.size} tracks, leadOut=${toc.leadOutLba}, audioCd=${toc.isAudioCd}")
            }
            return ScanResult(devicePath, inq, toc)
        }
    }

    /**
     * 按设置里的盘符选定驱动器。
     * 指定了盘符却找不到时**报错而不是悄悄换一台**——多光驱环境下静默换盘会让整张专辑写到错的目录。
     */
    fun selectScan(preferred: String, current: ScanResult): ScanResult {
        if (preferred.isBlank()) return current
        val want = com.doggybear233.saltaudiocopy.album.DriveSelect.normalize(preferred)
        if (com.doggybear233.saltaudiocopy.album.DriveSelect.normalize(current.path) == want) return current
        val candidates = CdDevice.scanDrives().mapNotNull { (path, _) ->
            runCatching { scan(path) }.getOrNull()
        }
        return com.doggybear233.saltaudiocopy.album.DriveSelect.pick(candidates, preferred)
            ?: throw IllegalStateException(
                "设置里指定了光驱 ${com.doggybear233.saltaudiocopy.album.DriveSelect.describe(preferred)}，" +
                    "但没找到可用且可读的驱动器（已发现: ${candidates.joinToString { it.path }}）"
            )
    }

    /** 抓轨前的介质检查：数据盘/混合模式盘要给出能看懂的原因，而不是一个 SCSI 状态码。 */
    fun requireAudioCd(toc: DiscToc) {
        if (toc.tracks.isEmpty()) {
            throw IllegalStateException("没有读到任何音轨：驱动器里可能没有光盘，或光盘尚未就绪。")
        }
        if (!toc.isAudioCd) {
            val dataTracks = toc.tracks.filter { !it.isAudio }.map { it.track }
            throw IllegalStateException(
                "这张盘不是音频 CD（数据轨: ${dataTracks.joinToString()}）。" +
                    "EAC 类抓轨只能处理 CD-DA，请换一张音乐 CD。"
            )
        }
    }

    fun ripTrack(
        scan: ScanResult,
        trackIndex: Int,
        outFile: Path,
        cancel: () -> Boolean,
        onProgress: (RipProgress) -> Unit,
        verify: Boolean = false,
        maxRetries: Int = 8,
        retryDelayMs: Int = 120,
        useC2: Boolean = false,
        writeFile: Boolean = true,
        /**
         * 击破驱动器读缓存：双读校验时，两次「相同」的读之间插一次远端扇区读，
         * 强迫驱动器真的回盘取数据。对应 EAC 的 "Clear cache" 思路。
         */
        defeatCache: Boolean = false,
        detectGaps: Boolean = false,
        gapFloorLba: Int = -1,
        errorRecovery: String = "medium",
        /** append（默认，范围本身已含后置间隙）/ prepend / leaveout */
        gapMode: String = "append",
        /** 下一轨前置间隙扇区数；leaveout 需要它来裁掉尾部间隙 */
        nextGapSectors: Int? = null,
        resume: Boolean = false,
        /** 读速上限（KB/s）；每条读句柄打开后都会重新下发一次，0 = 不下发。 */
        speedKbps: Int = 0,
        source: TrackSource? = null,
        /** 外部预扫得到的 INDEX 00 扇区数；给了就不再重复扫 Q 子通道。 */
        knownGapSectors: Int? = null,
        /** 间隙探测方法：`q` = 子通道 Q（EAC 默认），`silence` = 静音波形（Test Gaps On Silence） */
        gapMethod: String = "q"
    ): RipResult {
        val toc = scan.toc
        requireAudioCd(toc)
        require(trackIndex in toc.tracks.indices) { "trackIndex=$trackIndex 越界" }
        var (startLba, total) = toc.sectorsOf(trackIndex)
        val trackNo = toc.tracks[trackIndex].track
        // 只在真的要写文件时碰文件系统：Test only（writeFile=false）传进来的路径
        // 可能没有父目录（例如 `__test_only__.tmp`），无条件 createDirectories 会 NPE。
        if (writeFile) outFile.parent?.let { Files.createDirectories(it) }

        val crc = CRC32()
        var done = 0
        var retries = 0
        var unstableChunks = 0
        val retried = ArrayList<IntRange>()
        var c2Active = useC2
        var c2Errors = 0
        val t0 = System.nanoTime()
        var cancelled = false
        var peak = 0.0

        var gapSectors = 0
        var detectedGap = 0
        val ownSource = source == null
        val dev: TrackSource = source ?: CdTrackSource(CdDevice(scan.path))
        // 每次开新句柄都重设一次读速：驱动器在句柄关闭后往往恢复默认速度。
        if (speedKbps > 0) {
            runCatching { dev.setSpeed(speedKbps) }
                .onFailure { Diag.log("set speed on read handle failed track=$trackNo", it) }
        }
        // 缓存击破的「远端扇区」：离当前块足够远，能把刚读过的扇区挤出驱动器缓存。
        val span = toc.leadOutLba.coerceAtLeast(1)
        val flush: (() -> Unit)? = if (defeatCache && verify) {
            {
                val at = startLba + done
                val target = (if (at > 4000) at - 4000 else at + 4000).coerceIn(0, span - 1)
                runCatching { dev.readAudioSectors(target, 1) }
                    .onFailure { Diag.log("cache flush read failed lba=$target", it) }
            }
        } else null
        try {
            if (knownGapSectors != null && knownGapSectors > 0) {
                detectedGap = knownGapSectors
                Diag.log("gap track=$trackNo index00=$detectedGap sectors (预扫结果)")
            } else if (detectGaps) {
                val floor = if (gapFloorLba >= 0) gapFloorLba else startLba
                if (gapMethod.equals("silence", true)) {
                    val n = runCatching {
                        com.doggybear233.saltaudiocopy.scsi.SilenceGapDetector.detect(startLba, floor) { lba ->
                            runCatching { dev.readAudioSectors(lba, 1) }.getOrNull()
                        }
                    }.onFailure { Diag.log("silence gap detect failed lba=$startLba", it) }
                        .getOrDefault(0)
                    if (n > 0) {
                        detectedGap = n
                        Diag.log("gap track=$trackNo silence=$n sectors (波形静音法)")
                    }
                } else {
                    val g = runCatching { GapDetector.detect(startLba, floor) { lba -> dev.readQ(lba) } }
                        .onFailure { Diag.log("gap detect failed lba=$startLba", it) }
                        .getOrNull()
                    if (g != null && g.sectors > 0) {
                        detectedGap = g.sectors
                        Diag.log("gap track=$trackNo index00=${g.sectors} sectors (${g.msf}) startLba=${g.startLba}")
                    }
                }
            }
            // 间隙归属决定音轨边界：对应 EAC 的三种 gap 处理方式
            when (gapMode.lowercase()) {
                "prepend" -> if (detectedGap > 0) {
                    gapSectors = detectedGap
                    startLba -= detectedGap
                    total += detectedGap
                    Diag.log("gap track=$trackNo 归入本轨：新起点=$startLba 扇区数=$total")
                }

                "leaveout" -> {
                    val trim = nextGapSectors ?: 0
                    if (trim in 1 until total) {
                        total -= trim
                        Diag.log("gap track=$trackNo 丢弃尾部间隙 $trim 扇区：扇区数=$total")
                    } else {
                        Diag.log("gap track=$trackNo leaveout：下一轨间隙未知，按附加到上一轨处理")
                    }
                }

                else -> {} // append：toc 范围本身到下一轨 INDEX 01，尾部间隙已在其中
            }

            val wav: WavSink = if (writeFile) WavWriter(outFile, append = resume) else NullWavSink
            wav.use {
                if (writeFile && resume) {
                    val prior = (wav as? WavWriter)?.priorDataBytes ?: 0L
                    val already = (prior / 2352L).toInt()
                    when {
                        // 同名文件里已经是一整轨（上次抓完，或覆盖策略让本次复用了同名文件）：
                        // append 模式从文件尾继续写会变成「两遍音频」，必须先清空重抓。
                        total > 0 && already >= total -> {
                            (wav as WavWriter).truncateData(0)
                            done = 0
                            crc.reset()
                            Diag.log("resume track=$trackNo 旧文件已完整（$prior 字节 / $total 扇区），清空重抓")
                        }

                        already in 1 until total -> {
                            (wav as WavWriter).truncateData(already * 2352L)
                            done = already
                            crc.reset()
                            crcFile(crc, outFile, already * 2352L)
                            Diag.log("resume track=$trackNo 从 $done/$total 扇区续抓（已有 $prior 字节）")
                        }
                    }
                }
                // 有些驱动器不接受 27 扇区的 READ CD：首块就回 ILLEGAL REQUEST 时退回 16，
                // 不然整张盘会因为「读得太快」而抓不动。
                var chunk = dev.maxSectorsPerRequest
                SectorBuffer(chunk).use { first ->
                    SectorBuffer(chunk).use { second ->
                        val a = ByteArray(first.size)
                        val b = ByteArray(second.size)
                        while (done < total) {
                            if (cancel()) { cancelled = true; break }
                            val n = minOf(chunk, total - done)
                            val retriesBefore = retries
                            val len = try {
                                readCompare(
                                    dev, first.segment, second.segment, startLba + done, n,
                                    a, b, verify, maxRetries, retryDelayMs,
                                    onRetry = { retries++ },
                                    policy = SecureRip.policyFor(errorRecovery, maxRetries),
                                    flush = flush,
                                    onUnstable = { unstableChunks++ }
                                )
                            } catch (e: com.doggybear233.saltaudiocopy.scsi.ScsiException) {
                                if (chunk > 16 && done == 0) {
                                    Diag.log("read chunk $chunk rejected, falling back to 16: ${e.message}")
                                    chunk = 16
                                    continue
                                }
                                throw e
                            }
                            if (retries > retriesBefore && retried.size < 64) {
                                retried += (startLba + done) until (startLba + done + n)
                            }
                            peak = maxOf(peak, SecureRip.peakLevel(a, len))
                            if (c2Active) {
                                val c2 = checkC2(
                                    dev, startLba + done, n, maxRetries, retryDelayMs,
                                    onRetry = { retries++ },
                                    onUnsupported = { c2Active = false }
                                )
                                if (c2 > 0) c2Errors += c2
                            }
                            wav.write(a, 0, len)
                            crc.update(a, 0, len)
                            done += n
                            onProgress(
                                RipProgress(done, total, wav.dataByteCount(), (System.nanoTime() - t0) / 1_000_000, retries)
                            )
                        }
                    }
                }
            }
        } finally {
            if (ownSource) runCatching { dev.close() }
        }

        val elapsed = (System.nanoTime() - t0) / 1_000_000
        val result = RipResult(
            trackNo, outFile, done, done.toLong() * 2352, crc.value, elapsed, cancelled, retries,
            gapSectors = gapSectors, peakLevel = peak, retried = retried.toList(),
            unstableChunks = unstableChunks
        )
        Diag.log(
            "rip track=$trackNo sectors=$done/$total crc32=0x${java.lang.Long.toHexString(crc.value)} " +
                "test=${!writeFile} gap=$gapSectors peak=${SecureRip.peakDb(peak)}dBFS " +
                "retries=$retries unstable=$unstableChunks verify=$verify " +
                "c2=${if (useC2) "on($c2Errors 扇区报告)" else "off"} " +
                "elapsed=${elapsed}ms cancelled=$cancelled -> $outFile"
        )
        return result
    }

    /**
     * 用 C2 位重读同一块，返回报告 C2 错误的扇区数。
     *
     * 只做「有没有错」判断：有错就整块重读（C2 指针纠错不在本实现范围内，EAC 同样靠重读）。
     * 驱动器不支持 C2（抛 SCSI 错误）时回调 [onUnsupported] 并返回 0，之后本次抓轨不再尝试。
     */
    internal fun checkC2(
        dev: TrackSource,
        lba: Int,
        n: Int,
        maxRetries: Int,
        retryDelayMs: Int,
        onRetry: () -> Unit = {},
        onUnsupported: () -> Unit = {}
    ): Int {
        var attempt = 0
        while (true) {
            val buf = try {
                dev.readAudioSectorsC2(lba, n)
            } catch (e: ScsiException) {
                Diag.log("c2 unsupported lba=$lba n=$n: ${e.message}")
                onUnsupported()
                return 0
            } catch (e: Exception) {
                // C2 是加速/诊断手段，不是正确性前提：任何异常都只降级，绝不中断抓轨。
                Diag.log("c2 failed lba=$lba n=$n，已关闭 C2", e)
                onUnsupported()
                return 0
            }
            val errors = C2.sectorErrorBytes(buf, n)
            val bad = errors.count { it > 0 }
            if (bad == 0) return 0
            if (attempt >= maxRetries) {
                Diag.log("c2 gave up lba=$lba n=$n after $maxRetries retries（$bad 扇区仍报告错误）")
                return bad
            }
            attempt++
            onRetry()
            Diag.log("c2 retry lba=$lba n=$n attempt=$attempt bad=$bad")
            if (retryDelayMs > 0) Thread.sleep(retryDelayMs.toLong())
        }
    }

    /**
     * 读一块并写入 [a]，返回有效字节数。
     * `verify` 时再读一块到 [b] 比对；不一致则重读，累计到 [maxRetries] 后仍不一致就用最后一次结果。
     */
    internal fun readCompare(
        dev: TrackSource,
        seg1: MemorySegment,
        seg2: MemorySegment,
        lba: Int,
        n: Int,
        a: ByteArray,
        b: ByteArray,
        verify: Boolean,
        maxRetries: Int,
        retryDelayMs: Int,
        onRetry: () -> Unit,
        policy: SecureRip.Policy = SecureRip.policyFor("medium", maxRetries),
        /** 每次「重读同一块」之前调用，用于击破驱动器缓存。 */
        flush: (() -> Unit)? = null,
        /** 重试次数用尽仍未达到一致读时调用（块级，用于把「不稳定的音频」暴露给用户）。 */
        onUnstable: () -> Unit = {}
    ): Int {
        val effective = if (!verify) SecureRip.Policy(1, 1) else policy
        var alreadyRead = 0
        val chunk = SecureRip.readStable(
            effective,
            read = {
                if (flush != null && alreadyRead > 0) flush()
                alreadyRead++
                readOnce(dev, lba, n)
            },
            onRetry = {
                onRetry()
                if (retryDelayMs > 0) Thread.sleep(retryDelayMs.toLong())
            },
            log = { Diag.log("$it lba=$lba n=$n") }
        )
        if (!chunk.stable) onUnstable()
        val len = minOf(chunk.data.size, a.size)
        System.arraycopy(chunk.data, 0, a, 0, len)
        if (len > 0) MemorySegment.copy(a, 0, seg1, ValueLayout.JAVA_BYTE, 0L, len)
        Diag.log(
            "read lba=$lba n=$n reads=${chunk.reads} identical=${chunk.identical} " +
                "stable=${chunk.stable} bytes=$len"
        )
        return len
    }

    /** 把已写入 WAV 的数据重新灌进 CRC（续抓时保证总 CRC 正确）。 */
    private fun crcFile(crc: CRC32, path: Path, dataBytes: Long) {
        runCatching {
            Files.newInputStream(path).use { ins ->
                var skipped = 0L
                while (skipped < 44L) {
                    val n = ins.skip(44L - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                val buf = ByteArray(1 shl 20)
                var left = dataBytes
                while (left > 0) {
                    val want = minOf(buf.size.toLong(), left).toInt()
                    val n = ins.read(buf, 0, want)
                    if (n <= 0) break
                    crc.update(buf, 0, n)
                    left -= n
                }
            }
        }.onFailure { Diag.log("crcFile failed on $path", it) }
    }

    /** 单次读取，带最多 3 次 IO 级重试（错误介质时硬失败立即抛出）。 */
    private fun readOnce(dev: TrackSource, lba: Int, n: Int): ByteArray {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                return dev.readAudioSectors(lba, n)
            } catch (e: Exception) {
                lastError = e
                if (e is ScsiException && e.isHardFailure) throw e
                Diag.log("read fail lba=$lba n=$n attempt=$attempt", e)
                Thread.sleep(80L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("read failed lba=$lba")
    }

    private fun readInto(dev: TrackSource, seg: MemorySegment, lba: Int, n: Int): Int {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                return dev.readAudioSectorsInto(lba, n, seg)
            } catch (e: Exception) {
                lastError = e
                if (e is ScsiException && e.isHardFailure) throw e
                Diag.log("read fail lba=$lba n=$n attempt=$attempt", e)
                Thread.sleep(80L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("read failed lba=$lba")
    }

    /**
     * 整盘抓轨：逐轨写 `NN.wav`，进度按全盘扇区数归一化。
     * 供旧入口使用；新流程走 [com.doggybear233.saltaudiocopy.album.AlbumRipper]。
     */
    fun ripDisc(
        scan: ScanResult,
        outDir: Path,
        cancel: () -> Boolean,
        onProgress: (RipProgress) -> Unit,
        verify: Boolean = false,
        maxRetries: Int = 8,
        retryDelayMs: Int = 120
    ): DiscRipResult {
        val toc = scan.toc
        requireAudioCd(toc)
        Files.createDirectories(outDir)
        val t0 = System.nanoTime()
        val totalAll = toc.tracks.indices.sumOf { toc.sectorsOf(it).second }
        val results = ArrayList<RipResult>(toc.tracks.size)
        var doneBase = 0
        var bytesBase = 0L
        var cancelled = false
        val eta = EtaEstimator()

        for (i in toc.tracks.indices) {
            if (cancel()) { cancelled = true; break }
            val trackNo = toc.tracks[i].track
            val out = outDir.resolve(String.format("%02d.wav", trackNo))
            val r = ripTrack(scan, i, out, cancel, { p ->
                val nowMs = (System.nanoTime() - t0) / 1_000_000
                val done = doneBase + p.sectorsDone
                onProgress(
                    RipProgress(
                        done, totalAll, bytesBase + p.bytes, nowMs, p.errors,
                        eta.sample(done, totalAll, nowMs)
                    )
                )
            }, verify, maxRetries, retryDelayMs)
            results += r
            if (r.cancelled) { cancelled = true; break }
            doneBase += toc.sectorsOf(i).second
            bytesBase += r.bytes
        }

        val elapsed = (System.nanoTime() - t0) / 1_000_000
        Diag.log(
            "disc rip done: ${results.size}/${toc.tracks.size} tracks, cancelled=$cancelled, " +
                "elapsed=${elapsed}ms -> $outDir"
        )
        return DiscRipResult(outDir, results, elapsed, cancelled)
    }
}

data class DiscRipResult(
    val outDir: Path,
    val tracks: List<RipResult>,
    val elapsedMs: Long,
    val cancelled: Boolean
) {
    val totalSectors: Int get() = tracks.sumOf { it.sectors }
    val totalErrors: Int get() = tracks.sumOf { it.readErrors }
}
