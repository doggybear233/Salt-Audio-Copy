package com.saltrip.rip.rip

import com.saltrip.rip.scsi.DiscToc
import com.saltrip.rip.scsi.InquiryData
import com.saltrip.rip.scsi.Subchannel
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * 假驱动器：按 LBA 生成确定性的扇区内容，可注入读错误、校验不一致、C2 错误与 Q 子通道。
 *
 * 这是「没有光驱也能测整条抓轨流水线」的关键。它只实现 [TrackSource]，
 * 不模拟 IOCTL 细节——那些已经在 Sptd 层被硬件验证过了。
 */
class FakeTrackSource(
    override val devicePath: String = "\\\\.\\X:",
    private val sectors: Int,
    override val maxSectorsPerRequest: Int = 16,
    /** 该 LBA 的首次读取抛 IO 异常（模拟需要重试的抖动）。 */
    private val failFirstRead: MutableSet<Int> = mutableSetOf(),
    /** 该 LBA 的首次读取返回错误内容（模拟需要 verify 才能发现的不一致）。 */
    private val corruptFirstRead: MutableSet<Int> = mutableSetOf(),
    /** 报告 C2 错误的 LBA。 */
    private val c2Bad: MutableSet<Int> = mutableSetOf(),
    /** 模拟驱动器完全不支持 C2。 */
    private val c2Unsupported: Boolean = false,
    /** 返回 Q 子通道（index 号），null 表示不支持。 */
    private val qAt: (Int) -> Subchannel.Q? = { null }
) : TrackSource {

    var reads = 0
        private set

    /** 每次读的 (lba, 扇区数)，用于断言「击破缓存」插入的远端读。 */
    val log = mutableListOf<Pair<Int, Int>>()
    var c2Reads = 0
        private set
    var closed = false
        private set

    /** 第 n 个扇区的内容：可预测，便于独立重算 CRC。 */
    fun sector(lba: Int): ByteArray {
        val b = ByteArray(2352)
        for (i in b.indices) b[i] = ((lba * 7 + i * 3) and 0xFF).toByte()
        return b
    }

    private fun block(lba: Int, count: Int): ByteArray {
        require(count in 1..maxSectorsPerRequest) { "count=$count" }
        require(lba >= 0 && lba + count <= sectors) { "lba=$lba count=$count 越界（共 $sectors 扇区）" }
        val out = ByteArray(count * 2352)
        for (k in 0 until count) sector(lba + k).copyInto(out, k * 2352)
        return out
    }

    override fun readAudioSectors(lba: Int, count: Int): ByteArray {
        reads++
        log += lba to count
        if (failFirstRead.remove(lba)) throw java.io.IOException("fake read error at lba=$lba")
        val data = block(lba, count)
        if (corruptFirstRead.remove(lba)) data[0] = (data[0] + 1).toByte()
        return data
    }

    override fun readAudioSectorsInto(lba: Int, count: Int, buf: MemorySegment): Int {
        val data = readAudioSectors(lba, count)
        MemorySegment.copy(data, 0, buf, ValueLayout.JAVA_BYTE, 0L, data.size)
        return data.size
    }

    override fun readAudioSectorsC2(lba: Int, count: Int): ByteArray {
        c2Reads++
        if (c2Unsupported) throw IllegalStateException("fake drive 不支持 C2")
        val out = ByteArray(count * com.saltrip.rip.scsi.C2.STRIDE)
        for (k in 0 until count) {
            sector(lba + k).copyInto(out, k * com.saltrip.rip.scsi.C2.STRIDE)
            if (lba + k in c2Bad) {
                // C2 块的第 0 字节是错误位图起点：置 1 表示该扇区有错
                out[k * com.saltrip.rip.scsi.C2.STRIDE + com.saltrip.rip.scsi.C2.DATA] = 1
            }
        }
        return out
    }

    override fun readQ(lba: Int): Subchannel.Q =
        qAt(lba) ?: throw IllegalStateException("fake drive 不支持 Q 子通道")

    override fun close() {
        closed = true
    }

    companion object {
        fun disc3Tracks(leadOut: Int = 6000): DiscToc = DiscToc(
            firstTrack = 1,
            lastTrack = 3,
            entries = listOf(
                com.saltrip.rip.scsi.TocEntry(1, true, 1, 0),
                com.saltrip.rip.scsi.TocEntry(2, true, 1, 2000),
                com.saltrip.rip.scsi.TocEntry(3, true, 1, 4000),
                com.saltrip.rip.scsi.TocEntry(0xAA, true, 1, leadOut)
            )
        )

        fun q(control: Int, track: Int, index: Int, absMsf: Int): Subchannel.Q =
            Subchannel.Q(control, 1, track, index, absMsf - 150, absMsf, 0, 0)

        fun inquiry() = InquiryData(
            "FAKE", "SaltRip Fake Drive", "1.0", 0x05
        )
    }
}
