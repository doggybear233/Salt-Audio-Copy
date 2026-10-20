package com.saltrip.rip.rip

/**
 * EAC 式安全读取策略。
 *
 * EAC 的 secure 模式不是「固定重读 N 次」，而是「连续读到足够多的一致结果才接受」：
 * 官方文档的说法是 "until eight of 16 retries are identical"，即同一块数据要被独立读到
 * 多次完全相同才认为可信；出错恢复质量 Low/Medium/High 决定要求多少次一致
 * （对应界面里的 1 / 3 / 5 轮），最坏情况一块数据会被读 82 次。
 *
 * 这里把策略与读取动作解耦：[readStable] 只吃一个「读一次」的 lambda，
 * 因此可以脱离光驱做单元测试。
 */
object SecureRip {

    /** EAC 的最坏读取次数上限。 */
    const val MAX_TOTAL_READS = 82

    /**
     * @param identicalNeeded 需要连续多少次一致才接受（1=不校验，3=中等，5=高）
     * @param maxReads 这一块最多读几次
     */
    data class Policy(val identicalNeeded: Int, val maxReads: Int) {
        val verifies: Boolean get() = identicalNeeded > 1
    }

    /** 把设置里的 errorRecovery（low/medium/high）翻译成策略。 */
    fun policyFor(level: String, maxRetries: Int = 8): Policy = when (level.trim().lowercase()) {
        "low" -> Policy(1, 1)
        "high" -> Policy(5, MAX_TOTAL_READS)
        else -> Policy(3, (maxRetries + 1).coerceIn(2, MAX_TOTAL_READS))
    }

    /**
     * @param data 最终采用的数据（长度等于最后一次读到的字节数）
     * @param reads 实际读了几次
     * @param identical 结束时连续一致次数
     * @param stable 是否达到策略要求（false 表示读满次数仍不稳定，数据保留但可疑）
     */
    data class Chunk(
        val data: ByteArray,
        val reads: Int,
        val identical: Int,
        val stable: Boolean
    )

    /**
     * 反复读取直到「连续 [Policy.identicalNeeded] 次结果完全相同」或读满 [Policy.maxReads]。
     *
     * 不一致时以最新一次读取为新基准继续比对（EAC 的同一逻辑），并把 [onRetry] 计一次。
     */
    fun readStable(
        policy: Policy,
        read: () -> ByteArray,
        onRetry: () -> Unit = {},
        log: (String) -> Unit = {}
    ): Chunk {
        var baseline = read()
        var reads = 1
        var identical = 1
        if (!policy.verifies) return Chunk(baseline, reads, identical, true)

        while (reads < policy.maxReads && identical < policy.identicalNeeded) {
            val next = read()
            reads++
            if (next.contentEquals(baseline)) {
                identical++
            } else {
                onRetry()
                log("verify mismatch reads=$reads，以最新读取为新基准")
                baseline = next
                identical = 1
            }
        }
        val stable = identical >= policy.identicalNeeded
        if (!stable) log("verify gave up reads=$reads identical=$identical（保留最后一次读取）")
        return Chunk(baseline, reads, identical, stable)
    }

    /** 16 位小端 PCM 的峰值电平，0.0–1.0（1.0 = 满刻度 0 dBFS）。 */
    fun peakLevel(pcm: ByteArray, len: Int): Double {
        var peak = 0
        var i = 0
        val end = minOf(len, pcm.size) - 1
        while (i < end) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val v = ((hi shl 8) or lo).toShort().toInt()
            val abs = if (v == Short.MIN_VALUE.toInt()) 32768 else kotlin.math.abs(v)
            if (abs > peak) peak = abs
            i += 2
        }
        return peak / 32768.0
    }

    /** dBFS 文本，静音时返回 "-inf"。 */
    fun peakDb(level: Double): String =
        if (level <= 0.0) "-inf" else "%.2f".format(20.0 * kotlin.math.log10(level))
}
