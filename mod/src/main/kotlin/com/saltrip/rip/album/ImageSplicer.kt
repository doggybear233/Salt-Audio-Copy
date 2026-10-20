package com.saltrip.rip.album

import com.saltrip.rip.rip.WavInfo
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * 把逐轨 WAV 拼成「整盘镜像」单文件。
 *
 * 为什么用拼接而不是「一次读完整盘」：
 * - 逐轨抓取（含 secure 双读、C2、gap、续抓）已经是验证过的路径，拼接只做字节搬运，
 *   不会引入新的读盘行为；
 * - 每轨 CRC 天然可得，日志与 CUE 都能对上；
 * - 代价是临时多占一份磁盘（外层已做余量预检）。
 */
object ImageSplicer {

    const val HEADER_SIZE = 44L

    /** 拼接结果。 */
    data class Result(val dataBytes: Long, val sectors: Long, val parts: Int)

    /**
     * 按顺序拼接 [parts] 的数据段到 [out]，写一个新的 44 字节头。
     *
     * 要求每个 part 都是 16-bit / 2ch / 44.1k 的规范 WAV（由 [WavInfo] 校验），
     * 否则抛 [IllegalArgumentException]——半截文件混进来会让整盘镜像错位。
     */
    fun splice(parts: List<Path>, out: Path): Result {
        require(parts.isNotEmpty()) { "没有可拼接的音轨文件" }
        var total = 0L
        for (p in parts) {
            val f = WavInfo.read(p) ?: throw IllegalArgumentException("不是合法 WAV: $p")
            require(f.headerOk) { "WAV 头损坏: $p" }
            require(f.sampleRate == 44100 && f.channels == 2 && f.bitsPerSample == 16) {
                "采样格式不符（需要 44.1k/2ch/16bit）: $p"
            }
            require(f.dataBytes > 0) { "没有音频数据: $p" }
            total += f.dataBytes
        }
        Files.createDirectories(out.parent ?: out.toAbsolutePath().parent)

        RandomAccessFile(out.toFile(), "rw").use { raf ->
            raf.setLength(0)
            raf.write(header(total))
            val buf = ByteArray(1 shl 20)
            for (p in parts) {
                RandomAccessFile(p.toFile(), "r").use { src ->
                    src.seek(HEADER_SIZE)
                    var left = src.length() - HEADER_SIZE
                    while (left > 0) {
                        val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
        return Result(total, total / 2352, parts.size)
    }

    /** 44 字节 RIFF/WAVE 头。 */
    internal fun header(dataBytes: Long): ByteArray {
        val h = ByteArray(44)
        fun ascii(off: Int, s: String) { for (i in s.indices) h[off + i] = s[i].code.toByte() }
        fun i32(off: Int, v: Long) { for (i in 0..3) h[off + i] = ((v ushr (8 * i)) and 0xFF).toByte() }
        fun i16(off: Int, v: Int) { h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v ushr 8) and 0xFF).toByte() }
        ascii(0, "RIFF"); i32(4, dataBytes + 36)
        ascii(8, "WAVE"); ascii(12, "fmt "); i32(16, 16)
        i16(20, 1); i16(22, 2); i32(24, 44100); i32(28, 44100 * 4); i16(32, 4); i16(34, 16)
        ascii(36, "data"); i32(40, dataBytes)
        return h
    }
}
