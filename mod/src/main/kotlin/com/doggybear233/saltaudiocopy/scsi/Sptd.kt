package com.doggybear233.saltaudiocopy.scsi

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT

/**
 * SCSI_PASS_THROUGH_DIRECT 的最小实现。
 *
 * 结构体布局（x64，sizeof = 56，实测自 Python 探针，禁止 pack）：
 *   0  u16 Length
 *   2  u8  ScsiStatus
 *   3  u8  PathId
 *   4  u8  TargetId
 *   5  u8  Lun
 *   6  u8  CdbLength
 *   7  u8  SenseInfoLength
 *   8  u8  DataIn
 *   12 u32 DataTransferLength
 *   16 u32 TimeOutValue
 *   24 ptr DataBuffer
 *   32 u32 SenseInfoOffset
 *   36 u8[16] Cdb
 *
 * 注意：SenseInfoOffset 必须指向"同一个缓冲内"的位置，所以实际分配
 * 56 + 32 字节，Length 仍然填 56，sense 缓冲紧跟结构体。
 */
object Sptd {

    const val IOCTL_SCSI_PASS_THROUGH_DIRECT = 0x4D014
    const val STRUCT_SIZE = 56
    const val SENSE_LEN = 32
    const val TOTAL_SIZE = STRUCT_SIZE + SENSE_LEN
    const val DATA_IN = 1
    const val DATA_NONE = 2

    private const val OFF_SCSI_STATUS = 2L
    private const val OFF_CDB_LENGTH = 6L
    private const val OFF_SENSE_LEN = 7L
    private const val OFF_DATA_IN = 8L
    private const val OFF_TRANSFER = 12L
    private const val OFF_TIMEOUT = 16L
    private const val OFF_DATA_PTR = 24L
    private const val OFF_SENSE_OFF = 32L
    private const val OFF_CDB = 36L

    /** 执行命令，把数据拷回 Kotlin 数组。 */
    fun execute(dev: MemorySegment, cdb: ByteArray, bufLen: Int, dataIn: Boolean = true, timeoutSec: Int = 10): ByteArray {
        if (bufLen <= 0) {
            executeRaw(dev, cdb, MemorySegment.NULL, 0, false, timeoutSec)
            return ByteArray(0)
        }
        Arena.ofConfined().use { arena ->
            val buf = arena.allocate(bufLen.toLong(), 8)
            val returned = executeRaw(dev, cdb, buf, bufLen, dataIn, timeoutSec)
            val out = ByteArray(returned)
            MemorySegment.copy(buf, JAVA_BYTE, 0L, out, 0, returned)
            return out
        }
    }

    /** 执行命令，数据留在调用方提供的缓冲里（用于抓轨复用缓冲，避免逐块分配）。 */
    fun executeInto(dev: MemorySegment, cdb: ByteArray, buf: MemorySegment, bufLen: Int, timeoutSec: Int = 10): Int =
        executeRaw(dev, cdb, buf, bufLen, true, timeoutSec)

    /**
     * 执行「数据到设备」的命令（MODE SELECT 这类写参数的命令）。
     * @return 驱动回报的传输字节数
     */
    fun executeOut(dev: MemorySegment, cdb: ByteArray, payload: ByteArray, timeoutSec: Int = 10): Int {
        if (payload.isEmpty()) return executeRaw(dev, cdb, MemorySegment.NULL, 0, false, timeoutSec)
        Arena.ofConfined().use { arena ->
            val buf = arena.allocate(payload.size.toLong(), 8)
            MemorySegment.copy(payload, 0, buf, JAVA_BYTE, 0L, payload.size)
            return executeRaw(dev, cdb, buf, payload.size, false, timeoutSec)
        }
    }

