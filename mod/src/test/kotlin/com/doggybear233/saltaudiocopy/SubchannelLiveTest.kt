package com.doggybear233.saltaudiocopy

import org.junit.jupiter.api.Tag
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.scsi.Subchannel
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真机子通道验证：Q 通道自带 CRC，CRC 正确说明「反交织 + 字段解析」整套实现没有错位。
 * 同时验证 Q 里的绝对地址与请求的 LBA 一致。
 */
@Tag("live")
class SubchannelLiveTest {

    @Test
    fun qChannelCrcValidAndAddressMatchesLba() {
        val dev = runCatching { CdDevice("\\\\.\\F:") }.getOrNull()
        assumeTrue(dev != null, "没有可用光驱 F:")
        val toc = runCatching { dev!!.readToc() }.getOrNull()
        assumeTrue(toc != null && toc.isAudioCd, "光驱里没有音频 CD")
        val device = dev ?: return
        val audioToc = toc ?: return

        device.use {
            val targetLba = audioToc.tracks.first().lba + 500
            val q = it.readQ(targetLba)
            assertEquals(1, q.track, "第 $targetLba 扇区应属于第 1 轨（Q.track 字段）")
            assertTrue(q.isAudio, "第 1 轨应是音频轨")
            assertTrue(
                q.crcValid,
                "Q 通道 CRC 必须自洽：stored=0x%04X computed=0x%04X".format(q.crcStored, q.crcComputed)
            )
            val qLba = Subchannel.absToLba(q.absMsf)
            assertTrue(qLba == targetLba, "Q 的绝对地址 $qLba 应等于请求的 LBA $targetLba")
        }
    }
}
