import io

base = r'D:\deepseek\workspace\projects\salt-rip\mod\src\main\kotlin\com\saltrip\rip\scsi'

# --- CdDevice: 用 sub=2（Q-only）读取，直接拿到 12 字节 Q ---
p = base + r'\CdDevice.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    /**
     * 读 [count] 个扇区的用户数据 + 子通道（每扇区 2352+96=2448 字节）。
     * CDB[9] 用 `0xF9`（用户数据 + 子通道位，不带 C2）。
     */
    fun readSectorsWithSubchannel(lba: Int, count: Int): ByteArray {
        require(count in 1..maxSectorsPerRequest) { "count=$count 超出单次上限 $maxSectorsPerRequest" }
        val cdb = ByteArray(12)
        cdb[0] = 0xBE.toByte()
        cdb[1] = 0x04.toByte()
        cdb[2] = ((lba ushr 24) and 0xFF).toByte()
        cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()
        cdb[8] = count.toByte()
        cdb[9] = 0xF9.toByte()                 // 数据 + 子通道
        return Sptd.execute(handle, cdb, count * Subchannel.STRIDE)
    }

    /** 读单个扇区的 Q 子通道（用于 gap 探测，一次一扇区）。 */
    fun readQ(lba: Int): Subchannel.Q =
        Subchannel.qOfBlock(readSectorsWithSubchannel(lba, 1), 0)
''', '''    /**
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
    fun readQ(lba: Int): Subchannel.Q =
        Subchannel.parseQ(Subchannel.qOnlySlice(readSectorsWithQSubchannel(lba, 1), 0))
''')
io.open(p, 'w', encoding='utf-8', newline='').write(s)

# --- Subchannel: 增加 Q-only 布局与 CRC 取反口径 ---
p = base + r'\Subchannel.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    /** 带子通道读取时每扇区的总字节数（2352 用户数据 + 96 子通道）。 */
    const val STRIDE = 2352 + SIZE''', '''    /** 带子通道读取时每扇区的总字节数（2352 用户数据 + 96 子通道）。 */
    const val STRIDE = 2352 + SIZE

    /** Q-only 模式下每扇区的总字节数（2352 用户数据 + 16 字节 Q 记录）。 */
    const val Q_STRIDE = 2352 + 16

    /** 从 Q-only 块里取第 [sectorIndex] 个扇区的 12 字节 Q（前 12 字节，后 4 字节是填充）。 */
    fun qOnlySlice(buf: ByteArray, sectorIndex: Int): ByteArray {
        val off = sectorIndex * Q_STRIDE + 2352
        require(buf.size >= off + Q_SIZE) { "Q-only 缓冲太短: ${buf.size}" }
        return buf.copyOfRange(off, off + Q_SIZE)
    }''')
s = s.replace('''        /** Q 通道自校验：CRC 正确说明反交织与解析都没有错位。 */
        val crcValid: Boolean get() = crcStored == crcComputed''', '''        /**
         * Q 通道自校验。
         *
         * CD 的 Q 数据里存的是 CRC 的**按位取反值**（实测 `stored == crcComputed xor 0xFFFF`），
         * 两种口径都接受，便于兼容不同驱动器/固件。
         */
        val crcValid: Boolean
            get() = crcStored == crcComputed || crcStored == (crcComputed xor 0xFFFF)''')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
