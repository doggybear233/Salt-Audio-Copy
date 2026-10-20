package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.scsi.C2
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.scsi.ReadCommand
import com.doggybear233.saltaudiocopy.scsi.ScsiException
import com.doggybear233.saltaudiocopy.scsi.ScsiTransport
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 批次 17 · T-06：SCSI 命令层的内存假实现。
 *
 * 以前这些分支（读命令回退、短读判定、C2 剥离、短读报错文案）只真机上跑过，
 * CI 里全是 `SALTAC_HW` skipped。用 [FakeScsiTransport] 之后它们变成普通单测。
 */
private class FakeScsiTransport(
    private val reply: (cdb: ByteArray, size: Int) -> ByteArray
) : ScsiTransport {
    val commands = ArrayList<String>()
    var closed = false

    override fun execute(cdb: ByteArray, size: Int): ByteArray {
        commands += "%02X".format(cdb[0])
        return reply(cdb, size)
    }

    override fun executeOut(cdb: ByteArray, payload: ByteArray) {
        commands += "%02X-out".format(cdb[0])
    }

    override fun executeInto(cdb: ByteArray, buf: MemorySegment, size: Int): Int {
        commands += "%02X-into".format(cdb[0])
        return reply(cdb, size).size
    }

    override fun close() {
        closed = true
    }
}

class ScsiTransportTest {

    @Test
    fun shortTransferIsReportedAsShortRead() {
        // 请求 2 扇区 4704 字节，驱动只回 100 字节：必须报短读，不能当 PCM 写进 WAV
        val fake = FakeScsiTransport { _, _ -> ByteArray(100) }
        CdDevice(fake).use { dev ->
            val e = assertFailsWith<ScsiException> { dev.readAudioSectors(0, 2) }
            assertTrue(e.message!!.contains("短读"), "消息应说明短读：${e.message}")
            assertTrue(e.message!!.contains("4704"), "应报出期望字节数：${e.message}")
        }
    }

    @Test
    fun c2ResponseIsStrippedToPlainPcm() {
        // 0xFA 每扇区回 2352 + 294 字节；返回给上层的必须只有 2352
        val count = 3
        val fake = FakeScsiTransport { cdb, size ->
            if (size == count * C2.STRIDE) ByteArray(size) else ByteArray(size)
        }
        CdDevice(fake).use { dev ->
            val pcm = dev.readAudioSectorsC2(0, count)
            assertEquals(count * C2.STRIDE, pcm.size)
        }
    }

    @Test
    fun readCommandFallsBackWhenDriveRejectsIt() {
        // 真机老盘常见：READ CD 报 ILLEGAL REQUEST，必须自动换 READ(10) 而不是直接失败
        val seen = ArrayList<String>()
        val fake = FakeScsiTransport { cdb, size ->
            seen += "%02X".format(cdb[0])
            when (cdb[0].toInt() and 0xFF) {
                0xBE -> throw ScsiException("驱动不认 READ CD", senseKey = 0x05, asc = 0x20)
                else -> ByteArray(size)
            }
        }
        CdDevice(fake, preferred = ReadCommand.MMC, allowCommandFallback = true).use { dev ->
            val pcm = dev.readAudioSectors(0, 2)
            assertEquals(2 * 2352, pcm.size)
            assertEquals(ReadCommand.READ10, dev.readCommand, "应已退到 READ(10)")
            assertTrue(seen.contains("BE"), "先试过 READ CD：$seen")
            // MODE SELECT 走 executeOut，不会进 reply 的 seen 列表，要看 fake 自己的命令记录
            assertTrue(fake.commands.any { it.startsWith("55") }, "READ(10) 前要发 MODE SELECT 设块长：${fake.commands}")
            assertTrue(seen.contains("28"), "最后用 READ(10) 读：$seen")
        }
    }

    @Test
    fun fallbackCanBeDisabled() {
        val fake = FakeScsiTransport { _, _ ->
            throw ScsiException("驱动不认", senseKey = 0x05, asc = 0x20)
        }
        CdDevice(fake, preferred = ReadCommand.MMC, allowCommandFallback = false).use { dev ->
            assertFailsWith<ScsiException> { dev.readAudioSectors(0, 1) }
            assertEquals(ReadCommand.MMC, dev.readCommand, "关掉回退就不该换命令")
        }
    }

    @Test
    fun closeIsForwardedToTransport() {
        val fake = FakeScsiTransport { _, size -> ByteArray(size) }
        CdDevice(fake).close()
        assertTrue(fake.closed, "关闭设备要关掉底层传输")
    }

    @Test
    fun subchannelReadRejectsShortTransfer() {
        val fake = FakeScsiTransport { _, _ -> ByteArray(8) }
        CdDevice(fake).use { dev ->
            val e = assertFailsWith<ScsiException> { dev.readSectorsWithQSubchannel(0, 1) }
            assertTrue(e.message!!.contains("短读"), "子通道短读也要报错：${e.message}")
        }
    }
}
