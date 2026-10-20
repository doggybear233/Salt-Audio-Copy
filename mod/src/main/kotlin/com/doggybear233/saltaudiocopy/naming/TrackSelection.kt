package com.doggybear233.saltaudiocopy.naming

/**
 * 「只抓某些轨」的规格解析：`1-5,8,10-` 这类 EAC/foobar 风格的写法。
 *
 * 空串表示「全部」（返回 null，调用方据此走默认路径）。越界/非法片段一律忽略，
 * 而不是整体报错——用户手打 `1-3, ,5` 这种多余逗号很常见，为此中断抓轨不值得。
 */
object TrackSelection {

    /** @return 选中的轨号集合；规格为空时返回 null（= 全部）。 */
    fun parse(spec: String, available: Set<Int>): Set<Int>? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        val out = LinkedHashSet<Int>()
        for (part in s.split(',', ';', ' ')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val dash = p.indexOf('-')
            if (dash < 0) {
                p.toIntOrNull()?.let { if (it in available) out += it }
                continue
            }
            val lo = p.substring(0, dash).trim().toIntOrNull()
            val hiRaw = p.substring(dash + 1).trim()
            val hi = if (hiRaw.isEmpty()) available.maxOrNull() else hiRaw.toIntOrNull()
            if (lo == null || hi == null || lo > hi) continue
            for (n in maxOf(lo, available.minOrNull() ?: lo)..minOf(hi, available.maxOrNull() ?: hi)) {
                if (n in available) out += n
            }
        }
        return if (out.isEmpty()) emptySet() else out
    }

    /** 人类可读的回显（日志用）：`1-3,5` 形式，连续段压缩。 */
    fun describe(selected: Collection<Int>): String {
        if (selected.isEmpty()) return "(空)"
        val sorted = selected.sorted()
        val sb = StringBuilder()
        var start = sorted[0]
        var prev = sorted[0]
        fun flush() {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(if (start == prev) "$start" else "$start-$prev")
        }
        for (n in sorted.drop(1)) {
            if (n == prev + 1) {
                prev = n
            } else {
                flush(); start = n; prev = n
            }
        }
        flush()
        return sb.toString()
    }
}
