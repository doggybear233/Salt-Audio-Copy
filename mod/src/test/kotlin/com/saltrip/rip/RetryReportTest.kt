package com.saltrip.rip

import com.saltrip.rip.album.retryRanges
import com.saltrip.rip.rip.RipEngine
import com.saltrip.rip.rip.FakeTrackSource
import com.saltrip.rip.rip.ScanResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

/**
 * 重读位置记录：出错的扇区到底在哪，而不是只有「重试了 N 次」。
 */
class RetryReportTest {

    @Test
    fun emptyRangesFormatAsEmptyString() {
        assertEquals("", retryRanges(emptyList()))
    }

    @Test
    fun singleRangeShowsLbaAndCount() {
        assertEquals("LBA 1200-1215(16)", retryRanges(listOf(1200 until 1216)))
    }

    @Test
    fun manyRangesAreTruncatedWithTotal() {
        val ranges = (0 until 12).map { (it * 100) until (it * 100 + 16) }
        val s = retryRanges(ranges, limit = 3)
        assertTrue(s.startsWith("LBA 0-15(16)"), s)
        assertTrue(s.endsWith("共 12 段"), s)
        assertTrue(!s.contains("LBA 1000-"), "超过 limit 的不该出现")
    }

    @Test
    fun rippingRecordsWhichChunkWasRetried() {
        val dir = Files.createTempDirectory("saltrip-retry")
        val fake = FakeTrackSource(
            sectors = 6000,
            corruptFirstRead = mutableSetOf(288)
        )
        val scan = ScanResult(
            "\\\\.\\X:",
            FakeTrackSource.inquiry(),
            FakeTrackSource.disc3Tracks(6000)
        )
        val r = RipEngine.ripTrack(
            scan = scan, trackIndex = 0, outFile = dir.resolve("01.wav"),
            cancel = { false }, onProgress = {}, verify = true, maxRetries = 8, source = fake
        )
        assertTrue(r.retried.isNotEmpty(), "应当记录重读区间")
        assertTrue(
            r.retried.any { 288 in it },
            "重读区间应包含出错的 288，实际 ${r.retried}"
        )
    }
}
