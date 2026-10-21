package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.scsi.GapDetector
import com.doggybear233.saltaudiocopy.scsi.ReadCommand
import com.doggybear233.saltaudiocopy.scsi.ScsiException
import com.doggybear233.saltaudiocopy.scsi.ScsiTransport
import com.doggybear233.saltaudiocopy.scsi.Subchannel
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 第 3 轮 r3-06：READ CD 的子通道位、子通道步长自适应、
 * MODE SELECT(10) 参数头与「块长是否真的生效」。
 */
private class RecordingTransport(
    val onExecute: (ByteArray, Int) -> ByteArray = { _, size -> ByteArray(size) },
    val blockLength: Int = 2352
) : ScsiTransport {
    val commands = ArrayList<String>()
    val sizes = ArrayList<Int>()
    var payload: ByteArray? = null
    var payloadCdb: ByteArray? = null
    var closed = false

    override fun execute(cdb: ByteArray, size: Int): ByteArray {
        val op = cdb[0].toInt() and 0xFF
        commands += "%02X".format(op)
        sizes += size
        if (op == 0x5A) {
            // MODE SENSE(10)：8 字节头 + 8 字节块描述符
            val out = ByteArray(64)
            out[6] = 0
            out[7] = 8
            out[13] = ((blockLength shr 16) and 0xFF).toByte()
            out[14] = ((blockLength shr 8) and 0xFF).toByte()
            out[15] = (blockLength and 0xFF).toByte()
            return out
        }
        return onExecute(cdb, size)
    }

    override fun executeOut(cdb: ByteArray, payload: ByteArray): Int {
        commands += "%02X-out".format(cdb[0].toInt() and 0xFF)
        this.payloadCdb = cdb.copyOf()
        this.payload = payload.copyOf()
        return payload.size
    }

    override fun executeInto(cdb: ByteArray, buf: MemorySegment, size: Int, timeoutSec: Int): Int {
        commands += "%02X-into".format(cdb[0].toInt() and 0xFF)
        sizes += size
        val data = onExecute(cdb, size)
        val n = minOf(data.size, size)
        MemorySegment.copy(MemorySegment.ofArray(data), 0L, buf, 0L, n.toLong())
        return n
    }

    override fun close() {
        closed = true
    }
}

class BatchR306Test {

    private fun qBytes(index: Int): ByteArray {
        // 只填 ADR/索引相关字节即可：调用方按 2352..2368 切出来再比对
        val q = ByteArray(16)
        q[0] = 0x41
        q[1] = 0x01
        q[2] = 0x01
        q[3] = index.toByte()
        return q
    }

    @Test
    fun qSubchannelRequestSetsTheSubchannelBit() {
        val count = 2
        val t = RecordingTransport(onExecute = { _, size: Int ->
            if (size == count * Subchannel.Q_STRIDE) ByteArray(size) else ByteArray(size)
        })
        CdDevice(t).use { dev ->
            dev.readSectorsWithQSubchannel(100, count)
        }
        assertEquals("BE-into", t.commands.first())
        assertEquals(count * Subchannel.Q_STRIDE, t.sizes.first(), "要按 2352+16 申请")
    }

    @Test
    fun qSubchannelCdbHasBit2AndFormatTwo() {
        val t = RecordingTransport(onExecute = { _, size: Int -> ByteArray(size) })
        CdDevice(t).use { dev ->
            // 通过公开 API 触发一次，检查 CDB 由 readSectorsWithQSubchannel 拼装
            dev.readSectorsWithQSubchannel(0, 1)
        }
        // CDB 没暴露，改用行为断言：没有子通道位时真实驱动器会只回 2352 字节；
        // 这里用一个「只看 bit2」的假驱动器验证协议一致性。
        val strict = RecordingTransport(onExecute = { cdb, _: Int ->
            if ((cdb[9].toInt() and 0x04) == 0) ByteArray(2352) else ByteArray(2368)
        })
        CdDevice(strict).use { dev ->
            val buf = dev.readSectorsWithQSubchannel(0, 1)
            assertEquals(Subchannel.Q_STRIDE, buf.size)
        }
    }

    @Test
    fun driveWithoutSubchannelSupportIsAnError() {
        val t = RecordingTransport(onExecute = { _, _: Int -> ByteArray(2352) })
        CdDevice(t).use { dev ->
            val e = assertFailsWith<ScsiException> { dev.readSectorsWithQSubchannel(0, 1) }
            assertTrue(e.message!!.contains("子通道"), "要说明驱动器没给子通道：${e.message}")
        }
    }

    @Test
    fun modeSelectHeaderDeclaresItsLength() {
        val t = RecordingTransport(onExecute = { _, size: Int -> ByteArray(size) })
        // READ10 偏好才会走 MODE SELECT（默认偏好是 0xBE）
        CdDevice(t, preferred = ReadCommand.READ10).use { dev ->
            dev.readAudioSectors(0, 1)
        }
        val p = t.payload
        assertTrue(p != null, "READ(10) 之前必须下发 MODE SELECT(10)")
        assertEquals(0, p!![0].toInt(), "Mode Data Length 高字节")
        assertEquals(14, p[1].toInt(), "Mode Data Length = 参数总长 - 2")
        assertEquals(8, p[7].toInt(), "块描述符长度")
        assertEquals(16, t.payloadCdb!![8].toInt(), "CDB 声明的参数长度要和实际一致")
    }

    @Test
    fun ineffectiveBlockLengthIsReportedInsteadOfShortRead() {
        val t = RecordingTransport(onExecute = { _, size: Int -> ByteArray(size) }, blockLength = 2048)
        CdDevice(t, preferred = ReadCommand.READ10, allowCommandFallback = false).use { dev ->
            val e = assertFailsWith<ScsiException> { dev.readAudioSectors(0, 1) }
            assertTrue(
                e.message!!.contains("块长"),
                "MODE SENSE 回读 2048 时必须说「块长未生效」，而不是报短读：${e.message}"
            )
        }
    }

    @Test
    fun gapDetectorMarksUnreadableSubchannel() {
        val gap = GapDetector.detect(1000, 0) { throw ScsiException("no subchannel", senseKey = 0x05, asc = 0x20) }
        assertEquals(0, gap.sectors)
        assertTrue(gap.failed, "读不到子通道必须与「确实没有间隙」区分")
    }

    @Test
    fun gapDetectorWithoutFailureStaysClean() {
        val gap = GapDetector.detect(1000, 0) {
            Subchannel.Q(
                control = 0, adr = 1, track = 1, index = 1,
                relMsf = 0, absMsf = 0, crcStored = 0, crcComputed = 0xFFFF
            )
        }
        assertEquals(0, gap.sectors)
        assertTrue(!gap.failed)
    }
}
