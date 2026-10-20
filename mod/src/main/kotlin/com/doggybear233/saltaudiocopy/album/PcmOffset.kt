package com.doggybear233.saltaudiocopy.album

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 采样级 offset 校正（对应 EAC 的 read offset）。
 *
 * 只动 PCM 数据区，长度不变：正 offset 表示丢弃开头 N 个采样、末尾补静音；
 * 负 offset 反之。这样 WAV 头里的 `data` 大小不需要改。
 * 立体声 16bit：每采样 4 字节。
 */
object PcmOffset {

    private const val BYTES_PER_SAMPLE = 4
    private const val HEADER = 44

    fun apply(wavFile: Path, offsetSamples: Int) {
        if (offsetSamples == 0) return
        val bytes = Files.readAllBytes(wavFile)
        if (bytes.size <= HEADER) return
        val dataLen = bytes.size - HEADER
        if (dataLen % BYTES_PER_SAMPLE != 0) return

        val shift = offsetSamples * BYTES_PER_SAMPLE
        val out = ByteArray(bytes.size)
        System.arraycopy(bytes, 0, out, 0, HEADER) // 保留 WAV 头
        if (shift > 0) {
            if (shift < dataLen) {
                System.arraycopy(bytes, HEADER + shift, out, HEADER, dataLen - shift)
            }
            // 末尾默认是 0（静音）
        } else {
            val s = -shift
            if (s < dataLen) {
                System.arraycopy(bytes, HEADER, out, HEADER + s, dataLen - s)
            }
            // 开头默认是 0
        }
        Files.write(wavFile, out, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    /** 从已有 WAV 里读采样数，供日志/校验用。 */
    fun sampleCount(wavFile: Path): Long {
        val bytes = Files.readAllBytes(wavFile)
        if (bytes.size < HEADER) return 0
        val bb = ByteBuffer.wrap(bytes, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
        val dataLen = bb.getInt(40).toLong() and 0xFFFFFFFFL
        return dataLen / BYTES_PER_SAMPLE
    }
}