    private fun executeRaw(
        dev: MemorySegment, cdb: ByteArray, buf: MemorySegment, bufLen: Int,
        dataIn: Boolean, timeoutSec: Int
    ): Int {
        Arena.ofConfined().use { arena ->
            val pkt = arena.allocate(TOTAL_SIZE.toLong(), 8)
            pkt.set(JAVA_SHORT, 0L, STRUCT_SIZE.toShort())
            pkt.set(JAVA_BYTE, OFF_SCSI_STATUS, 0)
            pkt.set(JAVA_BYTE, 3L, 0)   // PathId
            pkt.set(JAVA_BYTE, 4L, 0)   // TargetId
            pkt.set(JAVA_BYTE, 5L, 0)   // Lun
            pkt.set(JAVA_BYTE, OFF_CDB_LENGTH, cdb.size.toByte())
            pkt.set(JAVA_BYTE, OFF_SENSE_LEN, SENSE_LEN.toByte())
            pkt.set(JAVA_BYTE, OFF_DATA_IN, (if (dataIn) DATA_IN else DATA_NONE).toByte())
            pkt.set(JAVA_INT, OFF_TRANSFER, bufLen)
            pkt.set(JAVA_INT, OFF_TIMEOUT, timeoutSec)
            pkt.set(ADDRESS, OFF_DATA_PTR, if (bufLen == 0) MemorySegment.NULL else buf)
            pkt.set(JAVA_INT, OFF_SENSE_OFF, STRUCT_SIZE)
            for (i in cdb.indices) pkt.set(JAVA_BYTE, OFF_CDB + i, cdb[i])

            // 传给驱动的缓冲必须覆盖结构体 + sense 区（Length 字段仍为 56），否则 SenseInfoOffset 越界 → err=87
            Win32.deviceIoControl(dev, IOCTL_SCSI_PASS_THROUGH_DIRECT, pkt, TOTAL_SIZE, arena)

            val status = pkt.get(JAVA_BYTE, OFF_SCSI_STATUS).toInt() and 0xFF
            if (status != 0) {
                val senseStart = STRUCT_SIZE.toLong()
                val sense = ByteArray(SENSE_LEN) { pkt.get(JAVA_BYTE, senseStart + it).toInt().toByte() }
                throw senseToException(cdb, status, sense)
            }
            // DeviceIoControl 的 lpBytesReturned 对 SPTD 返回结构体大小（56），不是数据长度；
            // 真正的传输字节数由驱动回写到 DataTransferLength 字段。
            val transferred = pkt.get(JAVA_INT, OFF_TRANSFER)
            // 不静默截断：驱动说传了 3000 字节而缓冲只有 2000，说明 CDB 要的东西和我们
            // 分配的不一致（典型是 CDB[9] 多要了 C2/子通道）。截短后照样解析只会得到垃圾 PCM。
            checkTransfer(transferred, bufLen, cdb)
            return transferred
        }
    }

    /** 把 SCSI sense 翻译成可读异常，保留 key/asc/ascq 供调用方判断是否值得重试。 */
    private fun senseToException(cdb: ByteArray, status: Int, sense: ByteArray): ScsiException {
        val code = cdb[0].toInt() and 0xFF
        val response = (sense[0].toInt() and 0xFF) and 0x7F
        // 两种 sense 格式都要认：0x70/0x71 是 fixed（key 在 [2]，ASC/ASCQ 在 [12]/[13]），
        // 0x72/0x73 是 descriptor（key 在 [1]，ASC/ASCQ 在 [2]/[3]）。
        // 只按 fixed 解会把 descriptor 的 ASC 当成 key 的高半字节，日志里冒出
        // 「HARDWARE ERROR」这类假故障，调用方还会误判成「不可重试」。
        val parsed = senseTriple(sense)
        val key = parsed[0]
        val asc = parsed[1]
        val ascq = parsed[2]
        val keyName = SENSE_KEYS[key] ?: "UNKNOWN(0x${key.toString(16)})"
        val detail = SENSE_DETAILS[asc to ascq] ?: SENSE_DETAILS_ASC[asc] ?: ""
        val msg = "SCSI 错误: ${keyName}${if (detail.isNotEmpty()) " - $detail" else ""} " +
            "(命令 0x${code.toString(16)}, status=0x${status.toString(16)}, " +
            "sense=0x${response.toString(16)}/$key/0x${asc.toString(16)}/0x${ascq.toString(16)})"
        return ScsiException(msg, scsiStatus = status, senseKey = key, asc = asc, ascq = ascq)
    }

    private val SENSE_KEYS = mapOf(
        0x0 to "NO SENSE",
        0x1 to "RECOVERED ERROR",
        0x2 to "NOT READY",
        0x3 to "MEDIUM ERROR",
        0x4 to "HARDWARE ERROR",
        0x5 to "ILLEGAL REQUEST",
        0x6 to "UNIT ATTENTION",
        0x7 to "DATA PROTECT",
        0xB to "ABORTED COMMAND"
    )

