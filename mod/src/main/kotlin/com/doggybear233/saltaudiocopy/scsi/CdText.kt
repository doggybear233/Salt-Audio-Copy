package com.doggybear233.saltaudiocopy.scsi

import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.TrackMeta

/**
 * CD-TEXT 解析（READ TOC format 5 返回的 pack 流）。
 *
 * 报文：`[len_hi, len_lo, 0, 0]` 之后是 18 字节的 pack 序列：
 * ```
 * [0] pack type (0x80..0x8F)  [1] track (0=专辑)  [2] sequence
 * [3] block(高 4 位) | charpos(低 4 位)   [4..15] 12 字节文本   [16..17] CRC16
 * ```
 *
 * 文本拼接按业界通用做法：同一 (type, track) 的 pack 按 sequence 排序，各取 12 字节依次相连。
 * 规范里的 `charpos` 用于「字符串不从 pack 边界开始」的罕见情况；实测盘与主流刻录软件都不产生这种 pack，
 * 所以这里忽略它（真遇到会在日志里体现为曲名前后多几个空格，不影响其余字段）。
 *
 * 编码：0x8F（size info）pack 的第 5 字节是字符集代码，0x00/0x01=ISO-8859-1，0x80..=Shift-JIS。
 * 没有 0x8F 时按内容猜：出现 Shift-JIS 双字节序列就按 MS932 解，否则按 ISO-8859-1。
 */
object CdText {

    const val PACK = 18

