package com.saltrip.rip.scsi

import com.saltrip.rip.Diag

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

data class InquiryData(
    val vendor: String,
    val product: String,
    val revision: String,
    val peripheralType: Int
) {
    val isCdRom: Boolean get() = peripheralType == 0x05
    override fun toString() = "$vendor $product (rev $revision)"
}

/**
 * 一个 CD/DVD 驱动器的 SPTI 句柄。
 * 语义与 EAC 的单请求记录一致：所有命令串行执行，一个设备一个句柄。
 */
class CdDevice(
    val path: String,
    preferred: ReadCommand? = ReadCommandPreference.preferred,
    private val allowCommandFallback: Boolean = true
) : com.saltrip.rip.rip.TrackSource {

    private val handle: MemorySegment = Win32.openDevice(path, writable = true)
    @Volatile
    private var closed = false

    val sectorSize: Int = 2352
    override val devicePath: String get() = path

    override val maxSectorsPerRequest: Int = 16

    fun inquiry(): InquiryData {
        val data = Sptd.execute(handle, cdb(0x12, 0x00, 0x00, 0x00, 0x24, 0x00), 36)
        fun str(from: Int, to: Int) = String(data, from, to - from, Charsets.US_ASCII).trim()
        return InquiryData(str(8, 16), str(16, 32), str(32, 36), data[0].toInt() and 0x1F)
    }

    /** READ TOC format 0 + MSF。 */
    fun readToc(): DiscToc {
        val data = Sptd.execute(
            handle,
            cdb(0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x24, 0x00),
            804
        )
        return DiscToc.parse(data)
    }

    /**
     * READ TOC format 5（CD-TEXT）。返回解析结果；盘上无 CD-TEXT 时驱动回 `00 02` 全零，
     * 由 [CdText.parse] 判为 null。
     *
     * 分配长度先试 8 KiB（CD-TEXT 上限约 4 KiB），部分驱动对超大分配回 ILLEGAL REQUEST，
     * 失败就降级到 2 KiB 再试一次。
     */
    fun readCdText(): CdText.Info? {
        for (alloc in intArrayOf(0x2000, 0x0800)) {
            val data = try {
                Sptd.execute(
                    handle,
                    cdb(
                        0x43, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00,
                        (alloc ushr 8) and 0xFF, alloc and 0xFF, 0x00
                    ),
                    alloc
                )
            } catch (e: Exception) {
                com.saltrip.rip.Diag.log("readCdText alloc=$alloc failed: ${e.message}")
                continue
            }
            CdText.parse(data)?.let { return it }
        }
        return null
    }

    /** 命令候选状态机：驱动不认某条读命令时自动换下一种（见 [ReadCommandPlan]）。 */
    private val plan = ReadCommandPlan(preferred, allowCommandFallback) { Diag.log(it) }

    /** 本次会话实际生效的读命令（换过之后会变）。 */
    val readCommand: ReadCommand get() = plan.current

    /**
     * 读取 count 个音频扇区（≤16），返回 count*2352 字节的 PCM。
     * 命令级错误（驱动不认识这条 CDB）会依次退到备用命令；真正的读错误直接抛。
     */
    override fun readAudioSectors(lba: Int, count: Int): ByteArray {
        require(count in 1..maxSectorsPerRequest) { "count=$count 超出单次上限 $maxSectorsPerRequest" }
        while (true) {
            try {
                return Sptd.execute(handle, plan.current.cdb(lba, count), count * sectorSize)
            } catch (e: ScsiException) {
                if (!plan.advanceAfter(e)) throw e
            }
        }
    }

    /** 复用缓冲区读取，避免每块分配。返回实际字节数。 */
    override fun readAudioSectorsInto(lba: Int, count: Int, buf: MemorySegment): Int {
        while (true) {
            try {
                return Sptd.executeInto(handle, plan.current.cdb(lba, count), buf, count * sectorSize)
            } catch (e: ScsiException) {
                if (!plan.advanceAfter(e)) throw e
            }
        }
    }

    /**
     * 带 C2 读取：返回 `count × 2610` 字节（每扇区 2352 数据 + 294 C2）。
     * C2 位要求 CDB[9] bit1，本机实测 `0xFA` 可用（`0xF8` 不带 C2）。
     */
    override fun readAudioSectorsC2(lba: Int, count: Int): ByteArray {
        require(count in 1..maxSectorsPerRequest) { "count=$count 超出单次上限 $maxSectorsPerRequest" }
        val cdb = ByteArray(12)
        cdb[0] = 0xBE.toByte()
        cdb[1] = 0x04.toByte()
        cdb[2] = ((lba ushr 24) and 0xFF).toByte()
        cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()
        cdb[8] = count.toByte()
        cdb[9] = 0xFA.toByte()                 // 数据 + C2 + 子通道
        return Sptd.execute(handle, cdb, count * C2.STRIDE)
    }

    /**
     * 读 [count] 个扇区的用户数据 + **Q-only 子通道**（每扇区 2352 + 16 = 2368 字节）。
     *
     * CDB[9] = `0xF8`（用户数据 + Sync + Header），**CDB[10] = 2 表示只取 Q 子通道**。
     * 实测本驱动：`CDB[10]=1`（raw 96 字节，需位平面反交织）返回的多为 0，不可用；
     * `CDB[10]=2` 直接给出 16 字节记录，前 12 字节就是 Q（后 4 字节填充），无需反交织。
     */
    fun readSectorsWithQSubchannel(lba: Int, count: Int): ByteArray {
        require(count in 1..maxSectorsPerRequest) { "count=$count 超出单次上限 $maxSectorsPerRequest" }
        val cdb = ByteArray(12)
        cdb[0] = 0xBE.toByte()
        cdb[1] = 0x04.toByte()
        cdb[2] = ((lba ushr 24) and 0xFF).toByte()
        cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()
        cdb[8] = count.toByte()
        cdb[9] = 0xF8.toByte()
        cdb[10] = 0x02.toByte()                // Q-only subchannel
        return Sptd.execute(handle, cdb, count * Subchannel.Q_STRIDE)
    }

    /** 读单个扇区的 Q 子通道（gap 探测用）。 */
    override fun readQ(lba: Int): Subchannel.Q =
        Subchannel.parseQ(Subchannel.qOnlySlice(readSectorsWithQSubchannel(lba, 1), 0))

    /** 直接下发任意 CDB（诊断/实验用）。 */
    fun rawCommand(cdb: ByteArray, readSize: Int): ByteArray = Sptd.execute(handle, cdb, readSize)

    /** TEST UNIT READY。 */    fun testUnitReady(): Boolean = runCatching {
        Sptd.execute(handle, cdb(0x00, 0, 0, 0, 0, 0), 0)
        true
    }.getOrDefault(false)

    /** SET CD SPEED（0xBB）；0xFFFF 表示"不改变"。 */
    fun setSpeed(readKbps: Int, writeKbps: Int = 0xFFFF) {
        val c = ByteArray(12)
        c[0] = 0xBB.toByte()
        c[2] = ((readKbps ushr 8) and 0xFF).toByte()
        c[3] = (readKbps and 0xFF).toByte()
        c[4] = ((writeKbps ushr 8) and 0xFF).toByte()
        c[5] = (writeKbps and 0xFF).toByte()
        Sptd.execute(handle, c, 0)
    }

    /**
     * 弹出托盘：START STOP UNIT（0x1B），byte4 的 bit1 = LoEj。
     * immed=1 让人机交互不必等机械动作完成（不然会卡住整个抓轨线程）。
     */
    fun eject() {
        Sptd.execute(handle, cdb(0x1B, 0x01, 0, 0, 0x02, 0), 0)
    }

    /** 收回托盘（LoEj=1 且 Start=1）。 */
    fun load() {
        Sptd.execute(handle, cdb(0x1B, 0x01, 0, 0, 0x03, 0), 0)
    }

    /** PREVENT/ALLOW MEDIUM REMOVAL（0x1E）：true 防止用户中途按弹出键。 */
    fun preventRemoval(prevent: Boolean) {
        Sptd.execute(handle, cdb(0x1E, 0, 0, 0, if (prevent) 1 else 0, 0), 0)
    }

    private fun cdb(vararg b: Int): ByteArray = ByteArray(b.size) { (b[it] and 0xFF).toByte() }

    override fun close() {
        if (closed) return
        closed = true
        Win32.close(handle)
    }

    companion object {
        /** 枚举 `\\.\A:` … `\\.\Z:` 中能被 SPTI 打开且报告为 CD-ROM 的设备。 */
        fun scanDrives(): List<Pair<String, InquiryData>> {
            val out = ArrayList<Pair<String, InquiryData>>()
            for (c in 'D'..'Z') {
                // 先用 GetDriveTypeW 过滤：只碰真正的光驱，避免枚举硬盘/网络盘导致长时间无响应
                if (Win32.driveType(c) != 5) continue
                val path = "\\\\.\\$c:"
                val dev = runCatching { CdDevice(path) }.getOrNull() ?: continue
                try {
                    val inq = dev.inquiry()
                    if (inq.isCdRom) out += path to inq
                } catch (_: Exception) {
                    // 无介质或非光驱
                } finally {
                    dev.close()
                }
            }
            return out
        }
    }
}

/** 供抓轨线程复用的大缓冲。 */
class SectorBuffer(sectors: Int) : AutoCloseable {
    private val arena: Arena = Arena.ofConfined()
    val segment: MemorySegment = arena.allocate((sectors * 2352).toLong(), 8)
    val size: Int = sectors * 2352
    override fun close() = arena.close()
}
