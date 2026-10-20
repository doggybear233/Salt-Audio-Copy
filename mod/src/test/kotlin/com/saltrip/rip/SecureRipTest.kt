package com.saltrip.rip

import com.saltrip.rip.rip.SecureRip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureRipTest {

    @Test
    fun `policy maps recovery levels`() {
        assertEquals(1, SecureRip.policyFor("low").identicalNeeded)
        assertEquals(3, SecureRip.policyFor("medium").identicalNeeded)
        assertEquals(5, SecureRip.policyFor("high").identicalNeeded)
        assertEquals(SecureRip.MAX_TOTAL_READS, SecureRip.policyFor("high").maxReads)
        // 未知值退回 medium
        assertEquals(3, SecureRip.policyFor("bogus").identicalNeeded)
    }

    @Test
    fun `stops as soon as enough identical reads`() {
        val data = byteArrayOf(1, 2, 3)
        var reads = 0
        val chunk = SecureRip.readStable(SecureRip.Policy(3, 82), read = { reads++; data })
        assertEquals(3, chunk.reads)
        assertEquals(3, chunk.identical)
        assertTrue(chunk.stable)
        assertTrue(chunk.data.contentEquals(data))
    }

    @Test
    fun `mismatch becomes new baseline and counts from one`() {
        val a = byteArrayOf(1)
        val b = byteArrayOf(2)
        val seq = ArrayDeque(listOf(a, b, b, b))
        var retries = 0
        val chunk = SecureRip.readStable(
            SecureRip.Policy(3, 82),
            read = { seq.removeFirst() },
            onRetry = { retries++ }
        )
        assertEquals(4, chunk.reads)
        assertEquals(1, retries)
        assertTrue(chunk.stable)
        assertTrue(chunk.data.contentEquals(b))
    }

    @Test
    fun `gives up after max reads and keeps last sample`() {
        var n = 0
        val chunk = SecureRip.readStable(
            SecureRip.Policy(3, 5),
            read = { byteArrayOf((n++ % 2).toByte()) }
        )
        assertEquals(5, chunk.reads)
        assertFalse(chunk.stable)
        assertEquals(1, chunk.identical)
    }

    @Test
    fun `no verification reads once`() {
        var reads = 0
        val chunk = SecureRip.readStable(SecureRip.Policy(1, 1), read = { reads++; byteArrayOf(0) })
        assertEquals(1, reads)
        assertTrue(chunk.stable)
    }

    @Test
    fun `peak level of pcm`() {
        assertEquals(0.0, SecureRip.peakLevel(ByteArray(8), 8))
        // 0x7FFF 小端
        val full = byteArrayOf(0xFF.toByte(), 0x7F)
        assertTrue(SecureRip.peakLevel(full, 2) > 0.999)
        // -32768
        val min = byteArrayOf(0x00, 0x80.toByte())
        assertEquals(1.0, SecureRip.peakLevel(min, 2))
        // 半刻度 0x4000
        val half = byteArrayOf(0x00, 0x40)
        assertEquals(0.5, SecureRip.peakLevel(half, 2))
        assertEquals("-inf", SecureRip.peakDb(0.0))
    }
}
