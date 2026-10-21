package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.FreeSpace
import com.doggybear233.saltaudiocopy.album.ImageSplicer
import com.doggybear233.saltaudiocopy.rip.WavInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 第 3 轮 r3-05：空间预检口径、镜像拼接的原子性与可取消性。 */
class BatchR305Test {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltac-r305")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun part(name: String, sectors: Int, seed: Byte): Path {
        val data = ByteArray(sectors * 2352) { seed }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size)
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(44_100).putInt(176_400).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(data.size)
        val p = dir.resolve(name)
        Files.write(p, h.array() + data)
        return p
    }

    @Test
    fun unknownFreeSpaceDoesNotBlockTheRip() {
        // usableBytes 问不到（网络盘/权限不足）时是 -1：以前会显示「可用 -0.0 GB」并拒绝抓轨
        assertNull(FreeSpace.check(-1L, 340_448, 21))
        // 不存在的盘符：找不到任何已存在的父目录，getFileStore 抛错 → -1
        assertEquals(-1L, FreeSpace.usableBytes(Path.of("Q:\\no-such-dir")))
    }

    @Test
    fun flacAndImageModeRaiseTheEstimate() {
        val plain = FreeSpace.peakBytes(100_000, 10, "wav", imageMode = false)
        val flac = FreeSpace.peakBytes(100_000, 10, "flac", imageMode = false)
        val image = FreeSpace.peakBytes(100_000, 10, "wav", imageMode = true)
        assertTrue(flac > plain, "FLAC 模式编码期间 WAV 与 FLAC 并存，峰值要更高")
        assertTrue(image > plain, "整盘镜像是额外一份完整音频")
    }

    @Test
    fun cancelledSpliceLeavesPreviousImageUntouched() {
        val a = part("a.wav", 2, 1)
        val b = part("b.wav", 2, 2)
        val out = dir.resolve("image.wav")
        Files.write(out, byteArrayOf(9, 9, 9))
        val before = Files.readAllBytes(out)

        val r = ImageSplicer.splice(listOf(a, b), out) { true }
        assertNull(r, "取消时返回 null")
        assertEquals(before.toList(), Files.readAllBytes(out).toList(), "上一次的镜像必须原样保留")
        assertTrue(
            Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().endsWith(".part") } },
            "取消后不能留下 .part 半成品"
        )
    }

    @Test
    fun failedSpliceDoesNotCorruptPreviousImage() {
        val good = part("good.wav", 2, 1)
        val bad = dir.resolve("bad.wav")
        Files.write(bad, byteArrayOf(1, 2, 3, 4))
        val out = dir.resolve("image.wav")
        Files.write(out, byteArrayOf(7, 7))
        val before = Files.readAllBytes(out)

        assertFailsWith<IllegalArgumentException> {
            ImageSplicer.splice(listOf(good, bad), out)
        }
        assertEquals(before.toList(), Files.readAllBytes(out).toList(), "失败的拼接不能动上一次的镜像")
        assertTrue(
            Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().endsWith(".part") } },
            "失败后不能留下 .part 半成品"
        )
    }

    @Test
    fun successfulSpliceWritesDeclaredDataOnly() {
        val a = part("a.wav", 3, 1)
        val b = part("b.wav", 5, 2)
        val out = dir.resolve("image.wav")
        val r = ImageSplicer.splice(listOf(a, b), out)!!
        assertEquals(8L * 2352, r.dataBytes)
        assertEquals(8L, r.sectors)
        assertEquals(44L + 8 * 2352, Files.size(out))
        assertEquals(8L * 2352, WavInfo.read(out)!!.dataBytes)
    }
}
