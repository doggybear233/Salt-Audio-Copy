package com.doggybear233.saltaudiocopy.encode

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 给 WAV 写标签：`LIST/INFO` + `id3 `（ID3v2.3）。
 *
 * ## 为什么两份都写
 * RIFF `LIST/INFO` 规范上就是 **ANSI 字符串**（没有编码标记）。Windows 资源管理器、
 * WMP、MediaInfo 都按**系统代码页**解释它——所以在中文/日文 Windows 上，把 UTF-8
 * 塞进 INFO 一定显示成乱码（实测：Shell 的 `System.Title` 返回 `轣ｰ縺九・繧`）。
 *
 * 但现代播放器（foobar2000、ffmpeg 系）读 WAV 时按 **UTF-8** 解释 INFO，甚至优先读
 * WAV 里的 ID3v2 块。所以正确做法是：
 *
 * 1. `INFO` 用**系统代码页**写：全部字段都能表示时用原文；有字符表示不了（简体中文、
 *    韩文、阿拉伯文、emoji…）就**逐字映射**——ANSI 里有的字照原样，没有的换成 `?`，
 *    整串都映射不了（纯韩文）干脆不写该字段，避免资源管理器里出现一串 `????`；
 * 2. 追加一个 `id3 ` 块，ID3v2.3 + UTF-16LE（带 BOM），任何语言的字符都能无损表达；
 *    它排在 INFO 后面，ffmpeg 之类「后出现的键覆盖前面的」解析顺序会拿到正确文本。
 *
 * ## 为什么不用 metaflac 那套外部工具
 * 编解码全在自己手里，能保证中日韩、俄文、阿拉伯文、emoji 都不经过系统代码页。
 */
object WavTags {

    const val KIND = 8

    /** 标签键 → 4 字节 INFO ID。 */
    val IDS: Map<String, String> = linkedMapOf(
        "title" to "INAM",
        "artist" to "IART",
        "album" to "IPRD",
        "albumartist" to "IART",
        "date" to "ICRD",
        "genre" to "IGNR",
        "comment" to "ICMT",
        "track" to "ITRK",
        "software" to "ISFT"
    )

    /** 标签键 → ID3v2.3 帧 ID。 */
    val ID3_IDS: Map<String, String> = linkedMapOf(
        "title" to "TIT2",
        "artist" to "TPE1",
        "album" to "TALB",
        "albumartist" to "TPE2",
        "date" to "TYER",
        "genre" to "TCON",
        "comment" to "COMM",
        "track" to "TRCK",
        "disc" to "TPOS",
        "composer" to "TCOM"
    )

    /**
     * 系统 ANSI 代码页。
     *
     * `sun.jnu.encoding` 在 Windows 上就是 ANSI 代码页（日文系统 MS932、简体 MS936 …）。
     * Java 18+ 的 `file.encoding` 已经是 UTF-8，**不能**用它来判断系统代码页。
     */
    internal fun ansiCharset(): Charset {
        val name = System.getProperty("sun.jnu.encoding").orEmpty()
        if (name.isNotBlank()) {
            runCatching { Charset.forName(name) }.getOrNull()?.let { cs ->
                if (cs.name().uppercase() !in setOf("UTF-8", "UTF8")) return cs
            }
        }
        return runCatching { Charset.forName("windows-31j") }.getOrDefault(Charsets.ISO_8859_1)
    }

    /**
     * 把文本尽量映射成系统 ANSI 能表示的字符串。
     *
     * 资源管理器只按 ANSI 解 INFO，所以中文/韩文在日文系统上无法原样显示。
     * 与其让整串变成乱码，不如逐字映射：ANSI 里有的字（中日共用汉字、西里尔等）照原样，
     * 没有的字换成 `?`——至少「王菲」能正确显示，「?豆」也能猜出来。
     *
     * @return 映射结果 + 映射成功的非 ASCII 字符数（0 表示这一串完全无法对应，调用方应丢掉该字段）
     */
    internal fun bestEffortAnsi(value: String, cs: Charset = ansiCharset()): Pair<String, Int> {
        val enc = cs.newEncoder()
        val sb = StringBuilder(value.length)
        var mapped = 0
        for (ch in value) {
            if (enc.canEncode(ch.toString())) {
                sb.append(ch)
                if (ch.code > 0x7F) mapped++
            } else if (sb.isEmpty() || sb.last() != '?') {
                // 连续不可映射的字符合成一个 ?（emoji 是代理对，否则会出现「??」）
                sb.append('?')
            }
        }
        return sb.toString() to mapped
    }

