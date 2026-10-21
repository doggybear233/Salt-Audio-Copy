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

    /**
     * 峰值占用（字节）。
     *
     * 以前只按「一份 WAV」估：FLAC 模式在编码期间 WAV 与 FLAC 同时存在（编码是逐轨的，
     * 最坏情况整盘的 WAV 都还在），整盘镜像模式还要多一份拼接产物（逐轨 WAV + 镜像）。
     * 低估会让预检通过、写到一半空间耗尽。
     */
    fun peakBytes(
        totalSectors: Long,
        trackCount: Int,
        format: String = "wav",
        imageMode: Boolean = false
    ): Long {
        val audio = estimateBytes(totalSectors, trackCount)
        var peak = audio
        if (format.equals("flac", true)) {
            // FLAC 约为 WAV 的 55~70%：按 65% 算，宁可多一点
            peak += audio * 65L / 100L
        }
        if (imageMode) peak += audio // 镜像与逐轨文件并存
        return (peak * 115L / 100L) + 8L * 1024 * 1024
    }

    /**
     * @return 可用空间不足时返回提示文案；足够返回 null。
     *
     * `usableBytes < 0` 表示「问不到磁盘信息」（网络盘、权限不足、路径还不存在）。
     * 这种情况返回 null：宁可让抓轨继续、真的写满时报写盘错误，也不要因为一个
     * 读不到的数值把用户挡在门外（以前会显示「可用 -0.0 GB」这种看不懂的话）。
     */
    fun check(
        usableBytes: Long,
        totalSectors: Long,
        trackCount: Int,
        format: String = "wav",
        imageMode: Boolean = false
    ): String? {
        if (usableBytes < 0) return null
        val need = peakBytes(totalSectors, trackCount, format, imageMode)
        if (usableBytes >= need) return null
        return Strings.fmt("磁盘空间不足：需要约 %s，可用 %s", gb(need), gb(usableBytes))
    }

    fun usableBytes(dir: Path): Long = runCatching {
        var p = dir
        while (!java.nio.file.Files.exists(p) && p.parent != null) p = p.parent
        java.nio.file.Files.getFileStore(p).usableSpace
    }.getOrDefault(-1L)

    private fun gb(b: Long): String = "%.1f GB".format(b / 1024.0 / 1024.0 / 1024.0)
}
