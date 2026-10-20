package com.doggybear233.saltaudiocopy.rip

/**
 * 抓轨剩余时间估算。
 *
 * 为什么不用「已用时间 / 已完成扇区 × 剩余扇区」：那是**累计平均**，只要中途遇到一张
 * 读取慢的轨（或 secure 模式某一轨疯狂重读），平均值就被拉高，剩余时间会**变大**，
 * 用户看到数字往回跳只会更焦虑。这里的做法是：
 *
 * 1. 只看**最近窗口**（默认 12 秒）的速度，慢轨的影响在 12 秒后自然消退；
 * 2. 速度用窗口首尾两点算（不是逐点平均），避免单次采样抖动被放大；
 * 3. 输出做非对称平滑：估算变小立即跟随（盘读顺了马上给好消息），
 *    变大只走一半（真变慢时数字缓慢爬，而不是一跳一截）；
 * 4. 样本不足（开头 2 秒 / 不到 32 扇区）返回 null，界面显示「正在估算…」，
 *    而不是给一个乱猜的数字。
 *
 * 纯计算、无 IO、可单测。
 */
class EtaEstimator(
    private val windowMs: Long = 12_000L,
    private val minWindowMs: Long = 1_500L,
    private val minSectors: Int = 32,
    private val alphaUp: Double = 0.5,
    private val alphaDown: Double = 1.0
) {
    private val times = ArrayDeque<Long>()
    private val dones = ArrayDeque<Int>()
    private var smoothed: Double? = null

    /** 换盘 / 重开抓轨时清空历史。 */
    fun reset() {
        times.clear()
        dones.clear()
        smoothed = null
    }

    /**
     * 记录一次进度，返回估算的剩余毫秒；样本不足返回 null（界面显示「正在估算…」）。
     *
     * @param doneSectors 已完成扇区（必须是**整盘累计**，不能是当前轨）
     * @param totalSectors 整盘总扇区
     * @param nowMs 从抓轨开始算起的毫秒
     */
    fun sample(doneSectors: Int, totalSectors: Int, nowMs: Long): Long? {
        if (totalSectors <= 0) return null
        if (doneSectors >= totalSectors) {
            smoothed = 0.0
            return 0L
        }
        if (doneSectors <= 0) return null

        // 单调性保护：同一时刻重复回调、或续抓回退，都不该污染速度
        val lastDone = dones.lastOrNull()
        if (lastDone != null && doneSectors < lastDone) reset()
        if (times.isNotEmpty() && nowMs < times.last()) reset()

        times.addLast(nowMs)
        dones.addLast(doneSectors)
        while (times.size > 2 && nowMs - times.first() > windowMs) {
            times.removeFirst()
            dones.removeFirst()
        }

        val span = nowMs - times.first()
        val moved = doneSectors - dones.first()
        if (span < minWindowMs || moved < minSectors) return smoothed?.toLong()

        val rate = moved.toDouble() / span.toDouble() // 扇区/毫秒
        if (rate <= 0.0) return smoothed?.toLong()
        val raw = (totalSectors - doneSectors).toDouble() / rate
        val prev = smoothed
        val next = if (prev == null) raw else {
            val a = if (raw < prev) alphaDown else alphaUp
            prev + (raw - prev) * a
        }
        smoothed = next
        return next.toLong()
    }
}
