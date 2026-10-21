package com.doggybear233.saltaudiocopy.scsi

import com.doggybear233.saltaudiocopy.Diag

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
class CdDevice private constructor(
    val path: String,
    private val transport: ScsiTransport,
    preferred: ReadCommand?,
    private val allowCommandFallback: Boolean
) : com.doggybear233.saltaudiocopy.rip.TrackSource {

    /** 真实设备：打开 SPTI 句柄。 */
    constructor(
        path: String,
        preferred: ReadCommand? = ReadCommandPreference.preferred,
        allowCommandFallback: Boolean = true
    ) : this(path, SptiTransport(Win32.openDevice(path, writable = true)), preferred, allowCommandFallback)

    /** 测试用：命令逻辑跑在内存假实现上，不碰真驱动器。 */
    internal constructor(
        transport: ScsiTransport,
        preferred: ReadCommand? = ReadCommandPreference.preferred,
        allowCommandFallback: Boolean = true
    ) : this("(fake)", transport, preferred, allowCommandFallback)

    @Volatile
    private var closed = false

    val sectorSize: Int = 2352
    override val devicePath: String get() = path

    /**
     * 单条 READ CD 最多取多少扇区。
     *
     * 16 太保守：约 37 KB/命令，命令开销比数据本身还大，把 8x 的盘压到 4x 以下。
     * 27 扇区（约 1/3 秒音频、63 KB）是 EAC / cdparanoia 的常用值；
     * 再大有些驱动器会直接 ILLEGAL REQUEST。
     */
    override val maxSectorsPerRequest: Int = MAX_SECTORS

    fun inquiry(): InquiryData {
        val data = transport.execute(cdb(0x12, 0x00, 0x00, 0x00, 0x24, 0x00), 36)
        fun str(from: Int, to: Int) = String(data, from, to - from, Charsets.US_ASCII).trim()
        return InquiryData(str(8, 16), str(16, 32), str(32, 36), data[0].toInt() and 0x1F)
    }

    /** READ TOC format 0 + MSF。 */
    fun readToc(): DiscToc {
        val data = transport.execute(
            cdb(0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x24, 0x00),
            804
        )
        val toc = DiscToc.parse(data)
        // 没有 lead-out 时 leadOutLba 只能是 0，末轨扇区会变成负数（拷出 0 字节）。
        // 与其让用户拿到一张空专辑，不如在这里明确报错。
        if (!toc.hasLeadOut) {
            throw ScsiException(
                "光盘 TOC 不完整：驱动器没有报告 lead-out 地址（音轨 ${toc.tracks.size} 条，末轨 LBA ${toc.tracks.lastOrNull()?.lba}）",
                senseKey = 0x05,
                asc = 0x57
            )
        }
        return toc
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
                transport.execute(
                    cdb(
                        0x43, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00,
                        (alloc ushr 8) and 0xFF, alloc and 0xFF, 0x00
                    ),
                    alloc
                )
            } catch (e: Exception) {
                com.doggybear233.saltaudiocopy.Diag.log("readCdText alloc=$alloc failed: ${e.message}")
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
     * 读取 count 个音频扇区，返回 count*2352 字节的 PCM。
     * 命令级错误（驱动不认识这条 CDB）会依次退到备用命令；真正的读错误直接抛。
     *
     * 若当前命令是 [ReadCommand.MMC_C2]，驱动会每扇区多回 294 字节 C2 信息；
     * 这里换成带 C2 的读法再剥出 PCM——按 2352 直接收会把 C2 字节当成下一扇区的音频。
     */
    override fun readAudioSectors(lba: Int, count: Int): ByteArray {
        require(count in 1..maxSectorsPerRequest) { "count=$count 超出单次上限 $maxSectorsPerRequest" }
        while (true) {
            val cmd = plan.current
            try {
                if (cmd == ReadCommand.MMC_C2) {
                    return C2.extractPcm(readAudioSectorsC2(lba, count), count)
                }
                if (cmd == ReadCommand.READ10) ensureRead10BlockSize()
                val expected = count * sectorSize
                return exact(cmd, expected, transport.execute(cmd.cdb(lba, count), expected))
            } catch (e: ScsiException) {
                if (!plan.advanceAfter(e)) throw e
            }
        }
    }

    /** 复用缓冲区读取，避免每块分配。返回实际字节数。 */
    override fun readAudioSectorsInto(lba: Int, count: Int, buf: MemorySegment): Int {
        require(count in 1..maxSectorsPerRequest) { "count=$count 超出单次上限 $maxSectorsPerRequest" }
        // 以前不校验容量：缓冲比请求小的时候 SPTI 会写到边界外，或者 COPY 抛 IndexOutOfBounds
        val need = count.toLong() * sectorSize
        require(buf.byteSize() >= need) {
            "目标缓冲太小：需要 $need 字节，只有 ${buf.byteSize()} 字节（count=$count）"
        }
        while (true) {
            val cmd = plan.current
            try {
                if (cmd == ReadCommand.MMC_C2) {
                    // 带 C2 时先读进缓冲（每扇区 2646），再原地把 PCM 挪到前部，避免多一次整块分配
                    val c2Size = C2.bufferSize(count).toLong()
                    if (buf.byteSize() >= c2Size) {
                        val got = transport.executeInto(cmd.cdb(lba, count), buf, c2Size.toInt())
                        if (got < c2Size.toInt()) {
                            throw ScsiException(
                                "短读：命令 ${cmd.id} 期望 $c2Size 字节，驱动只回了 $got 字节（盘面或线缆问题）",
                                senseKey = 0x03, asc = 0x11
                            )
                        }
                        val raw = ByteArray(c2Size.toInt())
                        MemorySegment.copy(buf, java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, raw, 0, raw.size)
                        val pcm = C2.extractPcm(raw, count)
                        MemorySegment.copy(pcm, 0, buf, java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, pcm.size)
                        return pcm.size
                    }
                    val pcm = C2.extractPcm(readAudioSectorsC2(lba, count), count)
                    MemorySegment.copy(pcm, 0, buf, java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, pcm.size)
                    return pcm.size
                }
                if (cmd == ReadCommand.READ10) ensureRead10BlockSize()
                val expected = count * sectorSize
                val got = transport.executeInto(cmd.cdb(lba, count), buf, expected)
                if (got != expected) {
                    throw ScsiException(
                        "短读：命令 ${cmd.id} 期望 $expected 字节，驱动只回了 $got 字节（盘面或线缆问题）",
                        senseKey = 0x03,
                        asc = 0x11
                    )
                }
                return got
            } catch (e: ScsiException) {
                if (!plan.advanceAfter(e)) throw e
            }
        }
    }

    /**
     * 校验驱动实际传输的字节数。
     *
     * SPTI 允许「请求 N 字节、只传 M < N 字节」而不报错（短读）。以前把返回值截断后照旧解析，
     * 短读的块会被当成正常 PCM 写进 WAV——CRC 会变、听感是爆音，而且完全不报错。
     */
    private fun exact(cmd: ReadCommand, expected: Int, data: ByteArray): ByteArray {
        if (data.size != expected) {
            throw ScsiException(
                "短读：命令 ${cmd.id} 期望 $expected 字节，驱动只回了 ${data.size} 字节（盘面或线缆问题）",
                senseKey = 0x03,
                asc = 0x11
            )
        }
        return data
    }

    private var blockLengthReady = false

    /**
     * READ(10)（0x28）本身不描述扇区类型，读出来多少字节取决于驱动器当前的块长度：
     * 音频盘默认往往是 2048（数据扇区），按 2352 收就是短读。
     *
     * 所以改用 READ(10) 之前先用 MODE SELECT(10) 把块长度设成 2352。
     * 以前这里只有一句「靠 MODE SELECT 设成 2352」的注释、代码里根本没有这条命令，
     * 于是退到 READ10 的驱动器必然短读或报 ILLEGAL REQUEST。
     */
    private fun ensureRead10BlockSize() {
        if (blockLengthReady) return
        val param = ByteArray(16)
        // MODE SELECT(10) 参数头 [0..1] = Mode Data Length = 参数总长 − 2。
        // 以前留 0：严格实现的驱动器（部分 USB/SATA 桥）会只消费 2 字节、丢掉块描述符，
        // 块长仍是 2048，于是第一个 READ(10) 就短读，而且 blockLengthReady 已经置位，
        // 后续重试全都跳过 MODE SELECT —— 整张盘在 READ10 模式下永久失败。
        param[0] = 0
        param[1] = (param.size - 2).toByte()
        param[6] = 0
        param[7] = 8                                  // 块描述符长度
        param[13] = ((sectorSize shr 16) and 0xFF).toByte()
        param[14] = ((sectorSize shr 8) and 0xFF).toByte()
        param[15] = (sectorSize and 0xFF).toByte()
        val cdb = ByteArray(12)
        cdb[0] = 0x55.toByte()                        // MODE SELECT(10)
        cdb[8] = param.size.toByte()                  // 参数列表长度（低字节）
        val written = transport.executeOut(cdb, param)
        // 部分写入（written < 参数长度）说明块描述符没送到，块长仍是 2048
        if (written < param.size) {
            throw IllegalStateException(
                "MODE SELECT(10) 只写入 $written / ${param.size} 字节，块长没有设置成功"
            )
        }

        // 回读确认改为「决定流程」：读回来不是 2352 就不要声称准备好了，
        // 让上层回退到 D8；以前只在日志里写一句，然后按已生效继续，最终表现为短读/CRC 全错。
        val len = runCatching { readBackBlockLength() }.getOrNull()
        if (len != null && len != sectorSize) {
            Diag.log("READ(10): 块长设置未生效（回读 $len，期望 $sectorSize），本次改用下一条读命令")
            throw ScsiException(
                "驱动器没有接受 2352 字节块长（回读 $len）",
                senseKey = 0x05, asc = 0x26
            )
        }
        blockLengthReady = true
        Diag.log("READ(10): block length set to $sectorSize" + if (len == null) "（驱动器不支持回读）" else "")
    }

    /** MODE SENSE(10) 读回当前块长；驱动器不支持时返回 null。 */
    private fun readBackBlockLength(): Int? {
        val sense = ByteArray(12)
        sense[0] = 0x5A.toByte()                      // MODE SENSE(10)
        sense[2] = 0x3F.toByte()                      // 所有页
        sense[7] = (64 shr 8).toByte()
        sense[8] = (64 and 0xFF).toByte()
        val back = transport.execute(sense, 64)
        if (back.size < 16) return null
        val bdl = (((back[6].toInt() and 0xFF) shl 8) or (back[7].toInt() and 0xFF))
        if (bdl < 8) return null
        return ((back[13].toInt() and 0xFF) shl 16) or
            ((back[14].toInt() and 0xFF) shl 8) or (back[15].toInt() and 0xFF)
    }

    /**
     * 带 C2 读取：返回 `count × 2646` 字节（每扇区 2352 用户数据 + 294 C2 信息）。
     *
     * CDB[9] = `0xFA` 的含义是「用户数据 + C2 错误信息」——**不含 96 字节子通道**；
     * 子通道要另外用 [readSectorsWithQSubchannel]。早先的注释写成「数据 + C2 + 子通道」，
     * 与 [C2.STRIDE] 的 2646 自相矛盾：照注释分配 2742 会多出 96 字节，剥 PCM 时整体错位。
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
        cdb[9] = 0xFA.toByte()
        val expected = count * C2.STRIDE
        return exact(ReadCommand.MMC_C2, expected, transport.execute(cdb, expected))
    }

    /**
     * 读 [count] 个扇区的用户数据 + **Q 子通道**。
     *
     * CDB[9] 的 bit2（0x04）是「要子通道」的总开关，CDB[10] 只选格式：
     * 这里用 `0xFC`（0xF8 | 0x04 = 用户数据 + Sync + Header + 子通道）+ `CDB[10]=0x02`（Q-only）。
     * 以前写 `0xF8`，等于按规范声明「不要子通道」，只在本机那台「只看 CDB[10]」的驱动器上成立。
     *
     * 步长不写死：用实际传输字节数反推 2352 / 2368 / 2448 三种情况，
     * 统一规整成 Q-only 布局返回（每扇区 2352 + 16）。
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
        cdb[9] = 0xFC.toByte()                 // 用户数据 + Sync + Header + 子通道
        cdb[10] = 0x02.toByte()                // Q-only subchannel
        val capacity = count * Subchannel.Q_STRIDE
        // SPTI 的目标缓冲必须是**本机内存**：传 heap 段会直接 IllegalArgumentException
        //（真机 liveTest 的 SubchannelLiveTest 踩到过）。这里自己开 ARENA 并在 finally 释放。
        val arena = java.lang.foreign.Arena.ofConfined()
        val got: Int
        val raw: ByteArray
        try {
            val buf = arena.allocate(capacity.toLong())
            // 子通道要等驱动器与盘面同步，10 秒在部分 BD 光驱上会直接 SEM_TIMEOUT
            got = transport.executeInto(cdb, buf, capacity, timeoutSec = 30)
            if (got < capacity) {
                // 短读时把实际收到的字节也拷出来，交给下面按 stride 判断
                raw = ByteArray(capacity.coerceAtLeast(0))
            } else {
                raw = ByteArray(got)
            }
            val copyLen = minOf(got, raw.size).toLong()
            if (copyLen > 0) MemorySegment.copy(buf, 0L, MemorySegment.ofArray(raw), 0L, copyLen)
        } finally {
            arena.close()
        }
        // 传输长度必须能被扇区数整除，且至少是「用户数据」的大小：
        // 否则后面按 stride 切包会越界（短读必须报成可读的错误，不是 ArrayIndexOutOfBounds）
        if (got < count * 2352 || got % count != 0) {
            throw ScsiException(
                "子通道短读：请求 $count 扇区，只回了 $got 字节" +
                    "（至少需要 ${count * 2352} 字节）",
                senseKey = 0x03, asc = 0x11
            )
        }
        val stride = got / count
        if (stride == 2352) {
            throw ScsiException(
                "驱动器没有返回子通道数据（Q 子通道不可用）：请求 $count 扇区，只回了 $got 字节",
                senseKey = 0x05, asc = 0x20
            )
        }
        return Subchannel.normalizeToQOnly(raw, count, stride)
    }

    /** 读单个扇区的 Q 子通道（gap 探测用）。 */
    override fun readQ(lba: Int): Subchannel.Q =
        Subchannel.parseQ(Subchannel.qOnlySlice(readSectorsWithQSubchannel(lba, 1), 0))

    /** 直接下发任意 CDB（诊断/实验用）。 */
    fun rawCommand(cdb: ByteArray, readSize: Int): ByteArray = transport.execute(cdb, readSize)

    /** TEST UNIT READY。 */    fun testUnitReady(): Boolean = runCatching {
        transport.execute(cdb(0x00, 0, 0, 0, 0, 0), 0)
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
        transport.execute(c, 0)
    }

    /**
     * 弹出托盘：START STOP UNIT（0x1B），byte4 的 bit1 = LoEj。
     * immed=1 让人机交互不必等机械动作完成（不然会卡住整个抓轨线程）。
     */
    fun eject() {
        transport.execute(cdb(0x1B, 0x01, 0, 0, 0x02, 0), 0)
    }

    /** 收回托盘（LoEj=1 且 Start=1）。 */
    fun load() {
        transport.execute(cdb(0x1B, 0x01, 0, 0, 0x03, 0), 0)
    }

    /** PREVENT/ALLOW MEDIUM REMOVAL（0x1E）：true 防止用户中途按弹出键。 */
    fun preventRemoval(prevent: Boolean) {
        transport.execute(cdb(0x1E, 0, 0, 0, if (prevent) 1 else 0, 0), 0)
    }

    private fun cdb(vararg b: Int): ByteArray = ByteArray(b.size) { (b[it] and 0xFF).toByte() }

    override fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    companion object {

        /** 单次 READ CD 的扇区数；27 与 EAC 一致（约 1/3 秒音频）。 */
        const val MAX_SECTORS: Int = 27

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
