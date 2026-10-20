package com.doggybear233.saltaudiocopy.encode

import com.doggybear233.saltaudiocopy.Diag
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * FLAC 元数据（VorbisComment + PICTURE）直写。
 *
 * 为什么不用 metaflac.exe：在 Windows 上它的命令行参数/导入文件都按**系统代码页**处理，
 * 实测 `--set-tag=TITLE=道` 会把 cp932 字节塞进 VorbisComment（规范要求 UTF-8），
 * 而 `ô` 这类字符会直接丢字。FLAC 的元数据块结构很简单
 * （`fLaC` + 若干 `[lastFlag|type][24-bit len][payload]` + 音频帧流），自己写可以：
 * - 保证标签永远是 UTF-8；
 * - 不改动音频帧（原样搬运，逐字节可验证）；
 * - 不引入对外部工具编码行为的依赖。
 */
object FlacMeta {

    const val TYPE_STREAMINFO = 0
    const val TYPE_PADDING = 1
    const val TYPE_VORBIS_COMMENT = 4
    const val TYPE_PICTURE = 6

    data class Block(val type: Int, val payload: ByteArray)

    data class Parsed(val blocks: List<Block>, val audioOffset: Long)

    fun readBlocks(file: Path): Parsed {
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "fLaC") { "不是 FLAC 文件: $file" }
            val blocks = ArrayList<Block>()
            var last = false
            while (!last) {
                val first = raf.readUnsignedByte()
                last = (first and 0x80) != 0
                val type = first and 0x7F
                val len = (raf.readUnsignedByte() shl 16) or
                    (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte()
                val payload = ByteArray(len)
                raf.readFully(payload)
                blocks += Block(type, payload)
            }
            return Parsed(blocks, raf.filePointer)
        }
    }

    // ---------------- VorbisComment ----------------

    fun vorbisComment(tags: List<Pair<String, String>>, vendor: String = "Salt Audio Copy"): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val v = vendor.toByteArray(Charsets.UTF_8)
        out.write(le32(v.size))
        out.write(v)
        val entries = tags
            .filter { (k, s) -> k.isNotBlank() && s.isNotBlank() }
            .map { (k, s) -> "${k.uppercase()}=${s.replace('\r', ' ').replace('\n', ' ').trim()}" }
        out.write(le32(entries.size))
        entries.forEach { e ->
            val b = e.toByteArray(Charsets.UTF_8)
            out.write(le32(b.size))
            out.write(b)
        }
        return out.toByteArray()
    }

    /** 读回标签（测试与自检用）。 */
    fun readTags(file: Path): Map<String, String> {
        val comment = readBlocks(file).blocks.firstOrNull { it.type == TYPE_VORBIS_COMMENT } ?: return emptyMap()
        val p = comment.payload
        var i = 0
        fun u32(): Int {
            val v = (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8) or
                ((p[i + 2].toInt() and 0xFF) shl 16) or ((p[i + 3].toInt() and 0xFF) shl 24)
            i += 4
            return v
        }
        val vendorLen = u32()
        i += vendorLen
        val count = u32()
        val out = LinkedHashMap<String, String>()
        repeat(count) {
            val len = u32()
            if (len < 0 || i + len > p.size) return@repeat
            val s = String(p, i, len, Charsets.UTF_8)
            i += len
            val eq = s.indexOf('=')
            if (eq > 0) out[s.substring(0, eq).uppercase()] = s.substring(eq + 1)
        }
        return out
    }

    // ---------------- PICTURE ----------------

    fun pictureBlock(image: ByteArray, mime: String, type: Int = 3, description: String = ""): ByteArray {
        val (w, h, depth) = imageSize(image, mime)
        val out = java.io.ByteArrayOutputStream()
        out.write(be32(type))
        val m = mime.toByteArray(Charsets.US_ASCII)
        out.write(be32(m.size))
        out.write(m)
        val d = description.toByteArray(Charsets.UTF_8)
        out.write(be32(d.size))
        out.write(d)
        out.write(be32(w))
        out.write(be32(h))
        out.write(be32(depth))
        out.write(be32(0)) // indexed colors
        out.write(be32(image.size))
        out.write(image)
        return out.toByteArray()
    }

    /** 只解析宽/高/色深，解析不出来就写 0（结构仍合法，播放器照常显示）。 */
    fun imageSize(image: ByteArray, mime: String): Triple<Int, Int, Int> {
        if (image.size > 24 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte()) {
            // PNG: IHDR 从 16 开始，宽高各 4 字节大端；位深 1 字节，颜色类型 1 字节
            val w = be32At(image, 16)
            val h = be32At(image, 20)
            val depth = image[24].toInt() and 0xFF
            return Triple(w, h, depth)
        }
        if (image.size > 4 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte()) {
            var i = 2
            while (i + 9 < image.size) {
                if (image[i].toInt() and 0xFF != 0xFF) { i++; continue }
                val marker = image[i + 1].toInt() and 0xFF
                val len = ((image[i + 2].toInt() and 0xFF) shl 8) or (image[i + 3].toInt() and 0xFF)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((image[i + 5].toInt() and 0xFF) shl 8) or (image[i + 6].toInt() and 0xFF)
                    val w = ((image[i + 7].toInt() and 0xFF) shl 8) or (image[i + 8].toInt() and 0xFF)
                    val depth = image[i + 4].toInt() and 0xFF
                    return Triple(w, h, depth)
                }
                i += 2 + len
            }
        }
        return Triple(0, 0, 0)
    }

    // ---------------- 写回 ----------------

    /**
     * 重写元数据块：替换/插入 VORBIS_COMMENT，可选替换 PICTURE，丢弃 PADDING，
     * 音频帧原样搬运。先写临时文件再原子替换，失败不会留下半截 FLAC。
     */
    fun write(file: Path, tags: List<Pair<String, String>>?, cover: ByteArray?, coverMime: String?, vendor: String = "Salt Audio Copy") {
        val parsed = readBlocks(file)
        val blocks = ArrayList<Block>()
        for (b in parsed.blocks) {
            when (b.type) {
                TYPE_PADDING -> {} // 丢弃；不做填充对齐没有副作用
                // tags == null 表示「不动标签」；显式传空列表才是「删掉标签」
                TYPE_VORBIS_COMMENT -> if (tags == null) blocks += b
                TYPE_PICTURE -> if (cover == null) blocks += b
                else -> blocks += b
            }
        }
        if (!blocks.any { it.type == TYPE_STREAMINFO }) error("FLAC 缺少 STREAMINFO: $file")
        if (tags != null) blocks += Block(TYPE_VORBIS_COMMENT, vorbisComment(tags, vendor))
        if (cover != null) blocks += Block(TYPE_PICTURE, pictureBlock(cover, coverMime ?: "image/jpeg"))

        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        RandomAccessFile(file.toFile(), "r").use { src ->
            RandomAccessFile(tmp.toFile(), "rw").use { dst ->
                dst.setLength(0)
                dst.write("fLaC".toByteArray(Charsets.US_ASCII))
                blocks.forEachIndexed { idx, b ->
                    val last = if (idx == blocks.size - 1) 0x80 else 0x00
                    dst.write(last or b.type)
                    dst.write((b.payload.size ushr 16) and 0xFF)
                    dst.write((b.payload.size ushr 8) and 0xFF)
                    dst.write(b.payload.size and 0xFF)
                    dst.write(b.payload)
                }
                // 音频帧原样搬运
                src.seek(parsed.audioOffset)
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    dst.write(buf, 0, n)
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        Diag.log("flac meta rewritten: $file tags=${tags?.size ?: 0} cover=${cover != null}")
    }

    fun writeTags(file: Path, tags: List<Pair<String, String>>, vendor: String = "Salt Audio Copy") =
        write(file, tags, null, null, vendor)

    fun writeCover(file: Path, cover: ByteArray, mime: String) =
        write(file, null, cover, mime)

    // ---------------- 标签构造 ----------------

    /**
     * 一轨的 Vorbis 注释。只用播放器普遍识别的大写键，避免各家不认的自定义键。
     */
    fun trackTags(
        albumTitle: String?,
        albumArtist: String?,
        year: String?,
        genre: String?,
        trackNumber: Int,
        trackTitle: String,
        trackArtist: String?,
        isrc: String?,
        trackTotal: Int?,
        discNumber: Int?,
        totalDiscs: Int?,
        comment: String,
        /** 词曲作者（CD-TEXT 0x82），VorbisComment 里叫 COMPOSER */
        composer: String? = null
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "TITLE" to trackTitle
        out += "ARTIST" to (trackArtist?.takeIf { it.isNotBlank() } ?: albumArtist.orEmpty())
        if (!albumTitle.isNullOrBlank()) out += "ALBUM" to albumTitle
        if (!albumArtist.isNullOrBlank()) out += "ALBUMARTIST" to albumArtist
        if (!year.isNullOrBlank()) out += "DATE" to year
        if (!genre.isNullOrBlank()) out += "GENRE" to genre
        out += "TRACKNUMBER" to trackNumber.toString()
        if (trackTotal != null && trackTotal > 0) out += "TRACKTOTAL" to trackTotal.toString()
        if (discNumber != null && discNumber > 0) out += "DISCNUMBER" to discNumber.toString()
        if (totalDiscs != null && totalDiscs > 0) out += "TOTALDISCS" to totalDiscs.toString()
        if (!isrc.isNullOrBlank()) out += "ISRC" to isrc
        if (comment.isNotBlank()) out += "COMMENT" to comment
        composer?.takeIf { it.isNotBlank() }?.let { out += "COMPOSER" to it }
        return out
    }

    // ---------------- 小工具 ----------------

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
    )

    private fun be32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun be32At(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
}
