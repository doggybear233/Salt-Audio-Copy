package com.saltrip.rip.rip

import com.saltrip.rip.scsi.CdDevice
import com.saltrip.rip.scsi.Subchannel
import java.lang.foreign.MemorySegment

/**
 * 抓轨需要的最小驱动器接口。
 *
 * 存在的唯一理由：让整条抓轨流水线可以在没有光驱的情况下被测试。
 * 生产路径永远是 CdTrackSource（包着 CdDevice）；测试路径是 FakeTrackSource。
 */
interface TrackSource : AutoCloseable {
    val devicePath: String
    val maxSectorsPerRequest: Int
    fun readAudioSectors(lba: Int, count: Int): ByteArray
    fun readAudioSectorsInto(lba: Int, count: Int, buf: MemorySegment): Int
    fun readAudioSectorsC2(lba: Int, count: Int): ByteArray
    fun readQ(lba: Int): Subchannel.Q
    override fun close() {}
}

/** 生产实现：把 CdDevice 桥到 TrackSource。 */
class CdTrackSource(private val dev: CdDevice) : TrackSource {
    override val devicePath: String get() = dev.path
    override val maxSectorsPerRequest: Int get() = dev.maxSectorsPerRequest
    override fun readAudioSectors(lba: Int, count: Int) = dev.readAudioSectors(lba, count)
    override fun readAudioSectorsInto(lba: Int, count: Int, buf: MemorySegment) =
        dev.readAudioSectorsInto(lba, count, buf)
    override fun readAudioSectorsC2(lba: Int, count: Int) = dev.readAudioSectorsC2(lba, count)
    override fun readQ(lba: Int) = dev.readQ(lba)
    override fun close() = dev.close()
}
