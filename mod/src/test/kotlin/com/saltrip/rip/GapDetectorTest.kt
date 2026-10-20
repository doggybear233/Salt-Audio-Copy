package com.saltrip.rip

import com.saltrip.rip.scsi.GapDetector
import com.saltrip.rip.scsi.Subchannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** gap 探测的纯逻辑测试（注入假 Q 序列，不碰硬件）。 */
class GapDetectorTest {

    private fun q(index: Int, track: Int = 1, valid: Boolean = true): Subchannel.Q =
        Subchannel.Q(
            control = 0, adr = 1, track = track, index = index,
            relMsf = 0, absMsf = 0,
            crcStored = 0, crcComputed = if (valid) 0 else 1
        )

    @Test
    fun noPregapWhenIndexIsOneAllTheWay() {
        val g = GapDetector.detect(1000, 900) { q(1) }
        assertEquals(0, g.sectors)
        assertEquals(1000, g.startLba)
    }

    @Test
    fun countsContiguousIndexZeroSectors() {
        // 995..999 是 INDEX 00，起始扇区 1000 是 INDEX 01
        val g = GapDetector.detect(1000, 900) { lba -> if (lba in 995..999) q(0) else q(1) }
        assertEquals(5, g.sectors)
        assertEquals(995, g.startLba)
        assertEquals("00:00:05", g.msf)
    }

    @Test
    fun stopsAtLowerBound() {
        val g = GapDetector.detect(1000, 998) { q(0) }
        assertEquals(2, g.sectors)
        assertEquals(998, g.startLba)
    }

    @Test
    fun stopsAtMaxScan() {
        val g = GapDetector.detect(1000, 0, maxScan = 10) { q(0) }
        assertEquals(10, g.sectors)
    }

    @Test
    fun stopsAtBadCrc() {
        val g = GapDetector.detect(1000, 0) { lba -> if (lba >= 998) q(0) else q(0, valid = false) }
        assertEquals(2, g.sectors)
    }

    @Test
    fun stopsOnReadError() {
        val g = GapDetector.detect(1000, 0) { lba ->
            if (lba >= 999) q(0) else throw IllegalStateException("boom")
        }
        assertEquals(1, g.sectors)
        assertTrue(g.startLba == 999)
    }
}
