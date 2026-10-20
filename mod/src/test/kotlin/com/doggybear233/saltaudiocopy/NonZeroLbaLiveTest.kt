package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.rip.RipEngine
import com.doggybear233.saltaudiocopy.scsi.C2
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 非零 LBA 的交叉校验：同一轨用两条**独立的 CDB 构造路径**读，字节必须一致。
 *
 * 存在理由：0.14.0 起 READ CD 的 LBA 字段被写成 3 字节，`lba=16` 实际请求 4096，
 * 驱动在 lead-out 之前不报错、只是给错扇区——单轨抓第 1 轨永远正确，跨轨/整盘必错。
 * 这条用例选第 2 轨（起始 LBA 远大于 0），并且不经过主路径的 `ReadCommand`，
 * 用 C2 读取（另一份 CDB 构造）取 PCM 与主路径对账。
 */
class NonZeroLbaLiveTest {

    @Test
    fun trackTwoPcmAgreesAcrossTwoCdbPaths() {
        HwGate.assumeMedia()
        val scan = runCatching {
            CdDevice("\\\\.\\F:").use { dev ->
                val toc = dev.readToc()
                if (!toc.isAudioCd || toc.tracks.size < 2) return@use null
                com.doggybear233.saltaudiocopy.rip.ScanResult("\\\\.\\F:", dev.inquiry(), toc)
            }
        }.getOrNull()
        org.junit.jupiter.api.Assumptions.assumeTrue(scan != null, "没有可用的音频 CD（≥2 轨）")

        val toc = scan!!.toc
        val lba = toc.tracks[1].lba
        assertTrue(lba > 0, "第 2 轨的起始 LBA 必须大于 0，否则这条用例没有意义")

        val tmp = Files.createTempDirectory("saltac-lba").resolve("track2.wav")
        val main = RipEngine.ripTrack(scan, 1, tmp, { false }, { }, verify = false, writeFile = false)

        val crc = CRC32()
        var done = 0
        CdDevice(scan.path).use { dev ->
            while (done < main.sectors) {
                val n = minOf(16, main.sectors - done)
                val raw = dev.readAudioSectorsC2(lba + done, n)
                crc.update(C2.extractPcm(raw, n))
                done += n
            }
        }

        println("[LBA-CROSSCHECK] track2 lba=%d sectors=%d crc32=0x%08X".format(lba, main.sectors, main.crc32))
        assertEquals(
            crc.value, main.crc32,
            "两条 CDB 路径在第 2 轨（lba=$lba，${main.sectors} 扇区）上读出的 PCM 必须一致"
        )
    }
}
