package com.doggybear233.saltaudiocopy.scsi

/** 一条 TOC 条目（READ TOC format 0，MSF 编码）。 */
data class TocEntry(
    val track: Int,
    val isAudio: Boolean,
    val adr: Int,
    val lba: Int
) {
    val msf: String
        get() {
            val v = lba + 150
            val m = v / 4500
            val s = (v % 4500) / 75
            val f = v % 75
            return "%02d:%02d:%02d".format(m, s, f)
        }
}

data class DiscToc(
    val firstTrack: Int,
    val lastTrack: Int,
    val entries: List<TocEntry>
) {
    val leadOut: TocEntry? = entries.firstOrNull { it.track == 0xAA }
    val leadOutLba: Int get() = leadOut?.lba ?: 0

    /** 仅包含真实音轨（不含 lead-out）。 */
    val tracks: List<TocEntry> = entries.filter { it.track in firstTrack..lastTrack }

    /** 全部音轨都是 CD-DA（CTRL bit2 = 0）。混合模式盘/数据盘为 false。 */
    val isAudioCd: Boolean get() = tracks.isNotEmpty() && tracks.all { it.isAudio }

    fun sectorsOf(index: Int): Pair<Int, Int> {
        val t = tracks[index]
        val next = tracks.getOrNull(index + 1)?.lba ?: leadOutLba
        return t.lba to (next - t.lba)
    }

    val totalSectors: Int get() = leadOutLba - (tracks.firstOrNull()?.lba ?: 0)

    companion object {
        /** 解析 READ TOC/PMA/ATIP format 0 的响应。 */
        fun parse(data: ByteArray): DiscToc {
            require(data.size >= 4) { "TOC 响应过短: ${data.size}" }
            fun u8(i: Int) = data[i].toInt() and 0xFF
            val len = ((u8(0) shl 8) or u8(1))
            val first = u8(2)
            val last = u8(3)
            val entries = ArrayList<TocEntry>()
            var off = 4
            while (off + 8 <= data.size && off < len + 2) {
                val ctrlAdr = u8(off + 1)
                val adr = (ctrlAdr shr 4) and 0x0F
                val ctrl = ctrlAdr and 0x0F
                val track = u8(off + 2)
                // CDB[1]=0 时地址是 4 字节大端 LBA（不是 MSF），不需要 ±150 换算
                val lba = (u8(off + 4) shl 24) or (u8(off + 5) shl 16) or (u8(off + 6) shl 8) or u8(off + 7)
                entries += TocEntry(track, (ctrl and 0x04) == 0, adr, lba)
                off += 8
            }
            return DiscToc(first, last, entries)
        }
    }
}
