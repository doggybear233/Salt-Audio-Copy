package com.saltrip.rip

import com.saltrip.rip.album.UNLIMITED_KBPS
import com.saltrip.rip.album.speedRequestKbps
import com.saltrip.rip.scsi.CdDevice
import com.saltrip.rip.rip.FakeTrackSource
import com.saltrip.rip.rip.RipEngine
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用户实测（G10）：把限速改成「不限速」后，速度仍然不到 4x。
 *
 * 两个原因：
 * ① 每条 READ CD 只取 16 扇区，命令开销吃掉了带宽；
 * ② 「不限速」时根本没下发 SET CD SPEED（0 = 跳过），驱动器停在默认低速。
 */
class ReadSpeedTest {

    @Test
    fun unlimitedAsksTheDriveForItsMaximum() {
        assertEquals(UNLIMITED_KBPS, speedRequestKbps(0))
        assertTrue(UNLIMITED_KBPS > 7_000, "要高于任何光驱的上限，让驱动器自己钳制")
    }

    @Test
    fun explicitLimitIsSentAsIs() {
        assertEquals(1200, speedRequestKbps(1200))
        assertEquals(3600, speedRequestKbps(3600))
    }

    @Test
    fun chunksAreBigEnoughToNotBeCommandBound() {
        // 27 扇区 = 63 KB/命令；16 扇区时实测把 8x 压到 4x 以下
        assertTrue(CdDevice.MAX_SECTORS >= 27, "单次读取扇区数太小：${CdDevice.MAX_SECTORS}")
    }

    /**
     * 关键回归：读速必须设在「真正读数据的那条句柄」上。
     *
     * 0.41.1 之前只在抓轨开始前用一个临时句柄 setSpeed，句柄一关驱动器就复位，
     * 于是 8x / 16x 的选择等于没设。
     */
    @Test
    fun everyReadHandleGetsTheSpeedLimit() {
        val src = FakeTrackSource(sectors = 6000, maxSectorsPerRequest = 27)
        val out = Files.createTempDirectory("speed").resolve("t.wav")
        RipEngine.ripTrack(
            scan = com.saltrip.rip.rip.ScanResult("\\\\.\\X:", FakeTrackSource.inquiry(), FakeTrackSource.disc3Tracks()),
            trackIndex = 0,
            outFile = out,
            cancel = { false },
            onProgress = { },
            speedKbps = 2400,
            source = src
        )
        assertTrue(src.speedRequests.isNotEmpty(), "读句柄上从来没有下发过读速")
        assertTrue(src.speedRequests.all { it == 2400 }, "读速请求不对：${src.speedRequests}")
    }
}
