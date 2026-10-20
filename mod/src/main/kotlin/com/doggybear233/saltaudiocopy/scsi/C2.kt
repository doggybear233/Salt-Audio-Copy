package com.doggybear233.saltaudiocopy.scsi

/**
 * READ CD 的 C2 错误信息解析。
 *
 * 带 C2 位（CDB[9] bit1）读取时，每个扇区在 2352 字节用户数据之后附 294 字节 C2 指针/标志，
 * **第一个扇区的 C2 块前 2 字节是头部**（MMC 规定的 C2 error block header）。
 * 这里只做"有没有错"的粗判：C2 区出现任何非零字节即认为该扇区可能出错，
 * 交给上层用重读/双读兜底——不尝试用 C2 指针做纠错（EAC 也不做，它靠重读）。
 */
object C2 {

    /** 每扇区用户数据字节数。 */
    const val DATA = 2352

    /** 每扇区 C2 信息字节数。 */
    const val INFO = 294

    /** 带 C2 读取时每扇区的总字节数。 */
    const val STRIDE = DATA + INFO

    /** 一整块（[count] 扇区）带 C2 时的缓冲长度。 */
    fun bufferSize(count: Int): Int = count * STRIDE

    /**
     * 统计每扇区 C2 区里的非零字节数。
     *
     * @param buf 按「每扇区 2352 数据 + 294 C2」交错排布（与 READ CD 返回一致）
     * @param count 扇区数
     * @return 长度为 [count] 的数组，0 表示该扇区 C2 无错
     */
    fun sectorErrorBytes(buf: ByteArray, count: Int): IntArray {
        require(count >= 0)
        require(buf.size >= bufferSize(count)) {
            "缓冲太短: ${buf.size} < ${bufferSize(count)}"
        }
        val out = IntArray(count)
        for (i in 0 until count) {
            var start = i * STRIDE + DATA
            var end = start + INFO
            // 第一个扇区的 C2 块前两字节是头部，不参与判定
            if (i == 0) start += 2
            var n = 0
            for (p in start until end) if (buf[p].toInt() != 0) n++
            out[i] = n
        }
        return out
    }

    /** 该块是否有任一扇区报告 C2 错误。 */
    fun hasErrors(buf: ByteArray, count: Int): Boolean =
        sectorErrorBytes(buf, count).any { it > 0 }

    /** 从带 C2 的缓冲里拷出纯 PCM（连续 2352×count）。 */
    fun extractPcm(buf: ByteArray, count: Int): ByteArray {
        require(count >= 0)
        require(buf.size >= bufferSize(count)) {
            "带 C2 的缓冲太短: ${buf.size} < ${bufferSize(count)}（0xFA 每扇区 $STRIDE 字节，不是 2352）"
        }
        val out = ByteArray(count * DATA)
        for (i in 0 until count) {
            System.arraycopy(buf, i * STRIDE, out, i * DATA, DATA)
        }
        return out
    }
}
