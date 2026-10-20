package com.doggybear233.saltaudiocopy.rip

import java.nio.file.Files
import java.nio.file.Path

/** WAV 文件的最小事实：只读头，不解码音频。 */
data class WavFacts(
    val dataBytes: Long,
    val sectors: Long,
    val headerOk: Boolean,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int
) {
    val durationMs: Long
        get() {
            val byteRate = sampleRate.toLong() * channels * bitsPerSample / 8
            return if (byteRate <= 0) 0 else dataBytes * 1000 / byteRate
        }
}

/**
 * 读取既有 WAV 的头部事实。断点续抓要判断「这个文件是半截还是完整」，
 * 覆盖策略也要据此决定是续写还是另存——两处都依赖它，所以独立成对象便于单测。
 */
object WavInfo {

    const val HEADER_SIZE = 44L

    fun read(path: Path): WavFacts? {
        if (!Files.isRegularFile(path)) return null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size < HEADER_SIZE) return null
        val h = ByteArray(HEADER_SIZE.toInt())
        runCatching {
            Files.newInputStream(path).use { ins ->
                var off = 0
                while (off < h.size) {
                    val n = ins.read(h, off, h.size - off)
                    if (n <= 0) break
                    off += n
                }
            }
        }.getOrNull() ?: return null

        fun ascii(off: Int, len: Int) = String(h, off, len, Charsets.US_ASCII)
        fun i32(off: Int): Long =
            (h[off].toLong() and 0xFF) or
                ((h[off + 1].toLong() and 0xFF) shl 8) or
                ((h[off + 2].toLong() and 0xFF) shl 16) or
                ((h[off + 3].toLong() and 0xFF) shl 24)
        fun i16(off: Int): Int =
            (h[off].toInt() and 0xFF) or ((h[off + 1].toInt() and 0xFF) shl 8)

        val headerOk = ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE" && ascii(12, 4) == "fmt "
        val dataBytes = maxOf(0L, minOf(i32(40).let { if (it < 0) 0 else it }, size - HEADER_SIZE))
        return WavFacts(
            dataBytes = dataBytes,
            sectors = dataBytes / 2352,
            headerOk = headerOk,
            sampleRate = i32(24).toInt(),
            channels = i16(22),
            bitsPerSample = i16(34)
        )
    }

    /** 数据段是否是 CD-DA 的整数个扇区（不整除就是写坏了或被截断）。 */
    fun wholeSectors(facts: WavFacts): Boolean = facts.dataBytes % 2352L == 0L
}
