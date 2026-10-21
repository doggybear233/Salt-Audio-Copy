package com.doggybear233.saltaudiocopy.rip

import com.doggybear233.saltaudiocopy.Diag
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * 流式 WAV 写入：先写 44 字节头占位，边抓边写，收尾时回填长度。
 * 规格固定为 44.1 kHz / 16 bit / 立体声 / 小端（CD-DA 原生格式）。
 */

/**
 * 续抓前必须核对「这只文件确实是这一轨」。
 *
 * 只看字节数是不够的：同名文件可能是另一张盘的第 3 轨、或者用户改过 gap 处理后
 * 起点变了。把起点 LBA / 扇区数 / 采样格式写进 `.saltaudiocopy/resume-*.txt`，
 * 不匹配就整轨重来，宁可从零抓也不要拼出半轨错音。
 */
data class ResumeGuard(
    val startLba: Int,
    val sectors: Int,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
    val bitsPerSample: Int = 16
) {
    fun encode(): String =
        "startLba=$startLba\nsectors=$sectors\nsampleRate=$sampleRate\nchannels=$channels\nbits=$bitsPerSample\n"

    companion object {
        fun decode(text: String): ResumeGuard? {
            val map = text.lineSequence()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
                }
                .toMap()
            val start = map["startLba"]?.toIntOrNull() ?: return null
            val sectors = map["sectors"]?.toIntOrNull() ?: return null
            return ResumeGuard(
                start, sectors,
                map["sampleRate"]?.toIntOrNull() ?: 44100,
                map["channels"]?.toIntOrNull() ?: 2,
                map["bits"]?.toIntOrNull() ?: 16
            )
        }
    }
}
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
    private val append: Boolean = false,
    /** 非空时必须与同目录守卫文件一致，才允许续写（见 [ResumeGuard]）。 */
    private val guard: ResumeGuard? = null
) : WavSink {

    private val file: Path = path
    private val raf = RandomAccessFile(path.toFile(), "rw")
    private var dataBytes = 0L
    private var finished = false

    /** 续写前文件里已有的数据字节数（0 表示新文件）。 */
    val priorDataBytes: Long

    /** 因为守卫不匹配而丢弃了旧文件内容（调用方要提示用户，不能悄悄删）。 */
    var droppedExisting: Boolean = false
        private set

    /** 收尾回填头部失败（文件会留下 data=0 的假 WAV，调用方必须提示）。 */
    var finishFailed: Boolean = false
        private set

    init {
        val guardOk = if (guard == null) true else matchesGuard(guard)
        if (append && !guardOk) {
            // 以前只在日志里留一行，用户看到的是「续抓」实际却是整轨重来、旧内容被清零
            droppedExisting = raf.length() > HEADER_SIZE
            Diag.log("resume: 守卫不匹配（同名文件不是这一轨），整轨重抓：${file.fileName}")
        }
        val resumed = if (append && guardOk && raf.length() >= HEADER_SIZE && hasValidHeader()) {
            val declared = declaredDataBytes()
            // 被强杀时 finish() 没跑，头部 data 长度还是 0（或被上次回填成旧值），
            // 只能按文件实际长度推断已写多少，否则续抓会从 0 重来、把整轨再写一遍。
            val actual = raf.length() - HEADER_SIZE
            val existing = if (declared in 1..actual) declared else actual
            raf.seek(HEADER_SIZE + existing)
            raf.setLength(HEADER_SIZE + existing)
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
            writeGuard()
        }
    }

    /** 守卫文件：放在同目录的 `.saltaudiocopy/` 里，不污染用户看到的文件列表。 */
    private fun guardFile(): Path? {
        val parent = file.parent ?: return null
        return parent.resolve(".saltaudiocopy").resolve("resume-${file.fileName}.txt")
    }

    private fun matchesGuard(expected: ResumeGuard): Boolean {
        val p = guardFile() ?: return false
        val text = runCatching { Files.readString(p) }.getOrNull() ?: return false
        val saved = ResumeGuard.decode(text) ?: return false
        return saved == expected
    }

    private fun writeGuard() {
        val g = guard ?: return
        val p = guardFile() ?: return
        runCatching {
            Files.createDirectories(p.parent)
            Files.writeString(p, g.encode())
        }.onFailure { Diag.log("resume guard write failed", it) }
    }

    /** 头部 `data` 块声明的长度；读不到或长度字段越界时返回 0。 */
    private fun declaredDataBytes(): Long {
        raf.seek(40)
        val b = ByteArray(4)
        if (raf.read(b) != 4) return 0
        val v = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
        return if (v > 0) v.toLong() else 0
    }

    /**
     * 同名文件能不能接着写。
     *
     * 只看 RIFF/WAVE/fmt 不够：22/24/34 这类 container 位深的 WAV 也满足这三条，
     * 但里面是容器字节而不是 CD 的 16 位 PCM。继续往里追加等于把两种数据混在一轨，
     * 播放器听到的是噪声，日志却报「质量 100%」。所以要连声道/采样率/位深一起比。
     */
    private fun hasValidHeader(): Boolean {
        raf.seek(0)
        val h = ByteArray(HEADER_SIZE.toInt())
        if (raf.read(h) != h.size) return false
        fun i16(off: Int): Int = (h[off].toInt() and 0xFF) or ((h[off + 1].toInt() and 0xFF) shl 8)
        fun i32(off: Int): Int = i16(off) or (i16(off + 2) shl 16)
        val riff = String(h, 0, 4, Charsets.US_ASCII)
        val wave = String(h, 8, 4, Charsets.US_ASCII)
        val fmt = String(h, 12, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE" || fmt != "fmt ") return false
        if (i16(20) != 1) return false // PCM
        if (i16(22) != channels) return false
        if (i32(24) != sampleRate) return false
        if (i16(34) != bitsPerSample) return false
        if (i16(32) != channels * bitsPerSample / 8) return false
        // fmt 块长度必须是 16：更长的可能是 WAVE_FORMAT_EXTENSIBLE，格式未经验证
        return i32(16) == 16
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
        // finish() 以前完全被 runCatching 吞掉：回填失败会留下 data=0 的假 WAV，
        // 用户看到「抓完」但实际上文件打不开，也没有任何日志。
        try {
            finish()
        } catch (e: Throwable) {
            finishFailed = true
            Diag.log("WAV 头部回填失败（文件可能打不开）: $file", e)
        }
        raf.close()
    }
}
