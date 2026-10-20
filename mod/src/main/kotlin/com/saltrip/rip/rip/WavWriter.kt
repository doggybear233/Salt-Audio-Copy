package com.saltrip.rip.rip

import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * 流式 WAV 写入：先写 44 字节头占位，边抓边写，收尾时回填长度。
 * 规格固定为 44.1 kHz / 16 bit / 立体声 / 小端（CD-DA 原生格式）。
 */
/** WAV 写入的抽象：Test&amp;Copy 的「测试读取」用 [NullWavSink] 只算 CRC 不落盘。 */
internal interface WavSink : AutoCloseable {
    fun write(bytes: ByteArray, off: Int, len: Int)
    fun dataByteCount(): Long
}

/** 不写文件的 sink，供 Test&amp;Copy 的测试通道使用。 */
internal object NullWavSink : WavSink {
    private var bytes = 0L
    override fun write(bytes: ByteArray, off: Int, len: Int) { this.bytes += len }
    override fun dataByteCount(): Long = bytes
    override fun close() {}
}

class WavWriter(
    path: Path,
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    private val bitsPerSample: Int = 16,
    /** true 时若目标已是合法的同格式 WAV，则在其数据尾部继续写（断点续抓）。 */
    private val append: Boolean = false
) : WavSink {

    private val raf = RandomAccessFile(path.toFile(), "rw")
    private var dataBytes = 0L
    private var finished = false

    /** 续写前文件里已有的数据字节数（0 表示新文件）。 */
    val priorDataBytes: Long

    init {
        val resumed = if (append && raf.length() >= HEADER_SIZE && hasValidHeader()) {
            val existing = raf.length() - HEADER_SIZE
            raf.seek(raf.length())
            existing
        } else {
            -1L
        }
        if (resumed >= 0) {
            priorDataBytes = resumed
            dataBytes = resumed
        } else {
            raf.setLength(0)
            raf.write(header(0))
            priorDataBytes = 0
            dataBytes = 0
        }
    }

    private fun hasValidHeader(): Boolean {
        raf.seek(0)
        val h = ByteArray(HEADER_SIZE.toInt())
        if (raf.read(h) != h.size) return false
        val riff = String(h, 0, 4, Charsets.US_ASCII)
        val wave = String(h, 8, 4, Charsets.US_ASCII)
        val fmt = String(h, 12, 4, Charsets.US_ASCII)
        return riff == "RIFF" && wave == "WAVE" && fmt == "fmt "
    }

    private fun header(dataLen: Int): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val h = ByteArray(44)
        fun putAscii(off: Int, s: String) { for (i in s.indices) h[off + i] = s[i].code.toByte() }
        fun putI32(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v ushr 8) and 0xFF).toByte()
            h[off + 2] = ((v ushr 16) and 0xFF).toByte()
            h[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun putI16(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        putAscii(0, "RIFF")
        putI32(4, 36 + dataLen)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putI32(16, 16)
        putI16(20, 1)                 // PCM
        putI16(22, channels)
        putI32(24, sampleRate)
        putI32(28, byteRate)
        putI16(32, blockAlign)
        putI16(34, bitsPerSample)
        putAscii(36, "data")
        putI32(40, dataLen)
        return h
    }

    override fun write(bytes: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        raf.write(bytes, off, len)
        dataBytes += len
    }

    /** 把数据区截断到 [bytes]（续抓前丢掉半截扇区）。 */
    fun truncateData(bytes: Long) {
        raf.setLength(HEADER_SIZE + bytes)
        raf.seek(HEADER_SIZE + bytes)
        dataBytes = bytes
    }

    override fun dataByteCount(): Long = dataBytes

    /** 回填 RIFF/data 长度。 */
    fun finish() {
        if (finished) return
        finished = true
        val len32 = dataBytes.toInt()
        raf.seek(4)
        raf.write(intLe(36 + len32))
        raf.seek(40)
        raf.write(intLe(len32))
        raf.seek(raf.length())
    }

    private companion object {
        const val HEADER_SIZE = 44L
    }

    private fun intLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    override fun close() {
        runCatching { finish() }
        raf.close()
    }
}
