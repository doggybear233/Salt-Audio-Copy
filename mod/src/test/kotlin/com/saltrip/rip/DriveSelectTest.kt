package com.saltrip.rip

import com.saltrip.rip.album.DriveSelect
import com.saltrip.rip.rip.ScanResult
import com.saltrip.rip.scsi.DiscToc
import com.saltrip.rip.scsi.InquiryData
import com.saltrip.rip.scsi.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** 多光驱选择（纯逻辑）。 */
class DriveSelectTest {

    private val toc = DiscToc(
        firstTrack = 1, lastTrack = 1,
        entries = listOf(TocEntry(1, isAudio = true, adr = 1, lba = 0), TocEntry(0xAA, true, 1, 1000))
    )

    private fun scan(path: String) = ScanResult(path, InquiryData("V", "P", "R", 0x05), toc)

    private val f = scan("\\\\.\\F:")
    private val g = scan("\\\\.\\G:")

    @Test
    fun normalizeAcceptsEverySpelling() {
        for (s in listOf("F", "f", "F:", "f:", "\\\\.\\F:", "\\\\.\\f:\\")) {
            assertEquals("f", DriveSelect.normalize(s), s)
        }
        assertEquals("", DriveSelect.normalize(""))
    }

    @Test
    fun blankPrefersFirstDrive() {
        assertSame(f, DriveSelect.pick(listOf(f, g), ""))
    }

    @Test
    fun picksRequestedLetter() {
        assertSame(g, DriveSelect.pick(listOf(f, g), "G"))
        assertSame(g, DriveSelect.pick(listOf(f, g), "g:"))
        assertSame(f, DriveSelect.pick(listOf(f, g), "\\\\.\\f"))
    }

    @Test
    fun missingLetterIsNullNotFallback() {
        // 关键行为：指定了 G 但只有 F 时返回 null，调用方必须报错，不能悄悄抓 F
        assertNull(DriveSelect.pick(listOf(f), "G"))
    }

    @Test
    fun emptyListIsNull() {
        assertNull(DriveSelect.pick(emptyList(), ""))
        assertNull(DriveSelect.pick(emptyList(), "F"))
    }

    @Test
    fun describeIsReadable() {
        assertEquals("自动", DriveSelect.describe(""))
        assertEquals("F", DriveSelect.describe("f:"))
    }
}
