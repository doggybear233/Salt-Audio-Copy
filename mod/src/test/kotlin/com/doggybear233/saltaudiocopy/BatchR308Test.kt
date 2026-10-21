package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.DriveSelect
import com.doggybear233.saltaudiocopy.rip.ScanResult
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.InquiryData
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * r3-08 的回归：扫描与轮询必须用同一套光驱兜底。
 *
 * 以前 `ConfigActions.scanDrives` 自己写了「取第一台音频盘，否则第一台」，
 * 而 `Discs.sync`（轮询线程）取「第一台能打开的」，多光驱时两边会指向不同驱动器：
 * 手动扫描显示有盘，2 秒后轮询又把它刷成「没有光盘」。
 */
class BatchR308Test {

    private fun scan(letter: String, audio: Boolean): ScanResult {
        val entries = listOf(TocEntry(1, audio, 1, 0), TocEntry(0xAA, true, 1, 2000))
        return ScanResult(
            path = "\\\\.\\$letter:",
            inquiry = InquiryData("SALT", "FAKE CD", "1.00", 0x05),
            toc = DiscToc(1, 1, entries)
        )
    }

    @Test
    fun automaticPickPrefersTheDriveThatActuallyHasAnAudioCd() {
        val data = scan("D", audio = false)
        val audio = scan("F", audio = true)
        assertEquals(audio.path, DriveSelect.pick(listOf(data, audio), "")?.path)
    }

    @Test
    fun automaticPickFallsBackToTheFirstDriveWhenNoAudioCdIsPresent() {
        val first = scan("D", audio = false)
        val second = scan("E", audio = false)
        assertEquals(first.path, DriveSelect.pick(listOf(first, second), "")?.path)
    }

    @Test
    fun explicitDriveWinsOverTheAudioHeuristic() {
        val data = scan("D", audio = false)
        val audio = scan("F", audio = true)
        assertEquals(data.path, DriveSelect.pick(listOf(data, audio), "d:")?.path)
    }

    @Test
    fun missingExplicitDriveReturnsNullInsteadOfSilentlySwitching() {
        // null 让 Discs 抛「设置里指定了光驱 X 但没找到」，而不是悄悄改用另一台
        assertNull(DriveSelect.pick(listOf(scan("D", audio = true)), "F"))
    }
}
