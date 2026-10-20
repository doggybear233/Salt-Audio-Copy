package com.saltrip.rip

import com.saltrip.rip.scsi.ScsiException
import com.saltrip.rip.scsi.ScsiMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SCSI 错误码 → 人话。用户只该看到结论，原始码进日志。 */
class ScsiMessagesTest {

    private fun e(key: Int, asc: Int, ascq: Int = 0, win32: Int = 0) =
        ScsiException("raw", win32Error = win32, scsiStatus = if (win32 == 0) 2 else 0, senseKey = key, asc = asc, ascq = ascq)

    @Test
    fun noMedium() {
        val s = ScsiMessages.human(e(0x02, 0x3A))
        assertTrue(s.contains("没有光盘"), s)
    }

    @Test
    fun notReadyYet() {
        val s = ScsiMessages.human(e(0x02, 0x04))
        assertTrue(s.contains("还没准备好"), s)
    }

    @Test
    fun discChanged() {
        val s = ScsiMessages.human(e(0x06, 0x28))
        assertTrue(s.contains("已经更换"), s)
    }

    @Test
    fun dataDiscIsExplainedAsNotAudioCd() {
        val s = ScsiMessages.human(e(0x05, 0x30, 0x02))
        assertTrue(s.contains("不是音频 CD"), s)
    }

    @Test
    fun illegalModeIsExplained() {
        val s = ScsiMessages.human(e(0x05, 0x64))
        assertTrue(s.contains("读取方式"), s)
    }

    @Test
    fun readErrorMentionsSurfaceDamage() {
        val s = ScsiMessages.human(e(0x03, 0x11))
        assertTrue(s.contains("无法纠正"), s)
    }

    @Test
    fun unknownCombinationStillSaysSomethingUseful() {
        val s = ScsiMessages.human(e(0x0B, 0x55, 0x01))
        assertTrue(s.contains("命令被中止"), s)
        assertTrue(s.contains("0x55"), s)
    }

    @Test
    fun win32ErrorAloneIsMapped() {
        val s = ScsiMessages.human(ScsiException("raw", win32Error = 21, scsiStatus = 0))
        assertTrue(s.contains("没有光盘"), s)
    }

    @Test
    fun win32ParameterErrorIsAdmittedAsOurBug() {
        val s = ScsiMessages.human(ScsiException("raw", win32Error = 87, scsiStatus = 0))
        assertTrue(s.contains("插件的 bug"), s)
    }

    @Test
    fun nonScsiThrowableKeepsItsMessage() {
        assertEquals("磁盘满了", ScsiMessages.human(IllegalStateException("磁盘满了")))
        assertFalse(ScsiMessages.human(RuntimeException("boom")).isBlank())
        assertFalse(ScsiMessages.human(IllegalStateException()).isBlank(), "没有 message 也要给出类型名")
    }
}
