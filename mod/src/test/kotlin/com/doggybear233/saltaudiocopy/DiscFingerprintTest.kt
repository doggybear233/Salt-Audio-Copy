package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.scsi.DiscFingerprint
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 换盘判定的纯逻辑：TOC 稳定字段一致才算同一张盘。 */
class DiscFingerprintTest {

    private fun toc(leadOut: Int, vararg lba: Int): DiscToc = DiscToc(
        firstTrack = 1,
        lastTrack = lba.size,
        entries = lba.mapIndexed { i, start -> TocEntry(i + 1, true, 1, start) } +
            TocEntry(0xAA, true, 1, leadOut)
    )

    @Test
    fun sameLayoutIsSameDisc() {
        assertTrue(DiscFingerprint.same(toc(6000, 0, 2000, 4000), toc(6000, 0, 2000, 4000)))
    }

    @Test
    fun differentLeadOutMeansDifferentDisc() {
        assertFalse(DiscFingerprint.same(toc(6000, 0, 2000, 4000), toc(7000, 0, 2000, 4000)))
    }

    @Test
    fun differentTrackStartMeansDifferentDisc() {
        assertFalse(DiscFingerprint.same(toc(6000, 0, 2000, 4000), toc(6000, 0, 2100, 4000)))
    }

    @Test
    fun differentTrackCountMeansDifferentDisc() {
        assertFalse(DiscFingerprint.same(toc(6000, 0, 2000, 4000), toc(6000, 0, 2000)))
    }

    @Test
    fun dataTrackChangesFingerprint() {
        val audio = toc(6000, 0, 2000)
        val data = DiscToc(
            firstTrack = 1, lastTrack = 2,
            entries = listOf(
                TocEntry(1, true, 1, 0),
                TocEntry(2, false, 1, 2000),
                TocEntry(0xAA, true, 1, 6000)
            )
        )
        assertFalse(DiscFingerprint.same(audio, data))
    }

    @Test
    fun nullMeansNoDisc() {
        assertTrue(DiscFingerprint.same(null, null))
        assertFalse(DiscFingerprint.same(null, toc(6000, 0, 2000)))
        assertFalse(DiscFingerprint.same(toc(6000, 0, 2000), null))
    }

    @Test
    fun fingerprintIsStableAcrossRepeatedCalls() {
        val t = toc(6000, 0, 2000, 4000)
        assertEquals(DiscFingerprint.of(t), DiscFingerprint.of(t))
    }
}
