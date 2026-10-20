package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.rip.RipEngine
import com.doggybear233.saltaudiocopy.rip.ScanResult
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test & Copy 的硬件验证：同一轨用「只读不写」的测试通道与正式写入通道各抓一次，
 * 两者的 CRC32 必须相同——这是 Test&Copy 功能成立的前提。
 */
@Tag("live")
class TestCopyLiveTest {

    private fun scanOrSkip(): ScanResult? {
        val dev = runCatching { CdDevice("\\\\.\\F:") }.getOrNull()
        assumeTrue(dev != null, "没有可用光驱 F:")
        val toc = runCatching { dev!!.readToc() }.getOrNull()
        assumeTrue(toc != null && toc.isAudioCd, "光驱里没有音频 CD")
        val inquiry = runCatching { dev!!.inquiry() }.getOrNull()
        assumeTrue(inquiry != null, "INQUIRY 失败")
        return ScanResult("\\\\.\\F:", inquiry!!, toc!!)
    }

    @Test
    fun testPassCrcEqualsCopyPassCrc() {
        HwGate.assumeMedia()
        val scan = scanOrSkip() ?: return
        val tmp = Files.createTempDirectory("saltac-testcopy").resolve("track1.wav")

        val testPass = RipEngine.ripTrack(
            scan, 0, tmp, { false }, { },
            verify = false, maxRetries = 4, retryDelayMs = 80,
            useC2 = false, writeFile = false
        )
        val copyPass = RipEngine.ripTrack(
            scan, 0, tmp, { false }, { },
            verify = false, maxRetries = 4, retryDelayMs = 80,
            useC2 = false, writeFile = true
        )

        assertEquals(testPass.sectors, copyPass.sectors, "两次读取的扇区数应一致")
        assertEquals(
            testPass.crc32, copyPass.crc32,
            "测试通道与复制通道 CRC 必须一致（test=0x%08X copy=0x%08X）"
                .format(testPass.crc32, copyPass.crc32)
        )
        Files.deleteIfExists(tmp)
    }
}
