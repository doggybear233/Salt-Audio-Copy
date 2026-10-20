package com.doggybear233.saltaudiocopy.scsi

import kotlin.math.abs
import kotlin.math.log10

/**
 * 静音法的音轨前间隙探测（对应 EAC 的 "Test Gaps On Silence"）。
 *
 * Q 子通道法（[GapDetector]）依赖盘上真的写了 INDEX 00。老盘、某些自刻盘、
 * 以及部分驱动器会把 INDEX 00 报成 INDEX 01，这时 Q 法得到 0，而波形上明明有一段静音。
 * 静音法不看索引，只看波形：从音轨起点往前逐扇区看峰值，连续低于阈值就当作间隙。
 *
 * 两者是互补的，不是替代关系：Q 法知道「作者认为间隙在哪」，静音法知道「实际哪里没声音」。
 * 阈值默认 -60 dBFS（EAC 的经验值级别），可调。
 */
object SilenceGapDetector {

    /** 默认最多往前扫 75 扇区（1 秒），与 Q 法一致。 */
    const val DEFAULT_MAX_SCAN = 75

    /** 默认阈值：-60 dBFS。 */
    const val DEFAULT_THRESHOLD_DBFS = -60.0

    /** 16 位有符号样本满量程。 */
    private const val FULL_SCALE = 32768.0

    /**
     * 一扇区 2352 字节 PCM 的峰值，换算成 dBFS；全零返回 [Double.NEGATIVE_INFINITY]。
     */
    fun peakDbfs(pcm: ByteArray): Double {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            // 小端 16 位有符号
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val a = abs(s)
            if (a > peak) peak = a
            i += 2
        }
        if (peak == 0) return Double.NEGATIVE_INFINITY
        return 20.0 * log10(peak / FULL_SCALE)
    }

    /**
     * 从 [trackStart] 往前扫，统计连续「静音」的扇区数。
     *
     * @param readSector 读一个扇区的 PCM；返回 null 表示读失败（立即停）
     * @param thresholdDbfs 低于等于该峰值视为静音
     * @return 静音扇区数（0 表示音轨前不是静音，或第一扇区就失败）
     */
    fun detect(
        trackStart: Int,
        lowerBound: Int,
        maxScan: Int = DEFAULT_MAX_SCAN,
        thresholdDbfs: Double = DEFAULT_THRESHOLD_DBFS,
        readSector: (Int) -> ByteArray?
    ): Int {
        if (maxScan <= 0) return 0
        val floor = maxOf(lowerBound, trackStart - maxScan)
        var count = 0
        var lba = trackStart - 1
        while (lba >= floor) {
            val pcm = runCatching { readSector(lba) }.getOrNull() ?: break
            if (pcm.isEmpty()) break
            if (peakDbfs(pcm) > thresholdDbfs) break
            count++
            lba--
        }
        return count
    }
}
