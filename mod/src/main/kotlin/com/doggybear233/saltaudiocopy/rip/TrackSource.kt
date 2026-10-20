package com.doggybear233.saltaudiocopy.rip

import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.scsi.Subchannel
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

    /**
     * 在这条「真正用来读数据」的句柄上设置读速上限（KB/s）。
     *
     * 必须每开一次句柄就设一次：很多驱动器在句柄关闭/重新打开后会把速度复位到默认值，
     * 之前只在抓轨开始前用临时句柄设一次，8x / 16x 的选择常常等于没生效。
     */
    fun setSpeed(kbps: Int) {}

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
    override fun setSpeed(kbps: Int) = dev.setSpeed(kbps)
    override fun close() = dev.close()
}