    /**
     * INFO 块实际要写的值：ANSI 能全表示就用原文，否则逐字映射；
     * 完全映射不了（纯韩文/阿拉伯文/emoji）就返回 null，让这个字段干脆不写，
     * 免得资源管理器里出现一串 `????`。
     */
    internal fun infoValue(value: String, cs: Charset = ansiCharset()): String? {
        val enc = cs.newEncoder()
        if (enc.canEncode(value)) return value
        val (mapped, count) = bestEffortAnsi(value, cs)
        // 只认「字母/数字」的 ASCII 兜底：纯空格不能救活一串阿拉伯文
        val hasAsciiWord = value.any { it.code < 0x80 && it.isLetterOrDigit() }
        return if (count > 0 || hasAsciiWord) mapped else null
    }

    /** 全部字段都能用 [cs] 表示才用它，否则整体退回 UTF-8（避免同一块里混编码）。 */
    internal fun infoCharset(values: Collection<String>): Charset {
        val ansi = ansiCharset()
        val enc = ansi.newEncoder()
        return if (values.all { enc.canEncode(it) }) ansi else Charsets.UTF_8
    }

    /** 组装 `LIST(INFO ...)` 块；无标签返回 null。 */
    fun listBlock(tags: Map<String, String>, charset: Charset? = null): ByteArray? {
        val cs = charset ?: ansiCharset()
        val entries = tags.entries
            .filter { it.value.isNotBlank() }
            .mapNotNull { (k, v) ->
                val id = IDS[k] ?: return@mapNotNull null
                // charset 显式指定时按调用方要求写；默认走「尽量 ANSI」
                val text = if (charset != null) v else infoValue(v, cs) ?: return@mapNotNull null
                // RIFF 子块字符串带结尾 NUL（长度含 NUL），否则部分播放器会读进脏字节
                id to (text + "\u0000").toByteArray(cs)
            }
        if (entries.isEmpty()) return null

        var size = 4 // "INFO"
        for ((_, v) in entries) size += 8 + v.size + (v.size and 1)

        val out = java.io.ByteArrayOutputStream(size + 8)
        writeAscii(out, "LIST")
        writeLe32(out, size)
        writeAscii(out, "INFO")
        for ((id, v) in entries) {
            writeAscii(out, id)
            writeLe32(out, v.size)
            out.write(v)
            if (v.size and 1 == 1) out.write(0)
        }
        return out.toByteArray()
    }

