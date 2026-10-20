package com.saltrip.rip

import com.saltrip.rip.scsi.C2
import com.saltrip.rip.scsi.CdDevice
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真机 C2 读取：需要一张音频 CD（无光盘时自动 skip）。
 * 校验三点：带 C2 位可用、干净盘片 C2 全零、C2 缓冲里的 PCM 与普通读取逐字节一致。
 */
class C2LiveTest {

    private fun deviceOrSkip(): CdDevice? {
        val dev = runCatching { CdDevice("\\\\.\\F:") }.getOrNull()
        assumeTrue(dev != null, "没有可用光驱 F:")
        val toc = runCatching { dev!!.readToc() }.getOrNull()
        assumeTrue(toc != null && toc.isAudioCd, "光驱里没有音频 CD")
        return dev
    }

    @Test
    fun c2ReadMatchesPlainRead() {
        HwGate.assumeMedia()
        val dev = deviceOrSkip() ?: return
        dev.use {
            val lba = 0
            val n = 8
            val plain = it.readAudioSectors(lba, n)
            val withC2 = it.readAudioSectorsC2(lba, n)
            assertEquals(C2.bufferSize(n), withC2.size)
            val errors = C2.sectorErrorBytes(withC2, n)
            assertTrue(errors.all { e -> e == 0 }, "干净盘片不应报告 C2 错误: ${errors.toList()}")
            val pcm = C2.extractPcm(withC2, n)
            assertEquals(plain.size, pcm.size)
            assertTrue(plain.contentEquals(pcm), "C2 读取的 PCM 必须与普通读取一致")
        }
    }
}
