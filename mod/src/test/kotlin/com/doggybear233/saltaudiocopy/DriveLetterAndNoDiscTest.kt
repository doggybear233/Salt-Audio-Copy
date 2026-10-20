package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.scsi.ScsiException
import com.doggybear233.saltaudiocopy.scsi.ScsiMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 盘符推导与「有没有盘」的判定。
 *
 * 两件事都属于「看起来只是显示问题、实际上改错了会写坏设置」的一类：
 * - 盘符推错（`\\.\F:` 取到 `':'`）会让多光驱切换把 `":"` 写进设置；
 * - 「没盘」判错会让换盘监视进入指数退避，插盘后几十秒都不识别。
 */
class DriveLetterAndNoDiscTest {

    @Test
    fun driveLetterComesFromTheDevicePath() {
        assertEquals("F", ConfigActions.driveLetterOf("\\\\.\\F:"))
        assertEquals("E", ConfigActions.driveLetterOf("\\\\.\\e:"))
        assertEquals("F", ConfigActions.driveLetterOf("\\\\.\\F:\\"))
        assertEquals("D", ConfigActions.driveLetterOf("\\\\.\\d"))
    }

    @Test
    fun unknownDevicePathsYieldNoLetter() {
        assertEquals("", ConfigActions.driveLetterOf("\\\\.\\"))
        assertEquals("", ConfigActions.driveLetterOf(""))
        // 卷 GUID 路径没有单字母盘符：宁可留空（表示「自动」），也不要猜出一个错字母。
        assertEquals("", ConfigActions.driveLetterOf("\\\\?\\Volume{8f0a1b2c-0000-0000-0000-100000000000}\\"))
        assertEquals("", ConfigActions.driveLetterOf("\\\\.\\CdRom0"))
    }

    @Test
    fun win32NotReadyIsTreatedAsNoDisc() {
        // ERROR_NOT_READY(21) 是「驱动器里没盘」的另一条常见路径（打不开设备时拿到）。
        assertTrue(ScsiMessages.isNoDisc(ScsiException("open failed", win32Error = 21)))
        assertFalse(ScsiMessages.isNoDisc(ScsiException("open failed", win32Error = 2)))
    }

    @Test
    fun mediumMissingAndBecomingReadyCountAsNoDisc() {
        assertTrue(ScsiMessages.isNoDisc(ScsiException("no medium", senseKey = 0x02, asc = 0x3A)))
        assertTrue(ScsiMessages.isNoDisc(ScsiException("not ready", senseKey = 0x02, asc = 0x04)))
        assertTrue(ScsiMessages.isNoDisc(ScsiException("bad position", senseKey = 0x02, asc = 0x3C)))
    }

    @Test
    fun dataDiscIsNotAnEmptyDrive() {
        assertFalse(
            ScsiMessages.isNoDisc(ScsiException("incompatible", senseKey = 0x05, asc = 0x30, ascq = 0x02)),
            "数据盘要作为错误报出来，不能静默当空盘"
        )
    }

    @Test
    fun causeChainIsFollowed() {
        val wrapped = IllegalStateException("读不到光盘信息", ScsiException("no medium", senseKey = 0x02, asc = 0x3A))
        assertTrue(ScsiMessages.isNoDisc(wrapped), "包装层不应影响判定")
    }
}
