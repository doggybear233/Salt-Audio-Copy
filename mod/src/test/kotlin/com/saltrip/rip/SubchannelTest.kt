package com.saltrip.rip

import com.saltrip.rip.scsi.Subchannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 子通道 Q 的单元测试：用合成数据验证位平面反交织与 CRC。
 * 真机读取见 SubchannelLiveTest。
 */
class SubchannelTest {

    /** 造一个 96 字节子通道块，把给定 Q 的 12 字节写进 bit6 平面。 */
    private fun blockWithQ(q: ByteArray): ByteArray {
        val block = ByteArray(Subchannel.SIZE)
        for (bit in 0 until 96) {
            val byteIdx = bit / 8
            val bitInByte = bit % 8
            val v = (q[byteIdx].toInt() shr (7 - bitInByte)) and 1
            if (v != 0) block[bit] = (block[bit].toInt() or 0x40).toByte()
        }
        return block
    }

    /** 按规范构造 Q：control/adr、track、index、rel、abs、CRC。 */
    private fun makeQ(control: Int, adr: Int, track: Int, index: Int, absMsf: Int = 0x000200): ByteArray {
        fun toBcd(v: Int): Byte = (((v / 10) shl 4) or (v % 10)).toByte()
        val q = ByteArray(12)
        q[0] = (((control and 0x0F) shl 4) or (adr and 0x0F)).toByte()
        q[1] = toBcd(track)
        q[2] = toBcd(index)
        val f = absMsf % 75
        val s = (absMsf / 75) % 60
        val m = absMsf / (75 * 60)
        q[7] = toBcd(m)
        q[8] = toBcd(s)
        q[9] = toBcd(f)
        val crc = Subchannel.crc16(q, 0, 10)
        q[10] = ((crc shr 8) and 0xFF).toByte()
        q[11] = (crc and 0xFF).toByte()
        return q
    }

    @Test
    fun deinterleaveKeepsChannelBits() {
        val q = makeQ(control = 0, adr = 1, track = 3, index = 1)
        assertContentEquals(q, Subchannel.extractChannel(blockWithQ(q), 1))
    }

    @Test
    fun parsesFieldsAndCrc() {
        val parsed = Subchannel.parseQ(makeQ(control = 0x0, adr = 1, track = 7, index = 1, absMsf = 0x1234))
        assertEquals(0, parsed.control)
        assertEquals(1, parsed.adr)
        assertEquals(7, parsed.track)
        assertEquals(1, parsed.index)
        assertTrue(parsed.crcValid, "合成数据 CRC 应自洽")
        assertTrue(parsed.isAudio)
    }

    @Test
    fun detectsCorruptedCrc() {
        val q = makeQ(control = 0, adr = 1, track = 1, index = 0)
        q[5] = 0x7F
        assertFalse(Subchannel.parseQ(q).crcValid)
    }

    @Test
    fun msfToLbaSubtractsLeadIn() {
        assertEquals(0, Subchannel.absToLba(2 * 75))
        assertEquals(74, Subchannel.absToLba(2 * 75 + 74))
    }

    @Test
    fun bcdParsingHandlesTwoDigitValues() {
        val abs = 12 * 75 * 60 + 34 * 75 + 56
        val parsed = Subchannel.parseQ(makeQ(control = 0x4, adr = 1, track = 21, index = 1, absMsf = abs))
        assertEquals(21, parsed.track)
        assertEquals(abs, parsed.absMsf)
        assertFalse(parsed.isAudio, "control bit2=1 表示数据轨")
    }
}
