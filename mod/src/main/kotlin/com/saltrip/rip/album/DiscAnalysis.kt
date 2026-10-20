package com.saltrip.rip.album

import com.saltrip.rip.rip.TrackSource
import com.saltrip.rip.scsi.DiscToc
import com.saltrip.rip.scsi.GapDetector

/** 一轨的间隙信息。`startLba` 是该轨 INDEX 01 的绝对位置。 */
data class TrackGap(
    val number: Int,
    val startLba: Int,
    val sectors: Int,
    val gapSectors: Int
) {
    /** INDEX 00 的绝对位置（INDEX 01 往前推 gap）。 */
    val index00Lba: Int get() = (startLba - gapSectors).coerceAtLeast(0)
    val gapMsf: String get() = CueWriter.msf(gapSectors)
    val startMsf: String get() = CueWriter.msf(startLba)
    val lengthMsf: String get() = CueWriter.msf(sectors)
}

/**
 * 整盘间隙预扫（对应 EAC 的「检测间隙」预扫阶段）。
 *
 * 为什么值得先扫一遍：抓轨时每轨再扫一次等于把同一段 Q 子通道读两遍；
 * 预先算好再传给 [com.saltrip.rip.rip.RipEngine.ripTrack] 可以省掉一半 Q 读，
 * 而且用户能在开抓前就看到「这张盘有没有预间隙」。
 */
object DiscAnalysis {

    /**
     * 逐轨探测 INDEX 00。
     *
     * 每轨的下界是上一轨的结束位置，避免把上一轨的间隙算成本轨的。
     * 任何一轨读失败只让该轨 gap=0，不影响其它轨。
     */
    fun gapsFor(
        toc: DiscToc,
        source: TrackSource,
        maxScan: Int = 75,
        /** `q` = 子通道；`silence` = 波形静音 */
        gapMethod: String = "q",
        log: (String) -> Unit = {}
    ): List<TrackGap> {
        val entries = toc.tracks
        if (entries.isEmpty()) return emptyList()
        val out = ArrayList<TrackGap>(entries.size)
        var floor = 0 // 上一轨的 INDEX 01（不是上一轨结束位置，见下方注释）
        for (i in entries.indices) {
            val e = entries[i]
            val end = if (i + 1 < entries.size) entries[i + 1].lba else toc.leadOutLba
            val sectors = (end - e.lba).coerceAtLeast(0)
            // 下界取「上一轨 INDEX 01」：预间隙扇区位于上一轨的 TOC 范围内，
            // 若用上一轨结束位置（== 本轨起点）则扫描区间为空，永远探测不到间隙。
            val gap = runCatching {
                if (gapMethod.equals("silence", true)) {
                    val n = com.saltrip.rip.scsi.SilenceGapDetector.detect(e.lba, floor, maxScan) { lba ->
                        runCatching { source.readAudioSectors(lba, 1) }.getOrNull()
                    }
                    GapDetector.Gap(n, e.lba - n)
                } else {
                    GapDetector.detect(e.lba, floor, maxScan) { lba -> source.readQ(lba) }
                }
            }.onFailure { log("gap 探测失败 track=${e.track}: ${it.message}") }.getOrNull()
            out += TrackGap(e.track, e.lba, sectors, gap?.sectors ?: 0)
            floor = e.lba
        }
        return out
    }

    /** 面板/日志用的表格（等宽，便于直接贴进 issue）。 */
    fun describe(gaps: List<TrackGap>): String {
        if (gaps.isEmpty()) return "没有音轨"
        val sb = StringBuilder()
        sb.append("轨  INDEX 01     时长      间隙\n")
        gaps.forEach { g ->
            sb.append("%2d  %s  %s  %s%s\n".format(
                g.number, g.startMsf, g.lengthMsf, if (g.gapSectors > 0) "+" else " ", g.gapMsf
            ))
        }
        val total = gaps.sumOf { it.sectors.toLong() }
        val withGap = gaps.count { it.gapSectors > 0 }
        sb.append("共 %d 轨 / %s / %d 轨有预间隙".format(gaps.size, CueWriter.msf(total.toInt()), withGap))
        return sb.toString()
    }
}
