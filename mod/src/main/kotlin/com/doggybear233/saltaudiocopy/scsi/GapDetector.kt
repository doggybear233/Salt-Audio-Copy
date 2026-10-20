package com.doggybear233.saltaudiocopy.scsi

/**
 * 音轨前间隙（INDEX 00）探测。
 *
 * EAC 的 gap 检测靠读子通道 Q：音轨正式数据从 Q.index==1 开始，若之前还有一段 Q.index==0
 * 的扇区，那段就是预间隙（INDEX 00）。压制盘常见"无间隙"（INDEX 00 与 INDEX 01 重合），
 * 有些老盘/现场盘会有几秒的间隙。
 *
 * 做法：从音轨起始扇区往前逐扇区读 Q，统计紧邻起始扇区之前连续 index==0 的扇区数。
 * 只扫 [maxScan] 个扇区就停，避免病态盘把抓轨卡住。
 */
object GapDetector {

    /** 默认最多往前扫 75 扇区（1 秒）。 */
    const val DEFAULT_MAX_SCAN = 75

    /** 探测结果。 */
    data class Gap(
        /** INDEX 00 扇区数（0 表示无预间隙）。 */
        val sectors: Int,
        /** INDEX 00 的起始 LBA（无间隙时等于音轨起始 LBA）。 */
        val startLba: Int
    ) {
        val msf: String
            get() {
                val v = sectors
                return "%02d:%02d:%02d".format(v / 4500, (v % 4500) / 75, v % 75)
            }
    }

    /**
     * @param trackStart 音轨 INDEX 01 的 LBA
     * @param lowerBound 向前扫描的下界（通常是上一轨的结束扇区），防止跨进上一轨
     * @param readQ 读单个扇区 Q 的函数（真机传 `dev::readQ`，测试可注入假数据）
     */
    fun detect(
        trackStart: Int,
        lowerBound: Int,
        maxScan: Int = DEFAULT_MAX_SCAN,
        readQ: (Int) -> Subchannel.Q
    ): Gap {
        var count = 0
        val floor = maxOf(lowerBound, trackStart - maxScan)
        var lba = trackStart - 1
        while (lba >= floor) {
            val q = runCatching { readQ(lba) }.getOrNull() ?: break
            if (!q.crcValid) break
            if (q.index != 0) break
            count++
            lba--
        }
        return Gap(count, trackStart - count)
    }
}
