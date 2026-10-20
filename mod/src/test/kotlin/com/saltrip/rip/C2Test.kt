package com.saltrip.rip

import com.saltrip.rip.scsi.C2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C2 缓冲布局与错误判定的纯逻辑测试（真机 C2 读取另见 C2LiveTest）。 */
class C2Test {

    @Test
    fun layoutSizes() {
        assertEquals(2352, C2.DATA)
        assertEquals(294, C2.INFO)
        assertEquals(2646, C2.STRIDE)
        assertEquals(2646 * 3, C2.bufferSize(3))
    }

    @Test
    fun cleanBufferHasNoErrors() {
        val buf = ByteArray(C2.bufferSize(4))
        val e = C2.sectorErrorBytes(buf, 4)
        assertEquals(4, e.size)
        assertTrue(e.all { it == 0 })
        assertFalse(C2.hasErrors(buf, 4))
    }

    @Test
    fun detectsErrorsInEachSector() {
        val n = 3
        val buf = ByteArray(C2.bufferSize(n))
        // 第 1 扇区（索引 0）的 C2 区偏移 = 2352 + 2（跳过 2 字节头部）
        buf[2352 + 5] = 0x01
        // 第 2 扇区：整块第一扇区之后没有头部，C2 起点 = 1*2646 + 2352
        buf[1 * C2.STRIDE + 2352 + 10] = 0x7F
        // 第 3 扇区保持干净
        val e = C2.sectorErrorBytes(buf, n)
        assertEquals(1, e[0])
        assertEquals(1, e[1])
        assertEquals(0, e[2])
        assertTrue(C2.hasErrors(buf, n))
    }

    @Test
    fun headerBytesOfFirstSectorAreIgnored() {
        val buf = ByteArray(C2.bufferSize(2))
        buf[2352] = 0x01      // 第一扇区的 2 字节头部
        buf[2353] = 0x02
        assertFalse(C2.hasErrors(buf, 2), "第一扇区 C2 头部不计入错误")
    }

    @Test
    fun extractPcmSkipsC2Area() {
        val n = 2
        val buf = ByteArray(C2.bufferSize(n))
        for (i in 0 until n) {
            buf[i * C2.STRIDE] = (i + 1).toByte()
            buf[i * C2.STRIDE + 2351] = 0x55
            buf[i * C2.STRIDE + 2352] = 0x7F   // C2 区应为噪声
        }
        val pcm = C2.extractPcm(buf, n)
        assertEquals(2 * C2.DATA, pcm.size)
        assertEquals(1, pcm[0].toInt())
        assertEquals(0x55, pcm[2351].toInt() and 0xFF)
        assertEquals(2, pcm[2352].toInt())
        assertEquals(0x55, pcm[2352 + 2351].toInt() and 0xFF)
    }
}
