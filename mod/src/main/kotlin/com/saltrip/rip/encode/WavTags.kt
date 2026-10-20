package com.saltrip.rip.encode

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 给 WAV 写 RIFF `LIST/INFO` 标签。
 *
 * 为什么需要：Windows 资源管理器、foobar2000、大部分播放器读 WAV 的标题/艺术家时
 * 看的是 `LIST` 块里的 `INFO` 子块，而不是文件名。抓轨产物只有文件名（反馈 6：
 * 「资源管理器看不到音轨标题/艺术家/专辑」），所以抓完顺手补一份。
 *
 * 实现要点：
 * - `LIST` 块追加在 `data` 块之后；`RIFF` 头的 size 必须同步 +8+子块长度；
 * - 每个 `INFO` 子块是 4 字节 ID + 4 字节小端长度 + 数据，奇数长度补 1 字节 0；
 * - 文本按 UTF-8 写（微软的 INFO 规范其实是 ANSI，但 UTF-8 在现代 Windows
 *   资源管理器与 foobar2000 上都能正确显示中日文；写 ANSI 反而必然乱码）。
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

    /** 组装 `LIST(INFO ...)` 块；无标签返回 null。 */
    fun listBlock(tags: Map<String, String>): ByteArray? {
        val entries = tags.entries
            .filter { it.value.isNotBlank() }
            .mapNotNull { (k, v) ->
                val id = IDS[k] ?: return@mapNotNull null
                id to v.toByteArray(Charsets.UTF_8)
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
     * 把标签追加到 [file]（原地重写：先写 `.tmp` 再原子替换）。
     * 不是 RIFF/WAVE 的文件直接跳过（返回 false），不抛异常。
     */
    fun write(file: Path, tags: Map<String, String>): Boolean {
        if (!Files.isRegularFile(file)) return false
        val block = listBlock(tags) ?: return false

        val bytes = Files.readAllBytes(file)
        if (bytes.size < 12) return false
        if (ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return false

        // 先去掉旧的 LIST/INFO（重复打标签时不叠加）
        val body = stripList(bytes) ?: return false

        val merged = body.copyOf(body.size + block.size)
        System.arraycopy(block, 0, merged, body.size, block.size)
        val riffSize = merged.size - 8
        merged[4] = (riffSize and 0xFF).toByte()
        merged[5] = ((riffSize ushr 8) and 0xFF).toByte()
        merged[6] = ((riffSize ushr 16) and 0xFF).toByte()
        merged[7] = ((riffSize ushr 24) and 0xFF).toByte()

        val tmp = file.resolveSibling(file.fileName.toString() + ".tagtmp")
        Files.write(tmp, merged)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    /**
     * 复制 [src] 到 [dst] 并写入标签（供「刚写完 WAV、还要编码」的场合用）。
     * 返回是否写入成功。
     */
    fun writeTo(src: Path, dst: Path, tags: Map<String, String>): Boolean {
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
        return write(dst, tags)
    }

    /** 去掉已有 `LIST` 块，返回新的字节（RIFF size 已修正）。块结构损坏时返回 null。 */
    internal fun stripList(bytes: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 12)
        var pos = 12
        var removed = false
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos, 4)
            val size = le32(bytes, pos + 4)
            if (size < 0 || pos + 8 + size > bytes.size) return null
            val total = 8 + size + (size and 1)
            if (id == "LIST" && size >= 4 && ascii(bytes, pos + 8, 4) == "INFO") {
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
        if (!removed) return merged
        val riffSize = merged.size - 8
        merged[4] = (riffSize and 0xFF).toByte()
        merged[5] = ((riffSize ushr 8) and 0xFF).toByte()
        merged[6] = ((riffSize ushr 16) and 0xFF).toByte()
        merged[7] = ((riffSize ushr 24) and 0xFF).toByte()
        return merged
    }

    /** 读回 `LIST/INFO` 标签（测试与自检用）。 */
    fun read(file: Path): Map<String, String> {
        val bytes = runCatching { Files.readAllBytes(file) }.getOrDefault(ByteArray(0))
        val out = linkedMapOf<String, String>()
        if (bytes.size < 12 || ascii(bytes, 0, 4) != "RIFF") return out
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos, 4)
            val size = le32(bytes, pos + 4)
            if (size < 0 || pos + 8 + size > bytes.size) break
            if (id == "LIST" && size >= 4 && ascii(bytes, pos + 8, 4) == "INFO") {
                var p = pos + 12
                val end = pos + 8 + size
                while (p + 8 <= end) {
                    val subId = ascii(bytes, p, 4)
                    val subSize = le32(bytes, p + 4)
                    if (subSize < 0 || p + 8 + subSize > end) break
                    val value = String(bytes, p + 8, subSize, Charsets.UTF_8).trimEnd('\u0000')
                    val key = IDS.entries.firstOrNull { it.value == subId }?.key ?: subId
                    out[key] = value
                    p += 8 + subSize + (subSize and 1)
                }
            }
            pos += 8 + size + (size and 1)
        }
        return out
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

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.US_ASCII)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
