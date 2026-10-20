package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.scsi.C2
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.ScsiException
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import com.doggybear233.saltaudiocopy.scsi.checkTransfer
import com.doggybear233.saltaudiocopy.scsi.senseTriple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SCSI 层的纯校验逻辑：sense 两种格式、传输长度、lead-out、C2 缓冲长度。 */
class ScsiGuardsTest {

    private fun toc(entries: List<TocEntry>) = DiscToc(firstTrack = 1, lastTrack = entries.count { it.track != 0xAA }, entries = entries)

    private fun track(n: Int, lba: Int) = TocEntry(n, true, 1, lba)

    @Test
    fun fixedSenseIsReadFromTheStandardOffsets() {
        val sense = ByteArray(18)
        sense[0] = 0x70
        sense[2] = 0x05                       // ILLEGAL REQUEST
        sense[12] = 0x24.toByte()             // invalid field in CDB
        sense[13] = 0x00
        assertEquals(listOf(0x05, 0x24, 0x00), senseTriple(sense).toList())
    }

    @Test
    fun descriptorSenseIsNotMisreadAsHardwareError() {
        // 0x72 的 key 在 [1]、ASC 在 [2]；按 fixed 解会得到 key=0x03(硬件错误)、ASC=0
        val sense = ByteArray(8)
        sense[0] = 0x72
        sense[1] = 0x03
        sense[2] = 0x11.toByte()
        sense[3] = 0x05
        assertEquals(listOf(0x03, 0x11, 0x05), senseTriple(sense).toList())
    }

    @Test
    fun truncatedSenseDoesNotThrow() {
        assertEquals(listOf(0, 0, 0), senseTriple(ByteArray(0)).toList())
        assertEquals(listOf(0, 0, 0), senseTriple(byteArrayOf(0x70)).toList())
        assertEquals(listOf(0x05, 0, 0), senseTriple(byteArrayOf(0x70, 0, 0x05)).toList())
    }

    @Test
    fun transferLongerThanBufferIsRejected() {
        val cdb = ByteArray(12).also { it[0] = 0xBE.toByte(); it[9] = 0xFA.toByte() }
        val e = assertFailsWith<ScsiException> { checkTransfer(3000, 2000, cdb) }
        assertTrue(e.message!!.contains("传输长度异常"), e.message)
        assertTrue(e.message!!.contains("0xBE"), e.message)
    }

    @Test
    fun transferWithinBufferIsAccepted() {
        checkTransfer(2352, 2352, ByteArray(12))
        checkTransfer(0, 804, ByteArray(12))
    }

    @Test
    fun tocWithoutLeadOutIsFlagged() {
        assertFalse(toc(listOf(track(1, 0), track(2, 5000))).hasLeadOut)
    }

    @Test
    fun tocWithZeroLeadOutIsFlagged() {
        // 有的 BD 组合光驱回 0xAA 但地址写 0；只看「有没有 0xAA」会漏掉这种情况
        val entries = listOf(track(1, 0), track(2, 5000), TocEntry(0xAA, false, 1, 0))
        assertFalse(toc(entries).hasLeadOut)
    }

    @Test
    fun normalTocHasLeadOutAndSaneSectors() {
        val entries = listOf(track(1, 0), track(2, 5000), TocEntry(0xAA, false, 1, 12_000))
        val t = toc(entries)
        assertTrue(t.hasLeadOut)
        assertEquals(0 to 5000, t.sectorsOf(0))
        assertEquals(5000 to 7000, t.sectorsOf(1))
        assertEquals(12_000, t.totalSectors)
    }

    @Test
    fun backwardsTocNeverYieldsNegativeSectors() {
        // lead-out 比末轨起点还小：以前 sectorsOf 返回负数，读取循环一次不跑却报「完成」
        val entries = listOf(track(1, 0), track(2, 9000), TocEntry(0xAA, false, 1, 8000))
        val t = toc(entries)
        assertEquals(0, t.sectorsOf(1).second)
        assertEquals(0, (t.totalSectors - 20_000).coerceAtLeast(0))
    }

    @Test
    fun c2PcmExtractionRejectsTooShortBuffer() {
        // 0xFA 每扇区 2646 字节；只按 2352 分配时以前会静默越界/错位
        val short = ByteArray(2352)
        assertFailsWith<IllegalArgumentException> { C2.extractPcm(short, 1) }
        val ok = ByteArray(C2.bufferSize(1))
        assertEquals(2352, C2.extractPcm(ok, 1).size)
    }
}
