package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.encode.WavTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 批次 08：ID3 帧结构（COMM 的语言/描述）、TYER 年份、以及「写失败为什么失败」。
 */
class WavTagsId3Test {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltac-id3")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** 最小可用的 16bit 立体声 WAV：1 个采样。 */
    private fun wav(name: String = "a.wav"): Path {
        val p = dir.resolve(name)
        val data = ByteArray(4)
        val b = java.io.ByteArrayOutputStream()
        fun le32(v: Int) { b.write(v and 0xFF); b.write((v ushr 8) and 0xFF); b.write((v ushr 16) and 0xFF); b.write((v ushr 24) and 0xFF) }
        fun le16(v: Int) { b.write(v and 0xFF); b.write((v ushr 8) and 0xFF) }
        b.write("RIFF".toByteArray()); le32(36 + data.size); b.write("WAVE".toByteArray())
        b.write("fmt ".toByteArray()); le32(16); le16(1); le16(2); le32(44100); le32(44100 * 4); le16(4); le16(16)
        b.write("data".toByteArray()); le32(data.size); b.write(data)
        Files.write(p, b.toByteArray())
        return p
    }

    @Test
    fun commentRoundTripsAndCarriesLanguageField() {
        val p = wav()
        val why = WavTags.writeResult(p, mapOf("comment" to "Salt Audio Copy 1.0.0", "title" to "夜に駆ける"))
        assertEquals(null, why, "写入应当成功")
        val tags = WavTags.read(p)
        assertEquals("Salt Audio Copy 1.0.0", tags["comment"], "COMM 应能原样读回")
        assertTrue(
            String(Files.readAllBytes(p), Charsets.ISO_8859_1).contains("eng"),
            "COMM 帧体必须带 3 字节语言码"
        )
    }

    @Test
    fun yearFrameIsTruncatedToFourDigits() {
        val p = wav()
        WavTags.writeResult(p, mapOf("date" to "2019-05-01"))
        assertEquals("2019", WavTags.read(p)["date"], "TYER 只接受 4 位年份")
    }

    @Test
    fun writeResultExplainsWhyItFailed() {
        val p = dir.resolve("not-a-wav.txt")
        Files.writeString(p, "hello")
        val why = WavTags.writeResult(p, mapOf("title" to "x"))
        assertNotNull(why)
        assertTrue(why.contains("WAV"), "失败原因要说明不是 WAV：$why")
    }
}