    /** Shift-JIS；`Charsets` 里没有这个常量，按名字取（JRE 自带 MS932）。 */
    private val SJIS: java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName("MS932") }
            .getOrElse { java.nio.charset.Charset.forName("Shift_JIS") }
    private const val TITLE = 0x80
    private const val PERFORMER = 0x81
    private const val SONGWRITER = 0x82
    private const val GENRE = 0x87
    private const val UPC = 0x8E
    private const val SIZE_INFO = 0x8F

    /** 一张盘的 CD-TEXT 内容；没有任何文本时 [hasText] 为 false。 */
    data class Info(
        val albumTitle: String?,
        val albumPerformer: String?,
        val genre: String?,
        val upc: String?,
        val charset: Int,
        /** 轨号（1 起）→ 标题 */
        val titles: Map<Int, String>,
        /** 轨号（1 起）→ 艺术家 */
        val performers: Map<Int, String>,
        /** 轨号（1 起）→ 词曲作者（CD-TEXT 0x82）；EAC 用它填 CUE 的 SONGWRITER */
        val songwriters: Map<Int, String> = emptyMap(),
        /** 专辑级词曲作者（pack track = 0） */
        val albumSongwriter: String? = null
    ) {
        val hasText: Boolean
            get() = !albumTitle.isNullOrBlank() || !albumPerformer.isNullOrBlank() || titles.isNotEmpty()

        fun toAlbumMeta(toc: DiscToc, discId: String = "", cddbId: String = ""): AlbumMeta {
            val artist = albumPerformer?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val title = albumTitle?.takeIf { it.isNotBlank() } ?: "Unknown Album"
            val tracks = toc.tracks.mapIndexed { i, entry ->
                val n = i + 1
                TrackMeta(
                    number = n,
                    title = titles[n]?.takeIf { it.isNotBlank() } ?: "Track %02d".format(n),
                    artist = performers[n]?.takeIf { it.isNotBlank() } ?: artist,
                    lengthMs = null,
                    isrc = null
                )
            }
            return AlbumMeta(
                albumArtist = artist,
                albumTitle = title,
                year = null,
                genre = genre?.takeIf { it.isNotBlank() },
                discId = discId,
                cddbId = cddbId,
                source = "cd-text",
                tracks = tracks
            )
        }
    }

    /** 解析 READ TOC format 5 的原始字节；无有效 pack 时返回 null。 */
    fun parse(data: ByteArray): Info? {
        if (data.size < 6) return null
        val declared = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val end = minOf(data.size, 4 + declared).coerceAtLeast(4)
        val packs = ArrayList<Pack>()
        var off = 4
        while (off + PACK <= end) {
            val type = data[off].toInt() and 0xFF
            if (type and 0x80 == 0) break // 不是 pack
            packs += Pack(
                type = type,
                track = data[off + 1].toInt() and 0xFF,
                seq = data[off + 2].toInt() and 0xFF,
                charset = data[off + 3].toInt() and 0x0F,
                data = data.copyOfRange(off + 4, off + 16)
            )
            off += PACK
        }
        if (packs.isEmpty()) return null

        val charset = packs.firstOrNull { it.type == SIZE_INFO && it.data.size > 1 }
            ?.let { it.data[1].toInt() and 0xFF } ?: -1

        fun text(type: Int, track: Int): String? {
            val chunk = packs.filter { it.type == type && it.track == track }
                .sortedBy { it.seq }
                .flatMap { it.data.toList() }
                .map { it.toInt() and 0xFF }
            if (chunk.isEmpty()) return null
            val bytes = chunk.takeWhile { it != 0 }.map { it.toByte() }.toByteArray()
            val s = decode(bytes, charset).trim()
            return s.ifBlank { null }
        }

        val titles = LinkedHashMap<Int, String>()
        val performers = LinkedHashMap<Int, String>()
        val songwriters = LinkedHashMap<Int, String>()
        val maxTrack = packs.filter { it.type == TITLE || it.type == PERFORMER || it.type == SONGWRITER }
            .maxOfOrNull { it.track } ?: 0
        for (t in 1..maxTrack) {
            text(TITLE, t)?.let { titles[t] = it }
            text(PERFORMER, t)?.let { performers[t] = it }
            text(SONGWRITER, t)?.let { songwriters[t] = it }
        }
        // 0x8E 的 low byte 是 genre code，这里只取曲目/专辑文本；genre 文本来自 0x87
        return Info(
            albumTitle = text(TITLE, 0),
            albumPerformer = text(PERFORMER, 0),
            genre = text(GENRE, 0),
            upc = text(UPC, 0),
            charset = charset,
            titles = titles,
            performers = performers,
            songwriters = songwriters,
            albumSongwriter = text(SONGWRITER, 0)
        )
    }

    private data class Pack(
        val type: Int,
        val track: Int,
        val seq: Int,
        val charset: Int,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** 按字符集解码；未知时先看是否像 Shift-JIS。 */
    internal fun decode(bytes: ByteArray, charset: Int): String {
        if (bytes.isEmpty()) return ""
        val japanese = charset in 0x80..0x8F
        if (japanese) return decodeOrRaw(bytes, SJIS)
        if (charset >= 0) {
            // 0x00 / 0x01 及未定义值都按 Latin-1；但若其实是 SJIS 文本，下面会被猜出来
            if (!looksLikeShiftJis(bytes)) return decodeOrRaw(bytes, Charsets.ISO_8859_1)
        }
        return if (looksLikeShiftJis(bytes)) decodeOrRaw(bytes, SJIS)
        else decodeOrRaw(bytes, Charsets.ISO_8859_1)
    }

    private fun decodeOrRaw(bytes: ByteArray, cs: java.nio.charset.Charset): String =
        runCatching { String(bytes, cs) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))

    /**
     * 粗略判断是否为 Shift-JIS 双字节文本：合法的前导字节后跟合法尾字节，且至少出现一次。
     * 只用于「没有 0x8F 字符集信息」时选编码，判错最多让标题变乱码，不影响其他字段。
     */
    internal fun looksLikeShiftJis(bytes: ByteArray): Boolean {
        var i = 0
        var hits = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x81..0x9F || b in 0xE0..0xEF) {
                if (i + 1 >= bytes.size) return false
                val t = bytes[i + 1].toInt() and 0xFF
                if (t !in 0x40..0xFC || t == 0x7F) return false
                hits++
                i += 2
            } else if (b < 0x80) {
                i++
            } else {
                return false
            }
        }
        return hits > 0
    }

    // ---- 供单测/工具构造报文 ----
    /** 把若干 (type, track, 文本字节) 打包成 READ TOC format 5 的响应。 */
    internal fun buildResponse(packs: List<Triple<Int, Int, ByteArray>>): ByteArray {
        var seq = 0
        val body = ArrayList<Byte>()
        for ((type, track, bytes) in packs) {
            var i = 0
            while (i < bytes.size) {
                val take = minOf(12, bytes.size - i)
                val chunk = ByteArray(12)
                System.arraycopy(bytes, i, chunk, 0, take)
                body += (type or 0x80).toByte()
                body += track.toByte()
                body += seq.toByte()
                body += 0
                chunk.forEach { body += it }
                body += 0; body += 0 // CRC（解析不校验）
                seq++
                i += take
            }
        }
        val len = body.size
        val out = ByteArray(4 + len)
        out[0] = ((len shr 8) and 0xFF).toByte()
        out[1] = (len and 0xFF).toByte()
        return out.also { body.toByteArray().copyInto(it, 4) }
    }
}
