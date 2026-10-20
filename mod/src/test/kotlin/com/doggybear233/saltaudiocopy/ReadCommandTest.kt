package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.scsi.ReadCommand
import com.doggybear233.saltaudiocopy.scsi.ReadCommandPlan
import com.doggybear233.saltaudiocopy.scsi.ScsiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadCommandTest {

    private fun illegalRequest() = ScsiException("illegal", scsiStatus = 0x02, senseKey = 0x05, asc = 0x20)
    private fun readError() = ScsiException("l-ec", scsiStatus = 0x02, senseKey = 0x03, asc = 0x11)
    private fun mediumError() = ScsiException("incompatible", scsiStatus = 0x02, senseKey = 0x03, asc = 0x30)

    @Test
    fun mmcCdbMatchesTheLayoutWeVerifyOnHardware() {
        val cdb = ReadCommand.MMC.cdb(lba = 0x00010203, count = 16)
        assertEquals(12, cdb.size)
        assertEquals(0xBE, cdb[0].toInt() and 0xFF)
        assertEquals(0x04, cdb[1].toInt() and 0xFF, "Expected Sector Type = CD-DA")
        // READ CD 的 LBA 是 CDB[2..5] 四字节大端（lba=0x00010203 → 00 01 02 03）
        assertEquals(0x00, cdb[2].toInt() and 0xFF)
        assertEquals(0x01, cdb[3].toInt() and 0xFF)
        assertEquals(0x02, cdb[4].toInt() and 0xFF)
        assertEquals(0x03, cdb[5].toInt() and 0xFF)
        assertEquals(0x10, cdb[8].toInt() and 0xFF, "传输长度 = 16 扇区")
        assertEquals(0xF8, cdb[9].toInt() and 0xFF)
    }


    @Test
    fun nonZeroLbaKeepsItsLowByteInCdb5() {
        // 回归用例：0.14.0 曾把 LBA 写成 3 字节，lba=16 变成 0x1000=4096，
        // 越界后驱动报 ASC=0x21，回退链一路失败。真机上的症状是「整盘抓轨中途 IOCTL 报错」。
        val cdb = ReadCommand.MMC.cdb(lba = 16, count = 16)
        assertEquals(0x00, cdb[2].toInt() and 0xFF)
        assertEquals(0x00, cdb[3].toInt() and 0xFF)
        assertEquals(0x00, cdb[4].toInt() and 0xFF)
        assertEquals(0x10, cdb[5].toInt() and 0xFF, "lba 的低字节必须落在 CDB[5]")
        assertEquals(0x10, cdb[8].toInt() and 0xFF)
    }

    @Test
    fun mmcC2SetsTheC2Bit() {
        val cdb = ReadCommand.MMC_C2.cdb(0, 1)
        assertEquals(0xFA, cdb[9].toInt() and 0xFF)
        assertEquals(0xBE, cdb[0].toInt() and 0xFF)
    }

    @Test
    fun read10Uses16BitLengthAtOffset7() {
        val cdb = ReadCommand.READ10.cdb(lba = 0x01020304, count = 300)
        assertEquals(0x28, cdb[0].toInt() and 0xFF)
        assertEquals(0x01, cdb[2].toInt() and 0xFF)
        assertEquals(0x04, cdb[5].toInt() and 0xFF)
        assertEquals(0x01, cdb[7].toInt() and 0xFF, "300 = 0x012C")
        assertEquals(0x2C, cdb[8].toInt() and 0xFF)
    }

    @Test
    fun d8IsBigEndianThreeByteLayout() {
        val cdb = ReadCommand.D8.cdb(lba = 0x0000ABCD, count = 2)
        assertEquals(0xD8, cdb[0].toInt() and 0xFF)
        assertEquals(0x00, cdb[2].toInt() and 0xFF, "LBA 在 2..4 三个字节里大端存放")
        assertEquals(0xAB, cdb[3].toInt() and 0xFF)
        assertEquals(0xCD, cdb[4].toInt() and 0xFF)
        assertEquals(0x02, cdb[8].toInt() and 0xFF, "长度在 6..8")
    }

    @Test
    fun autoMeansNullAndUnknownFallsBackToAuto() {
        assertNull(ReadCommand.parsePreferred("auto"))
        assertNull(ReadCommand.parsePreferred(""))
        assertNull(ReadCommand.parsePreferred(null))
        assertNull(ReadCommand.parsePreferred("no-such-command"))
        assertEquals(ReadCommand.READ10, ReadCommand.parsePreferred("READ10"))
        assertEquals(ReadCommand.MMC_C2, ReadCommand.parsePreferred("mmc-c2 "))
    }

    @Test
    fun orderStartsWithPreferredAndHasNoDuplicates() {
        val order = ReadCommand.order(ReadCommand.READ10)
        assertEquals(ReadCommand.READ10, order.first())
        assertEquals(order.size, order.distinct().size)
        assertTrue(ReadCommand.MMC in order)

        val c2 = ReadCommand.order(ReadCommand.MMC_C2)
        assertEquals(ReadCommand.MMC_C2, c2.first())
        assertEquals(c2.size, c2.distinct().size, "C2 变体不该把 MMC 重复进来")
    }

    @Test
    fun illegalRequestAdvancesToNextCommand() {
        val log = ArrayList<String>()
        val plan = ReadCommandPlan(ReadCommand.MMC) { log += it }
        assertEquals(ReadCommand.MMC, plan.current)
        assertTrue(plan.advanceAfter(illegalRequest()))
        assertEquals(ReadCommand.READ10, plan.current, "MMC 之后应先试 READ10")
        assertTrue(log.single().contains("read command mmc"))
    }

    @Test
    fun realReadErrorDoesNotChangeCommand() {
        val plan = ReadCommandPlan(ReadCommand.MMC) {}
        assertFalse(plan.advanceAfter(readError()), "L-EC 说明命令是通的，换命令只会掩盖问题")
        assertFalse(plan.advanceAfter(mediumError()), "ASC 0x30 是盘不兼容，不是命令不认")
        assertEquals(ReadCommand.MMC, plan.current)
    }

    @Test
    fun nonScsiExceptionNeverAdvances() {
        val plan = ReadCommandPlan(null) {}
        assertFalse(plan.advanceAfter(IllegalStateException("boom")))
        assertEquals(ReadCommand.MMC, plan.current)
    }

    @Test
    fun advancingStopsAtLastCandidate() {
        val plan = ReadCommandPlan(null) {}
        var guard = 0
        while (plan.advanceAfter(illegalRequest())) {
            guard++
            assertTrue(guard < 10)
        }
        assertTrue(plan.exhausted)
        assertTrue(plan.tried.size >= 2, "至少试过两种命令：${plan.tried}")
        assertFalse(plan.advanceAfter(illegalRequest()), "用尽后应返回 false，让调用方抛原始异常")
    }
}
