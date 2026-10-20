package com.saltrip.rip

import com.saltrip.rip.scsi.SilenceGapDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 静音法间隙探测（EAC 的 "Test Gaps On Silence"）。
 *
 * 用的都是合成 PCM：静音=全零，有声=0x1000（约 -18 dBFS）。
 */
class SilenceGapDetectorTest {

    private fun silent() = ByteArray(2352)

    private fun loud(): ByteArray {
        val b = ByteArray(2352)
        for (i in b.indices step 2) {
            b[i] = 0x00
            b[i + 1] = 0x10 // 0x1000 小端
        }
        return b
    }

    @Test
    fun peakIsNegativeInfinityForSilence() {
        assertEquals(Double.NEGATIVE_INFINITY, SilenceGapDetector.peakDbfs(silent()))
    }

    @Test
    fun peakOfHalfScaleIsAboutMinusSixDb() {
        val b = ByteArray(4)
        b[0] = 0x00; b[1] = 0x40 // 0x4000 = 16384 = -6.02 dBFS
        val db = SilenceGapDetector.peakDbfs(b)
        assertTrue(db > -6.1 && db < -5.9, "实际 $db")
    }

    @Test
    fun countsConsecutiveSilentSectors() {
        // 100 之前 3 个扇区静音，再往前是有声
        val gap = SilenceGapDetector.detect(trackStart = 100, lowerBound = 0) { lba ->
            if (lba >= 97) silent() else loud()
        }
        assertEquals(3, gap)
    }

    @Test
    fun stopsAtFloor() {
        // 全静音但下界只允许看 2 个扇区
        val gap = SilenceGapDetector.detect(trackStart = 100, lowerBound = 98) { silent() }
        assertEquals(2, gap)
    }

    @Test
    fun respectsMaxScan() {
        val gap = SilenceGapDetector.detect(trackStart = 500, lowerBound = 0, maxScan = 10) { silent() }
        assertEquals(10, gap)
    }

    @Test
    fun firstSectorLoudMeansNoGap() {
        val gap = SilenceGapDetector.detect(trackStart = 50, lowerBound = 0) { loud() }
        assertEquals(0, gap)
    }

    @Test
    fun readFailureStopsImmediately() {
        var calls = 0
        val gap = SilenceGapDetector.detect(trackStart = 10, lowerBound = 0) {
            calls++
            null
        }
        assertEquals(0, gap)
        assertEquals(1, calls)
    }

    @Test
    fun thresholdIsAdjustable() {
        // 0x1000 ≈ -18 dBFS：默认 -60 阈值下算「有声」，放宽到 -6 才算静音
        val strict = SilenceGapDetector.detect(10, 0) { loud() }
        val loose = SilenceGapDetector.detect(10, 0, thresholdDbfs = -6.0) { loud() }
        assertEquals(0, strict)
        assertEquals(10, loose)
    }

    @Test
    fun zeroMaxScanReturnsZero() {
        assertEquals(0, SilenceGapDetector.detect(10, 0, maxScan = 0) { silent() })
    }
}
