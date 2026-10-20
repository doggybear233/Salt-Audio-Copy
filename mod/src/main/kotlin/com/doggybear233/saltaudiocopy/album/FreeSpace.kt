package com.doggybear233.saltaudiocopy.album

import java.nio.file.Path
import com.doggybear233.saltaudiocopy.ui.Strings

/**
 * 磁盘余量检查。
 *
 * 抓一张 74 分钟盘约 780 MB（WAV）；加上 FLAC 中间产物、CUE、日志、封面，
 * 留 1.15 倍余量。余量不足时必须**在写第一个扇区之前**报错 —— 写到一半空间耗尽会留下
 * 半截 WAV，而且驱动器仍在转，报错信息也很难看懂。
 */
object FreeSpace {

    /** 每扇区 2352 字节 + 每轨 44 字节 WAV 头。 */
    fun estimateBytes(totalSectors: Long, trackCount: Int): Long =
        totalSectors * 2352L + trackCount.toLong() * 44L

    /** 需求 = 音频数据 × 1.15 + 8 MB（CUE/日志/封面/文件系统开销）。 */
    fun requiredBytes(totalSectors: Long, trackCount: Int): Long {
        val audio = estimateBytes(totalSectors, trackCount)
        return (audio * 115L / 100L) + 8L * 1024 * 1024
    }

    /** @return 可用空间不足时返回提示文案；足够返回 null。 */
    fun check(usableBytes: Long, totalSectors: Long, trackCount: Int, keepWav: Boolean): String? {
        var need = requiredBytes(totalSectors, trackCount)
        if (!keepWav) need = need * 3 / 2 // 编码期间 WAV 与 FLAC 同时存在
        if (usableBytes >= need) return null
        return Strings.fmt("磁盘空间不足：需要约 %s，可用 %s", mb(need), mb(usableBytes))
    }

    fun usableBytes(dir: Path): Long = runCatching {
        var p = dir
        while (!java.nio.file.Files.exists(p) && p.parent != null) p = p.parent
        java.nio.file.Files.getFileStore(p).usableSpace
    }.getOrDefault(-1L)

    private fun mb(b: Long): String = "%.1f GB".format(b / 1024.0 / 1024.0 / 1024.0)
}