    private val SENSE_DETAILS = mapOf(
        (0x02 to 0x00) to "No reference position found",
        (0x04 to 0x01) to "Logical unit is in process of becoming ready",
        (0x04 to 0x02) to "Logical unit not ready, initializing command required",
        (0x11 to 0x00) to "Unrecovered read error",
        (0x11 to 0x01) to "Read retries exhausted",
        (0x11 to 0x05) to "L-EC uncorrectable error",
        (0x11 to 0x06) to "CIRC unrecovered error",
        (0x15 to 0x00) to "Random positioning error",
        (0x20 to 0x00) to "Invalid command operation code",
        (0x21 to 0x00) to "Logical block address out of range",
        (0x24 to 0x00) to "Invalid field in CDB",
        (0x26 to 0x00) to "Invalid field in parameter list",
        (0x28 to 0x00) to "Not ready to ready change, medium may have changed",
        (0x29 to 0x00) to "Power on, reset, or bus device reset occurred",
        (0x2C to 0x00) to "Command sequence error",
        (0x30 to 0x00) to "Incompatible medium installed",
        (0x30 to 0x01) to "Cannot read medium - unknown format",
        (0x30 to 0x02) to "Cannot read medium - incompatible format",
        (0x30 to 0x03) to "Cleaning cartridge installed",
        (0x3A to 0x00) to "Medium not present",
        (0x3F to 0x02) to "Changed operating definition",
        (0x3F to 0x03) to "Current operating definition invalid",
        (0x44 to 0x00) to "Internal target failure",
        (0x57 to 0x00) to "Unable to recover table-of-contents",
        (0x64 to 0x00) to "Illegal mode for this track"
    )

    private val SENSE_DETAILS_ASC = mapOf(
        0x30 to "Incompatible / unreadable medium format"
    )
}

/**
 * 从 sense 数据解出 `[senseKey, ASC, ASCQ]`。
 *
 * fixed（0x70/0x71）与 descriptor（0x72/0x73）两种排布差别很大：
 * 只按 fixed 解，descriptor 的 senseKey 会被读成 0、ASC 读成 key 的那个字节，
 * 于是「介质不兼容」在日志里变成「HARDWARE ERROR」，上层也会据此重试或放弃。
 * 短包（驱动只回几字节）不抛异常，缺的字段按 0 处理。
 */
internal fun senseTriple(sense: ByteArray): IntArray {
    if (sense.isEmpty()) return intArrayOf(0, 0, 0)
    val response = (sense[0].toInt() and 0xFF) and 0x7F
    return if (response == 0x72 || response == 0x73) {
        intArrayOf(
            if (sense.size > 1) sense[1].toInt() and 0x0F else 0,
            if (sense.size > 2) sense[2].toInt() and 0xFF else 0,
            if (sense.size > 3) sense[3].toInt() and 0xFF else 0
        )
    } else {
        intArrayOf(
            if (sense.size > 2) sense[2].toInt() and 0x0F else 0,
            if (sense.size > 12) sense[12].toInt() and 0xFF else 0,
            if (sense.size > 13) sense[13].toInt() and 0xFF else 0
        )
    }
}

/**
 * 校验驱动回报的传输长度没有超过我们给的缓冲。
 *
 * 超出即抛错而不是截断：这通常意味着 CDB 要多收 C2/子通道（方向位与分配长度不匹配），
 * 硬截断后照旧解析会把垃圾字节当 PCM 写进专辑。
 */
internal fun checkTransfer(transferred: Int, capacity: Int, cdb: ByteArray) {
    if (transferred < 0 || transferred > capacity) {
        val op = if (cdb.isNotEmpty()) cdb[0].toInt() and 0xFF else 0
        val flags = if (cdb.size > 9) cdb[9].toInt() and 0xFF else 0
        throw ScsiException(
            "SCSI 传输长度异常: 驱动回报 $transferred 字节，缓冲只有 $capacity 字节" +
                "（命令 0x%02X，CDB[9]=0x%02X）".format(op, flags)
        )
    }
}
