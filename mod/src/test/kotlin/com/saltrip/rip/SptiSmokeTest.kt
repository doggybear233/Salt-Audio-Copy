package com.saltrip.rip

import com.saltrip.rip.scsi.CdDevice
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真机冒烟测试：需要 F: 上有音频 CD。没有设备、无介质或非音频 CD 时自动 skip。
 * 作用是在导入宿主之前，先用同一份 FFM 代码验证 SPTI 链路。
 */
class SptiSmokeTest {

    private fun open(): CdDevice? = runCatching { CdDevice("\\\\.\\F:") }.getOrNull()

    /** 读盘用例的前置：介质必须是 CD-DA；数据盘会被驱动器拒绝，属于正常现象。 */
    private fun assumeAudioCd(dev: CdDevice) {
        val toc = runCatching { dev.readToc() }.getOrNull()
        Assumptions.assumeTrue(toc != null && toc.isAudioCd, "介质不是音频 CD，跳过读取用例")
    }

    @Test
    fun inquiryAndToc() {
        HwGate.assumeMedia()
        val dev = open()
        Assumptions.assumeTrue(dev != null, "F: 不可用")
        dev!!.use {
            val inq = it.inquiry()
            println("INQUIRY: $inq peripheral=0x${inq.peripheralType.toString(16)}")
            assertTrue(inq.isCdRom, "F: 应报告为 CD-ROM")

            val toc = it.readToc()
            println(
                "TOC: ${toc.tracks.size} tracks, first=${toc.firstTrack} last=${toc.lastTrack} " +
                    "leadOut=${toc.leadOutLba} audioCd=${toc.isAudioCd}"
            )
            toc.tracks.forEach { t -> println("  #${t.track} lba=${t.lba} audio=${t.isAudio} ${t.msf}") }
            assertTrue(toc.tracks.isNotEmpty(), "TOC 不应为空")
            Assumptions.assumeTrue(toc.isAudioCd, "介质不是音频 CD，跳过后续断言")
            assertTrue(toc.leadOutLba > 0, "lead-out 应 > 0")
        }
    }

    @Test
    fun readOneSector() {
        HwGate.assumeMedia()
        val dev = open()
        Assumptions.assumeTrue(dev != null, "F: 不可用")
        dev!!.use {
            assumeAudioCd(it)
            // 有的盘开头若干扇区是数字静音（全 0），取样要避开起始扇区
            val data = it.readAudioSectors(75, 1)
            assertEquals(2352, data.size)
            val nonzero = data.count { b -> b.toInt() != 0 }
            println("sector@75: ${data.size} bytes, non-zero=$nonzero")
            assertTrue(nonzero > 0, "LBA 75 不应全 0")
        }
    }

    @Test
    fun readFullChunk() {
        HwGate.assumeMedia()
        val dev = open()
        Assumptions.assumeTrue(dev != null, "F: 不可用")
        dev!!.use {
            assumeAudioCd(it)
            val n = 16
            val data = it.readAudioSectors(1000, n)
            assertEquals(n * 2352, data.size)
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            println("16 sectors @1000: ${data.size} bytes, crc32=${crc.toString(16)}")
            assertTrue(data.any { b -> b.toInt() != 0 })
        }
    }

    /** 双读校验路径：两次独立读同区一致，且 verify 返回的数据与单读一致。 */
    @Test
    fun secureVerifyAgreesWithPlainRead() {
        HwGate.assumeMedia()
        val dev = open()
        Assumptions.assumeTrue(dev != null, "F: 不可用")
        dev!!.use {
            assumeAudioCd(it)
            val n = 16
            val lba = 1000
            val a = ByteArray(n * 2352)
            val b = ByteArray(n * 2352)
            var retries = 0
            com.saltrip.rip.scsi.SectorBuffer(n).use { s1 ->
                com.saltrip.rip.scsi.SectorBuffer(n).use { s2 ->
                    val len = com.saltrip.rip.rip.RipEngine.readCompare(
                        it, s1.segment, s2.segment, lba, n, a, b,
                        verify = true, maxRetries = 3, retryDelayMs = 50, onRetry = { retries++ }
                    )
                    assertEquals(n * 2352, len)
                    val plain = it.readAudioSectors(lba, n)
                    assertTrue(a.copyOf(len).contentEquals(plain), "verify 结果应与单读一致")
                    println("verify ok: $len bytes, retries=$retries")
                }
            }
        }
    }
}
