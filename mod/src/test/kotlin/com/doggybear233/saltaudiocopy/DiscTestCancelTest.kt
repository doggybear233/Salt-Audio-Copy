package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.DiscTest
import com.doggybear233.saltaudiocopy.rip.FakeTrackSource
import com.doggybear233.saltaudiocopy.rip.ScanResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 批次 07：只测不写的取消必须能被上层看见（以前会假装测试完成）。 */
class DiscTestCancelTest {

    private fun scan() = ScanResult("\\\\.\\X:", FakeTrackSource.inquiry(), FakeTrackSource.disc3Tracks(6000))

    private fun run(cancel: () -> Boolean) = DiscTest.run(
        scan = scan(),
        toc = FakeTrackSource.disc3Tracks(6000),
        cancel = cancel,
        onStage = {},
        verify = false,
        maxRetries = 1,
        retryDelayMs = 0,
        useC2 = false,
        source = FakeTrackSource(sectors = 6000)
    )

    @Test
    fun cancelledBeforeFirstTrackReportsCancellation() {
        val report = run { true }
        assertTrue(report.cancelled, "取消必须被报告")
        assertEquals(0, report.rows.size, "取消后不该有行")
    }

    @Test
    fun normalRunIsNotMarkedCancelled() {
        val report = run { false }
        assertFalse(report.cancelled)
        assertEquals(3, report.rows.size)
    }
}
