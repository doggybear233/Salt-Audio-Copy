package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.encode.WavTags
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R4-04 · E2：打标签时「见 LIST 就删」会连 `adtl` 一起删；
 * 块长度高位为 1 时 `le32` 变负数，`pos += span` 往回走就是死循环。
 */
class BatchR404WavTagsTest {

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun chunk(id: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(le32(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))
        chunks.forEach { body.write(it) }
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(bytes.size))
        out.write(bytes)
        return out.toByteArray()
    }

    private fun count(hay: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.US_ASCII)
        var c = 0
        var i = 0
        while (i <= hay.size - n.size) {
            var ok = true
            for (j in n.indices) if (hay[i + j] != n[j]) { ok = false; break }
            if (ok) { c++; i += n.size } else i++
        }
        return c
    }

    @Test
    fun `LIST adtl 不会被当成旧标签删掉`() {
        val file = Files.createTempFile("saltac-wav", ".wav")
        val info = chunk("LIST", "INFO".toByteArray(Charsets.US_ASCII) + chunk("IART", "old".toByteArray()))
        val adtl = chunk("LIST", "adtl".toByteArray(Charsets.US_ASCII) + chunk("labl", byteArrayOf(1, 2, 3, 4)))
        val data = chunk("data", ByteArray(16))
        Files.write(file, wav(chunk("fmt ", ByteArray(16)), info, adtl, data))

        val err = WavTags.writeResult(file, mapOf("artist" to "new"))
        assertTrue(err == null, "打标签失败：$err")

        val out = Files.readAllBytes(file)
        assertTrue(count(out, "adtl") == 1, "adtl 被删了")
        assertTrue(count(out, "INFO") == 1, "旧 INFO 没删干净（新 INFO 应只有一个）")
    }

    @Test
    fun `长度字段为负的块不会让扫描原地打转`() {
        val file = Files.createTempFile("saltac-wav-bad", ".wav")
        val bad = ByteArrayOutputStream().apply {
            write("JUNK".toByteArray(Charsets.US_ASCII))
            write(le32(0xFFFFFFF8.toInt()))
            write(ByteArray(8))
        }.toByteArray()
        Files.write(file, wav(chunk("fmt ", ByteArray(16)), chunk("data", ByteArray(16)), bad))

        val err = WavTags.writeResult(file, mapOf("artist" to "x"))
        assertNotNull(err, "损坏块应显式报错，而不是当成功")
        assertTrue(file.toFile().length() > 0)
    }
}
