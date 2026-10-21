package com.doggybear233.saltaudiocopy.scsi

/**
 * CD 子通道（subchannel）解析。
 *
 * READ CD 带子通道位时，每个扇区在 2352 字节用户数据之后附 **96 字节子通道数据**：
 * 这 96 个字节是位平面排布——第 i 个字节的 bit7..bit0 分别属于 P,Q,R,S,T,U,V,W 八个通道，
 * 每个通道 96 bit = 12 字节。所以 Q 通道 = 每个字节的 bit6（掩码 0x40）按 MSB-first 拼出的 12 字节。
 *
 * Q 通道 12 字节布局（MMC）：
 * ```
 * b0      control(高 4 位) | adr(低 4 位)
 * b1      track  (BCD)
 * b2      index  (BCD)
 * b3..b5  相对时间 M:S:F (BCD)
 * b6      保留（0）
 * b7..b9  绝对时间 M:S:F (BCD)
 * b10..11 CRC16（大端，CRC-16-CCITT，poly 0x1021，初值 0）
 * ```
 */
object Subchannel {

    /** 每扇区子通道数据字节数。 */
    const val SIZE = 96

    /** 每扇区 Q 通道字节数。 */
    const val Q_SIZE = 12

    /** Q-only 模式下每扇区的总字节数（2352 用户数据 + 16 字节 Q 记录）。 */
    const val Q_STRIDE = 2352 + 16

    /**
     * 把「用户数据 + 子通道」的原始响应规整成 Q-only 布局（每扇区 2352 + 16）。
     *
     * 不同驱动器对同一个 READ CD 请求回的步长不一样：
     * - `2352`：完全没带子通道（我们要的东西没拿到，调用方必须报错而不是当「无间隙」）；
     * - `2368`：2352 数据 + 16 字节 Q-only；
     * - `2448`：2352 数据 + 96 字节 raw 子通道（Q 需要按位平面反交织）。
     *
     * 以前写死 2368 并用 `exact()` 强校验：换一台按规范处理 bit2 的驱动器就会抛「短读」，
     * 而间隙探测把异常吞成「这张盘没有 INDEX 00」。
     */
    fun normalizeToQOnly(raw: ByteArray, count: Int, stride: Int): ByteArray {
        if (stride == Q_STRIDE) return raw
        val out = ByteArray(count * Q_STRIDE)
        for (i in 0 until count) {
            val src = i * stride
            System.arraycopy(raw, src, out, i * Q_STRIDE, 2352)
            val q = when (stride) {
                2352 + 96 -> extractChannel(raw, 0x01, src + 2352)
                2352 -> throw IllegalArgumentException("驱动器没有返回子通道数据")
                else -> throw IllegalArgumentException("未知的子通道步长 $stride")
            }
            System.arraycopy(q, 0, out, i * Q_STRIDE + 2352, 12)
        }
        return out
    }

    fun qOnlySlice(buf: ByteArray, sectorIndex: Int): ByteArray {
        val off = sectorIndex * Q_STRIDE + 2352
        require(buf.size >= off + Q_SIZE) { "Q-only 缓冲太短: ${buf.size}" }
        return buf.copyOfRange(off, off + Q_SIZE)
    }

    /** 从 96 字节子通道块里取出某一通道（0=P,1=Q,...7=W）的 12 字节。 */
    fun extractChannel(block: ByteArray, channel: Int, offset: Int = 0): ByteArray {
        require(channel in 0..7) { "channel=$channel 越界" }
        require(block.size >= offset + SIZE) { "子通道块太短" }
        val shift = 7 - channel
        val out = ByteArray(Q_SIZE)
        for (bit in 0 until 96) {
            val v = (block[offset + bit].toInt() shr shift) and 1
            if (v != 0) {
                val byteIdx = bit / 8
                out[byteIdx] = (out[byteIdx].toInt() or (0x80 shr (bit % 8))).toByte()
            }
        }
        return out
    }

    /** 解析出的 Q 通道内容。 */
    data class Q(
        val control: Int,
        val adr: Int,
        val track: Int,
        val index: Int,
        val relMsf: Int,
        val absMsf: Int,
        val crcStored: Int,
        val crcComputed: Int,
        /** 12 字节 Q 全为 0——驱动器没真正填子通道（常见于部分 SATA 光驱）。 */
        val blank: Boolean = false
    ) {
        /** 该扇区是否音频轨（control bit2 = 0 表示 audio）。 */
        val isAudio: Boolean get() = (control and 0x04) == 0

        /**
         * Q 通道自校验。
         *
         * CD 的 Q 数据里存的是 CRC 的**按位取反值**（实测 `stored == crcComputed xor 0xFFFF`），
         * 两种口径都接受，便于兼容不同驱动器/固件。
         *
         * 全零块必须判为无效：全零时 stored 与 computed 都是 0，看起来「校验通过」，
         * 但 track/index 也都是 0——间隙检测会把它当成「到处都是 INDEX 00」，扫出假间隙。
         */
        val crcValid: Boolean
            get() = !blank && (crcStored == crcComputed || crcStored == (crcComputed xor 0xFFFF))
    }

    /** BCD → int。 */
    private fun bcd(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return ((v shr 4) and 0x0F) * 10 + (v and 0x0F)
    }

    /** MSF 三字节 → 扇区号（分*60*75 + 秒*75 + 帧）。 */
    private fun msf(b0: Byte, b1: Byte, b2: Byte): Int =
        bcd(b0) * 60 * 75 + bcd(b1) * 75 + bcd(b2)

    /**
     * 解析 12 字节 Q。CRC 同时给两种口径：
     * - [Q.crcComputed] = 对前 10 字节做 CRC-16-CCITT（初值 0）
     * 部分驱动器/盘片把 CRC 按「含 CRC 共 12 字节结果为 0」存，若前者不等可再比对 `crcValidFull12`。
     */
    fun parseQ(q: ByteArray): Q {
        require(q.size >= Q_SIZE) { "Q 通道长度不足" }
        val control = (q[0].toInt() and 0xF0) shr 4
        val adr = q[0].toInt() and 0x0F
        val stored = ((q[10].toInt() and 0xFF) shl 8) or (q[11].toInt() and 0xFF)
        val blank = (0 until Q_SIZE).all { (q[it].toInt() and 0xFF) == 0 }
        return Q(
            control = control,
            adr = adr,
            track = bcd(q[1]),
            index = bcd(q[2]),
            relMsf = msf(q[3], q[4], q[5]),
            absMsf = msf(q[7], q[8], q[9]),
            crcStored = stored,
            crcComputed = crc16(q, 0, 10),
            blank = blank
        )
    }

    /** CRC-16-CCITT（poly 0x1021，初值 0，不反转、不取补）。 */
    fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    /** 把 12 字节 Q 里的绝对地址换算成 LBA（MSF 以 00:02:00 为 LBA 0）。 */
    fun absToLba(absMsf: Int): Int = absMsf - 150
}
