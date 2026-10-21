package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.ImageSplicer
import com.doggybear233.saltaudiocopy.rip.WavInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageSplicerTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltac-img")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** 每轨内容不同，便于验证拼接顺序。[tagged] 为真时在 data 之后再追加 LIST + id3（真实抓轨产物）。 */
    private fun part(name: String, sectors: Int, fill: Byte, tagged: Boolean = false): Path {
        val p = dir.resolve(name)
        val audio = sectors * 2352
        val data = ByteArray(44 + audio)
        ImageSplicer.header(audio.toLong()).copyInto(data, 0)
        for (i in 44 until data.size) data[i] = fill
        Files.write(p, data)
        if (tagged) {
            // LIST/INFO + id3：长度不进 RIFF 的 data 字段，只能靠解析块找到音频终点
            val tags = ByteArray(44) { (0x55).toByte() }
            val riff = String(data, 0, 4, Charsets.US_ASCII)
            Files.write(p, data + tags)
            assertTrue(riff == "RIFF")
        }
        return p
    }

    @Test
    fun concatenatesInOrderAndWritesNewHeader() {
        val a = part("a.wav", 2, 1)
        val b = part("b.wav", 3, 2)
        val c = part("c.wav", 1, 3)
        val out = dir.resolve("image.wav")
        val r = ImageSplicer.splice(listOf(a, b, c), out)!!

        assertEquals(6L * 2352, r.dataBytes)
        assertEquals(6L, r.sectors)
        assertEquals(3, r.parts)
        assertEquals(44L + 6 * 2352, Files.size(out))

        val facts = WavInfo.read(out)!!
        assertTrue(facts.headerOk)
        assertEquals(6L, facts.sectors)

        val bytes = Files.readAllBytes(out)
        assertEquals(1, bytes[44].toInt())
        assertEquals(1, bytes[44 + 2 * 2352 - 1].toInt())
        assertEquals(2, bytes[44 + 2 * 2352].toInt())
        assertEquals(3, bytes[44 + 5 * 2352].toInt())
    }

    @Test
    fun ignoresTagsAppendedAfterTheAudio() {
        // 关键回归：标签字节一律不能混进镜像，否则第 2 轨起整体错位、CUE 全错
        val a = part("ta.wav", 2, 1, tagged = true)
        val b = part("tb.wav", 3, 2, tagged = true)
        val out = dir.resolve("image2.wav")
        val r = ImageSplicer.splice(listOf(a, b), out)!!

        assertEquals(5L * 2352, r.dataBytes)
        assertEquals(44L + 5 * 2352, Files.size(out), "镜像只应有头 + 纯音频")
        val bytes = Files.readAllBytes(out)
        assertEquals(1, bytes[44].toInt())
        assertEquals(2, bytes[44 + 2 * 2352].toInt(), "第 2 轨应从音频起点开始，不能被标签顶偏")
        assertEquals(2, bytes[44 + 5 * 2352 - 1].toInt())
    }

    @Test
    fun rejectsNonWav() {
        val bad = dir.resolve("bad.wav")
        Files.write(bad, ByteArray(100) { 'X'.code.toByte() })
        assertFailsWith<IllegalArgumentException> {
            ImageSplicer.splice(listOf(bad), dir.resolve("out.wav"))
        }
    }

    @Test
    fun rejectsEmptyList() {
        assertFailsWith<IllegalArgumentException> {
            ImageSplicer.splice(emptyList(), dir.resolve("out.wav"))
        }
    }

    @Test
    fun rejectsHeaderOnlyPart() {
        val p = dir.resolve("empty.wav")
        Files.write(p, ImageSplicer.header(0))
        assertFailsWith<IllegalArgumentException> {
            ImageSplicer.splice(listOf(p), dir.resolve("out.wav"))
        }
    }
}
