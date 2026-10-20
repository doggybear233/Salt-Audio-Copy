package com.saltrip.rip.encode

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WAV 的 LIST/INFO 标签：块结构、RIFF 长度、重复写入、CJK 编码。 */
class WavTagsTest {

    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltrip-wavtags")
    }

    @AfterTest
    fun tearDown() {
        runCatching {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** 44 字节头 + n 字节数据的干净 WAV。 */
    private fun wav(name: String, dataBytes: Int): java.nio.file.Path {
        val f = dir.resolve(name)
        val total = 44 + dataBytes
        val b = ByteArray(total)
        fun put(off: Int, s: String) = s.forEachIndexed { i, c -> b[off + i] = c.code.toByte() }
        put(0, "RIFF")
        le32(b, 4, total - 8)
        put(8, "WAVE")
        put(12, "fmt ")
        le32(b, 16, 16)
        b[20] = 1 // PCM
        b[22] = 2 // 2ch
        le32(b, 24, 44100)
        le32(b, 28, 44100 * 4)
        b[32] = 4
        b[34] = 16
        put(36, "data")
        le32(b, 40, dataBytes)
        for (i in 0 until dataBytes) b[44 + i] = (i and 0xFF).toByte()
        Files.write(f, b)
        return f
    }

    private fun le32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    @Test
    fun writesTagsAndFixesRiffSize() {
        val f = wav("a.wav", 1000)
        val ok = WavTags.write(
            f,
            mapOf(
                "title" to "道 - テスト",
                "artist" to "宇多田ヒカル",
                "album" to "Fantôme",
                "date" to "2016",
                "track" to "3/11"
            )
        )
        assertTrue(ok)
        val size = Files.size(f).toInt()
        val bytes = Files.readAllBytes(f)
        assertEquals(size - 8, le32Of(bytes, 4), "RIFF 头长度必须等于文件长度 - 8")

        val tags = WavTags.read(f)
        assertEquals("道 - テスト", tags["title"])
        assertEquals("宇多田ヒカル", tags["artist"])
        assertEquals("Fantôme", tags["album"])
        assertEquals("3/11", tags["track"])
    }

    @Test
    fun pcmBytesSurviveTagging() {
        val f = wav("b.wav", 600)
        val before = Files.readAllBytes(f)
        WavTags.write(f, mapOf("title" to "x"))
        val after = Files.readAllBytes(f)
        // data 块内容必须逐字节不变：哪怕多一个 LIST 块，音频数据也不能被动。
        for (i in 0 until 600) {
            assertEquals(before[44 + i], after[44 + i], "第 $i 字节被改动")
        }
        assertTrue(after.size > before.size)
    }

    @Test
    fun rewritingReplacesOldListInsteadOfAppending() {
        val f = wav("c.wav", 200)
        WavTags.write(f, mapOf("title" to "first", "artist" to "a1"))
        val once = Files.size(f)
        WavTags.write(f, mapOf("title" to "second"))
        val twice = Files.size(f)
        assertTrue(twice < once + 100, "重复写不应无限追加（once=$once twice=$twice）")
        val tags = WavTags.read(f)
        assertEquals("second", tags["title"])
        assertNull(tags["artist"], "旧 LIST 被整体替换，不该残留 artist")
    }

    @Test
    fun rejectsNonWavQuietly() {
        val f = dir.resolve("x.bin")
        Files.write(f, "not a wav".toByteArray())
        assertFalse(WavTags.write(f, mapOf("title" to "t")))
        assertNull(WavTags.listBlock(emptyMap()))
    }

    @Test
    fun listBlockIsNullWhenAllValuesBlank() {
        assertNull(WavTags.listBlock(mapOf("title" to "  ", "artist" to "")))
        assertNotNull(WavTags.listBlock(mapOf("title" to "t")))
    }

    private fun le32Of(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
