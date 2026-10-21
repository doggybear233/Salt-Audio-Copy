package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.scsi.C2
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.scsi.ReadCommand
import com.doggybear233.saltaudiocopy.scsi.ScsiTransport
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 第 3 轮 SCSI MINOR 清理（F-351…F-356）。
 *
 * 都发生在「只有真机才会碰到」的分支上：C2 头部位置、复用缓冲、MODE SELECT 部分写入、
 * 读命令切换的线程可见性。
 */
class BatchR313Test {

    /** 记录 CDB 与写入长度的假传输层。 */
    private class Fake(
        private val reply: (ByteArray, Int) -> ByteArray,
        private val written: Int? = null
    ) : ScsiTransport {
        val commands = mutableListOf<String>()
        var outBytes = 0

        override fun execute(cdb: ByteArray, size: Int): ByteArray {
            commands += "%02X".format(cdb[0])
            return reply(cdb, size)
        }

        override fun executeOut(cdb: ByteArray, payload: ByteArray): Int {
            commands += "%02X-out".format(cdb[0])
            return written ?: payload.size
        }

        override fun executeInto(cdb: ByteArray, buf: MemorySegment, size: Int, timeoutSec: Int): Int {
            commands += "%02X-into".format(cdb[0])
            val data = reply(cdb, size)
            MemorySegment.copy(data, 0, buf, java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, minOf(size, data.size))
            return minOf(size, data.size)
        }

        override fun close() {}
    }

    @Test
    fun c2BufferWithExternalHeaderIsAccepted() {
        // 有驱动器把 2 字节头部额外加上：2646*n + 2
        val count = 2
        val extra = ByteArray(C2.bufferSize(count) + 2)
        val pcm = C2.extractPcm(extra, count)
        assertEquals(count * C2.DATA, pcm.size)
    }

    @Test
    fun c2BufferThatIsTooShortStillFails() {
        val e = assertFailsWith<IllegalArgumentException> { C2.extractPcm(ByteArray(100), 1) }
        assertTrue(e.message!!.contains("缓冲太短"))
    }

    @Test
    fun reusingATooSmallBufferIsRejectedBeforeIoC() {
        val fake = Fake({ _, size -> ByteArray(size) })
        val dev = CdDevice(fake, ReadCommand.MMC)
        Arena.ofConfined().use { arena ->
            val small = arena.allocate(100)
            val e = assertFailsWith<IllegalArgumentException> {
                dev.readAudioSectorsInto(0, 2, small)
            }
            assertTrue(e.message!!.contains("目标缓冲太小"))
            assertTrue(fake.commands.isEmpty(), "容量不足不该真的发命令")
        }
    }

    @Test
    fun modeSelectPartialWriteIsVisible() {
        // 驱动器只写了 8 字节（不是 16）：说明块长没设成功
        val fake = Fake({ _, size -> ByteArray(size) }, written = 8)
        val dev = CdDevice(fake, ReadCommand.READ10)
        val e = assertFailsWith<IllegalStateException> { dev.readAudioSectors(0, 1) }
        assertTrue(e.message!!.contains("MODE SELECT"), e.message)
    }

    @Test
    fun c2TrafficIsCountedAsAccepted() {
        val count = 1
        val buf = ByteArray(C2.bufferSize(count))
        val fake = Fake({ _, _ -> buf })
        val dev = CdDevice(fake, ReadCommand.MMC_C2)
        val pcm = dev.readAudioSectors(0, count)
        assertContentEquals(ByteArray(C2.DATA), pcm)
    }
}