    /**
     * 组装 `id3 `（ID3v2.3）块：UTF-16LE + BOM，帧体 `encoding(1) + BOM + 文本 + 00 00`。
     * 无标签返回 null。
     */
    fun id3Block(tags: Map<String, String>): ByteArray? {
        val frames = java.io.ByteArrayOutputStream()
        var count = 0
        for ((key, value) in tags) {
            if (value.isBlank()) continue
            val frameId = ID3_IDS[key] ?: continue
            val body = java.io.ByteArrayOutputStream()
            body.write(1) // encoding 1 = UTF-16LE with BOM
            if (frameId == "COMM") {
                // COMM 的帧体不是纯文本：encoding(1) + 语言(3) + 简短描述(以 NUL 结尾) + 正文。
                // 以前写成 encoding + BOM + 文本，严格解析器（foobar2000）会读成乱码或丢弃。
                body.write('e'.code); body.write('n'.code); body.write('g'.code)
                body.write(0); body.write(0) // 空描述（UTF-16 空串 = 两个 0 字节）
            }
            body.write(0xFF); body.write(0xFE)
            // TYER 规范只接受 4 位年份：带上「2019-05-01」会被判非法
            val text = if (frameId == "TYER") value.take(4) else value
            body.write(text.toByteArray(Charsets.UTF_16LE))
            body.write(0); body.write(0)
            val data = body.toByteArray()
            writeAscii(frames, frameId)
            writeBe32(frames, data.size)
            frames.write(0); frames.write(0) // frame flags
            frames.write(data)
            count++
        }
        if (count == 0) return null
        val payload = frames.toByteArray()

        val out = java.io.ByteArrayOutputStream(payload.size + 32)
        writeAscii(out, "id3 ")
        // RIFF 的块长度是 little-endian；ID3 头里的长度才是 syncsafe（大端 7bit/字节）
        val chunkSize = 10 + payload.size
        writeLe32(out, chunkSize)
        // 10 字节 ID3v2 头：ID3 + version(3,0) + flags(0) + syncsafe size
        out.write('I'.code); out.write('D'.code); out.write('3'.code)
        out.write(3); out.write(0)
        out.write(0)
        writeSyncSafe(out, payload.size)
        out.write(payload)
        // RIFF 块按偶数字节对齐
        if (chunkSize and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    /**
     * 把标签写进 [file]（原地重写：先写 `.tmp` 再原子替换）。
     * 不是 RIFF/WAVE 的文件直接跳过（返回 false），不抛异常。
     */
    fun write(file: Path, tags: Map<String, String>): Boolean = writeResult(file, tags) == null

    /**
     * 与 [write] 相同，但把「为什么没写成」带出来（null = 成功）。
     *
     * 以前调用方只能拿到 false，日志里只有「WAV 标签写入失败」而没有任何原因，
     * 用户与排查都无从下手。
     */
    fun writeResult(file: Path, tags: Map<String, String>): String? {
        if (!Files.isRegularFile(file)) return "文件不存在：$file"
        val bytes = runCatching { Files.readAllBytes(file) }.getOrElse {
            return "读文件失败：${it.message ?: it.javaClass.simpleName}"
        }
        if (bytes.size < 12) return "文件太短（${bytes.size} 字节），不像 WAV"
        if (ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return "不是 RIFF/WAVE 文件"

        // INFO 可能整块写不出来（纯韩文/阿拉伯文在日文系统上无法编码），
        // 这时至少还有 id3 能无损保存，不能因此整份放弃。
        val info = listBlock(tags)
        val id3 = id3Block(tags)
        if (info == null && id3 == null) return "标签里没有任何可写入的字段（全部为空或无法编码）"

        // 先去掉旧的 LIST/INFO 与 id3 块（重复打标签时不叠加）
        val body = stripMeta(bytes) ?: return "RIFF 块结构损坏，拒绝改写"

        val merged = ByteArray(body.size + (info?.size ?: 0) + (id3?.size ?: 0))
        System.arraycopy(body, 0, merged, 0, body.size)
        var off = body.size
        if (info != null) {
            System.arraycopy(info, 0, merged, off, info.size)
            off += info.size
        }
        if (id3 != null) System.arraycopy(id3, 0, merged, off, id3.size)
        fixRiffSize(merged)

        val tmp = file.resolveSibling(file.fileName.toString() + ".tagtmp")
        return runCatching {
            Files.write(tmp, merged)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }.exceptionOrNull()?.let {
            runCatching { Files.deleteIfExists(tmp) }
            "写临时文件失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    /**
     * 复制 [src] 到 [dst] 并写入标签（供「刚写完 WAV、还要编码」的场合用）。
     * 返回是否写入成功。
     */
    fun writeTo(src: Path, dst: Path, tags: Map<String, String>): Boolean {
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
        return write(dst, tags)
    }

    /** 去掉已有 `LIST` 与 `id3 ` 块，返回新字节（RIFF size 已修正）；结构损坏返回 null。 */
    internal fun stripMeta(bytes: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 12)
        var pos = 12
        var removed = false
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos, 4)
            val size = le32(bytes, pos + 4)
            if (size < 0 || pos + 8 + size > bytes.size) return null
            val total = 8 + size + (size and 1)
            val isInfo = id == "LIST" && size >= 4 && ascii(bytes, pos + 8, 4) == "INFO"
            if (isInfo || id == "id3 ") {
                removed = true
            } else {
                val end = minOf(bytes.size, pos + total)
                out.write(bytes, pos, end - pos)
            }
            pos += total
            if (total <= 0) return null
        }
        if (pos < bytes.size) out.write(bytes, pos, bytes.size - pos)
        val merged = out.toByteArray()
        if (removed) fixRiffSize(merged)
        return merged
    }

    /** 兼容旧调用：只去 `LIST`。 */
    internal fun stripList(bytes: ByteArray): ByteArray? = stripMeta(bytes)

    /** 读回标签：优先 ID3v2 块（无损），否则 INFO（按 UTF-8 试解，失败退回 ANSI）。 */
    fun read(file: Path): Map<String, String> {
        val bytes = runCatching { Files.readAllBytes(file) }.getOrDefault(ByteArray(0))
        val out = linkedMapOf<String, String>()
        if (bytes.size < 12 || ascii(bytes, 0, 4) != "RIFF") return out
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos, 4)
            val size = le32(bytes, pos + 4)
            if (size < 0 || pos + 8 + size > bytes.size) break
            if (id == "id3 ") {
                readId3(bytes, pos + 8, size, out)
            } else if (id == "LIST" && size >= 4 && ascii(bytes, pos + 8, 4) == "INFO") {
                readInfo(bytes, pos + 12, pos + 8 + size, out)
            }
            pos += 8 + size + (size and 1)
        }
        return out
    }

