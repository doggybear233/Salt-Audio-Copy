package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.scsi.ReadCommand
import com.doggybear233.saltaudiocopy.scsi.ScsiException
import com.doggybear233.saltaudiocopy.scsi.ScsiMessages
import com.doggybear233.saltaudiocopy.scsi.ScsiTransport
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2-07：READ(10) 的块长要走真正的 Data-Out 命令，并且失败原因不能被翻译成「没有错误」。
 */
class BatchR207Test {

    /** 只记录命令、按请求长度回数据；不碰任何设备。 */
    private class FakeTransport(private val onData: (ByteArray) -> ByteArray) : ScsiTransport {
        val commands = ArrayList<String>()
        var lastOutPayload: ByteArray? = null

        override fun execute(cdb: ByteArray, size: Int): ByteArray {
            commands += "%02X".format(cdb[0])
            return onData(cdb)
        }

        override fun executeOut(cdb: ByteArray, payload: ByteArray) {
            commands += "%02X-out".format(cdb[0])
            lastOutPayload = payload
        }

        override fun executeInto(cdb: ByteArray, buf: MemorySegment, size: Int, timeoutSec: Int): Int {
            commands += "%02X-into".format(cdb[0])
            return size
        }

        override fun close() {}
    }

    @Test
    fun modeSelectIsSentAsDataOutThenVerified() {
        val fake = FakeTransport { cdb ->
            when (cdb[0].toInt() and 0xFF) {
                0x5A -> ByteArray(64).also { b ->
                    b[7] = 8                    // block descriptor length
                    b[13] = (2352 shr 16).toByte()
                    b[14] = (2352 shr 8).toByte()
                    b[15] = (2352 and 0xFF).toByte()
                }
                else -> ByteArray(2352)
            }
        }
        CdDevice(fake, ReadCommand.READ10).use { dev ->
            val data = dev.readAudioSectors(0, 1)
            assertEquals(2352, data.size)
        }
        assertTrue(fake.commands.contains("55-out"), "MODE SELECT(10) 必须以 Data-Out 下发，命令序列=${fake.commands}")
        assertTrue(fake.commands.contains("5A"), "下完块长要回读确认，命令序列=${fake.commands}")
        assertTrue(fake.commands.contains("28-into") || fake.commands.contains("28"), "实际读要用 READ(10)，命令序列=${fake.commands}")
        val payload = fake.lastOutPayload!!
        assertEquals(8, payload[7].toInt(), "块描述符长度应写在参数头里")
    }

    @Test
    fun blockLengthMismatchIsLoggedNotFatal() {
        val fake = FakeTransport { cdb ->
            when (cdb[0].toInt() and 0xFF) {
                0x5A -> ByteArray(64).also { b ->
                    b[7] = 8
                    b[13] = 0; b[14] = 8; b[15] = 0     // 驱动器说 2048
                }
                else -> ByteArray(2352)
            }
        }
        CdDevice(fake, ReadCommand.READ10).use { dev ->
            assertEquals(2352, dev.readAudioSectors(0, 1).size)
        }
    }

    @Test
    fun zeroSenseCodesFallBackToTheExceptionMessage() {
        val e = ScsiException("短读：命令 mmc 期望 2352 字节，驱动只回了 1024 字节")
        val text = ScsiMessages.human(e)
        assertTrue(text.contains("短读"), "结构化码全 0 时必须用异常文案，实际=$text")
        assertTrue(!text.contains("没有错误"), "不能把校验失败说成没有错误，实际=$text")
    }

    @Test
    fun realSenseCodesStillUseTheFriendlyMap() {
        val e = ScsiException("x", senseKey = 0x02, asc = 0x3A, ascq = 0x00)
        val text = ScsiMessages.human(e)
        assertTrue(!text.contains("短读"))
        assertTrue(text.isNotBlank())
    }
}
