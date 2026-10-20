package com.saltrip.rip

import com.saltrip.rip.encode.FlacMeta
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlacMetaTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltrip-flacmeta")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private val audio = ByteArray(64) { (0xA0 + it).toByte() }

    /** 最小合法 FLAC：fLaC + STREAMINFO(34) + [额外块] + 音频 */
    private fun writeFlac(name: String, extra: List<Pair<Int, ByteArray>> = emptyList()): Path {
        val out = java.io.ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        blocks += FlacMeta.TYPE_STREAMINFO to ByteArray(34)
        blocks += extra
        blocks.forEachIndexed { idx, (type, payload) ->
            out.write((if (idx == blocks.size - 1) 0x80 else 0) or type)
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(payload)
        }
        out.write(audio)
        val p = dir.resolve(name)
        Files.write(p, out.toByteArray())
        return p
    }

    private fun audioPart(file: Path): ByteArray {
        val all = Files.readAllBytes(file)
        return all.copyOfRange(FlacMeta.readBlocks(file).audioOffset.toInt(), all.size)
    }

    @Test
    fun writesUtf8TagsAndKeepsAudioBytes() {
        val f = writeFlac("a.flac")
        val tags = FlacMeta.trackTags(
            albumTitle = "Fantôme", albumArtist = "宇多田ヒカル", year = "2016", genre = "J-Pop",
            trackNumber = 3, trackTitle = "道", trackArtist = null, isrc = "JPU901602345",
            trackTotal = 11, discNumber = 1, totalDiscs = 2, comment = "SaltRip 0.9.0"
        )
        FlacMeta.writeTags(f, tags)

        val read = FlacMeta.readTags(f)
        assertEquals("道", read["TITLE"])
        assertEquals("Fantôme", read["ALBUM"])
        assertEquals("宇多田ヒカル", read["ARTIST"])
        assertEquals("3", read["TRACKNUMBER"])
        assertEquals("11", read["TRACKTOTAL"])
        assertEquals("JPU901602345", read["ISRC"])
        assertContentEquals(audio, audioPart(f), "音频帧必须逐字节不变")
    }

    @Test
    fun utf8BytesOnDiskAreReallyUtf8() {
        val f = writeFlac("b.flac")
        FlacMeta.writeTags(f, listOf("TITLE" to "道"))
        val raw = Files.readAllBytes(f)
        val needle = "TITLE=道".toByteArray(Charsets.UTF_8)
        assertTrue(indexOf(raw, needle) >= 0, "磁盘上必须是 UTF-8 字节（metaflac 在 Windows 上会写成 cp932）")
        assertTrue(indexOf(raw, "TITLE=道".toByteArray(charset("MS932"))) < 0)
    }

    @Test
    fun pictureBlockHasParsedDimensions() {
        val f = writeFlac("c.flac")
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        )
        FlacMeta.writeCover(f, png, "image/png")
        val blocks = FlacMeta.readBlocks(f).blocks
        val pic = blocks.first { it.type == FlacMeta.TYPE_PICTURE }
        // type(4) mimeLen(4) "image/png"(9) descLen(4) desc(0) w(4) h(4) depth(4) colors(4) dataLen(4)
        val p = pic.payload
        fun be32(off: Int) = ((p[off].toInt() and 0xFF) shl 24) or ((p[off + 1].toInt() and 0xFF) shl 16) or
            ((p[off + 2].toInt() and 0xFF) shl 8) or (p[off + 3].toInt() and 0xFF)
        assertEquals(3, be32(0), "front cover")
        assertEquals(9, be32(4))
        assertEquals("image/png", String(p, 8, 9, Charsets.US_ASCII))
        val afterMime = 8 + 9
        assertEquals(0, be32(afterMime), "描述为空")
        assertEquals(1, be32(afterMime + 4), "宽")
        assertEquals(1, be32(afterMime + 8), "高")
        assertEquals(png.size, be32(afterMime + 20))
        assertContentEquals(audio, audioPart(f))
    }

    @Test
    fun keepsUnknownBlocksAndDropsPadding() {
        val seekTable = 3 to ByteArray(18) { 7 }
        val padding = FlacMeta.TYPE_PADDING to ByteArray(100)
        val f = writeFlac("d.flac", listOf(seekTable, padding))
        FlacMeta.writeTags(f, listOf("TITLE" to "x"))
        val types = FlacMeta.readBlocks(f).blocks.map { it.type }
        assertTrue(3 in types, "其它元数据块必须保留：$types")
        assertTrue(FlacMeta.TYPE_PADDING !in types, "padding 被丢弃：$types")
        assertContentEquals(audio, audioPart(f))
    }

    @Test
    fun rewritingTwiceReplacesInsteadOfAppending() {
        val f = writeFlac("e.flac")
        FlacMeta.writeTags(f, listOf("TITLE" to "first"))
        FlacMeta.writeTags(f, listOf("TITLE" to "second", "ARTIST" to "someone"))
        val read = FlacMeta.readTags(f)
        assertEquals("second", read["TITLE"])
        assertEquals("someone", read["ARTIST"])
        assertEquals(1, FlacMeta.readBlocks(f).blocks.count { it.type == FlacMeta.TYPE_VORBIS_COMMENT })
        assertContentEquals(audio, audioPart(f))
    }

    @Test
    fun emptyFileWithoutCommentReadsEmpty() {
        val f = writeFlac("f.flac")
        assertTrue(FlacMeta.readTags(f).isEmpty())
    }

    @Test
    fun imageSizeHandlesUnknownData() {
        val (w, h, _) = FlacMeta.imageSize(ByteArray(10), "image/jpeg")
        assertEquals(0, w)
        assertEquals(0, h)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
