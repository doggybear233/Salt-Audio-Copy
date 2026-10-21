package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumRipper
import com.doggybear233.saltaudiocopy.album.AlbumTrackResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R4-10：整盘镜像失败/取消后逐轨 WAV 会留下来。
 *
 * 抓轨主循环按「镜像模式」跳过了逐轨标签，失败路径必须补写——
 * 否则用户留下的是一堆没有专辑信息的 WAV。
 */
class BatchR410RetainedTagsTest {

    private fun wavFile(dir: Path, name: String, dataBytes: Int): Path {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
        header.putInt(44100).putInt(44100 * 4).putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(dataBytes)
        val p = dir.resolve(name)
        Files.write(p, header.array() + ByteArray(dataBytes))
        return p
    }

    @Test
    fun retainedPartsGetAlbumTags() {
        val dir = Files.createTempDirectory("sac-retag")
        try {
            val p = wavFile(dir, "01 - track.wav", 1000)
            val before = p.readBytes().size
            AlbumRipper.tagRetainedParts(
                listOf(
                    AlbumTrackResult(
                        number = 1, title = "First", artist = "Someone", file = p,
                        startLba = 0, sectors = 1, crc32 = 0, errors = 0, elapsedMs = 1
                    )
                ),
                album = null,
                albumArtist = "Some Artist",
                albumTitle = "Some Album",
                totalTracks = 2
            )
            val bytes = p.readBytes()
            assertTrue(bytes.size > before, "没有追加标签块")
            val text = String(bytes, Charsets.ISO_8859_1)
            assertTrue(text.contains("INFO"), "缺少 LIST/INFO")
            assertTrue(text.contains("Some Album"), "没有写入专辑名")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