    private fun readInfo(bytes: ByteArray, from: Int, end: Int, out: MutableMap<String, String>) {
        var p = from
        while (p + 8 <= end) {
            val subId = ascii(bytes, p, 4)
            val subSize = le32(bytes, p + 4)
            if (subSize < 0 || p + 8 + subSize > end) break
            val raw = String(bytes, p + 8, subSize, Charsets.ISO_8859_1).trimEnd('\u0000')
            val key = IDS.entries.firstOrNull { it.value == subId }?.key ?: subId
            out.putIfAbsent(key, decodeText(bytes, p + 8, subSize))
            p += 8 + subSize + (subSize and 1)
        }
    }

    private fun readId3(bytes: ByteArray, from: Int, size: Int, out: MutableMap<String, String>) {
        val end = from + size
        if (from + 10 > end) return
        var p = from + 10 // 跳过 ID3 头
        while (p + 10 <= end) {
            val frameId = ascii(bytes, p, 4)
            if (frameId.isBlank() || frameId[0] == '\u0000') break
            val frameSizeRaw = ((bytes[p + 4].toInt() and 0xFF) shl 24) or
                ((bytes[p + 5].toInt() and 0xFF) shl 16) or
                ((bytes[p + 6].toInt() and 0xFF) shl 8) or
                (bytes[p + 7].toInt() and 0xFF)
            if (frameSizeRaw <= 0 || p + 10 + frameSizeRaw > end) break
            val bodyStart = p + 10
            val enc = bytes[bodyStart].toInt() and 0xFF
            var textOff = bodyStart + 1
            var textLen = frameSizeRaw - 1
            if (frameId == "COMM") {
                // encoding + 语言(3) + 空描述(2) 之后才是正文
                textOff += 3
                textLen -= 3
                if (textLen >= 2 && bytes[textOff] == 0.toByte() && bytes[textOff + 1] == 0.toByte()) {
                    textOff += 2
                    textLen -= 2
                }
            }
            val text = when {
                textLen <= 0 -> ""
                enc == 1 -> decodeUtf16(bytes, textOff, textLen)
                enc == 2 -> String(bytes, textOff, textLen, Charsets.UTF_16BE)
                enc == 3 -> String(bytes, textOff, textLen, Charsets.UTF_8)
                else -> String(bytes, textOff, textLen, Charsets.ISO_8859_1)
            }.trimEnd('\u0000')
            val key = ID3_IDS.entries.firstOrNull { it.value == frameId }?.key
            if (key != null && text.isNotBlank()) out[key] = text
            p = bodyStart + frameSizeRaw
        }
    }

    /** UTF-16（带 BOM）或 UTF-8；解码失败退回 ANSI。 */
    private fun decodeUtf16(b: ByteArray, off: Int, len: Int): String {
        if (len <= 0) return ""
        return runCatching {
            if (len >= 2 && b[off] == 0xFF.toByte() && b[off + 1] == 0xFE.toByte()) {
                String(b, off + 2, len - 2, Charsets.UTF_16LE)
            } else if (len >= 2 && b[off] == 0xFE.toByte() && b[off + 1] == 0xFF.toByte()) {
                String(b, off + 2, len - 2, Charsets.UTF_16BE)
            } else {
                String(b, off, len, Charsets.UTF_16LE)
            }
        }.getOrDefault("")
    }

    /**
     * INFO 文本解码：先按 UTF-8 严格解（能解出来说明是别的工具写的 UTF-8），
     * 否则按系统 ANSI 解（我们自己写的就是 ANSI）。
     */
    private fun decodeText(b: ByteArray, off: Int, len: Int): String {
        val trimmed = if (len > 0 && b[off + len - 1] == 0.toByte()) len - 1 else len
        if (trimmed <= 0) return ""
        runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b, off, trimmed)).toString()
        }.getOrNull()?.let { return it }
        return String(b, off, trimmed, ansiCharset())
    }

    private fun fixRiffSize(bytes: ByteArray) {
        val riffSize = bytes.size - 8
        bytes[4] = (riffSize and 0xFF).toByte()
        bytes[5] = ((riffSize ushr 8) and 0xFF).toByte()
        bytes[6] = ((riffSize ushr 16) and 0xFF).toByte()
        bytes[7] = ((riffSize ushr 24) and 0xFF).toByte()
    }

    private fun writeAscii(out: java.io.ByteArrayOutputStream, s: String) {
        out.write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun writeLe32(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeBe32(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeSyncSafe(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v ushr 21) and 0x7F)
        out.write((v ushr 14) and 0x7F)
        out.write((v ushr 7) and 0x7F)
        out.write(v and 0x7F)
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.US_ASCII)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
