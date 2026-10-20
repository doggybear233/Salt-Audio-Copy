package com.doggybear233.saltaudiocopy

import org.junit.jupiter.api.Tag
import com.doggybear233.saltaudiocopy.album.AlbumRipper
import com.doggybear233.saltaudiocopy.rip.RipEngine
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.settings.RipSettings
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 整盘流程真机集成测试：只抓第 1 轨，验证目录/文件/CUE/日志都按模板落盘。
 * 需要 F: 上有音频 CD，否则 skip。
 */
@Tag("live")
class AlbumRipperLiveTest {

    @Test
    fun ripsFirstTrackWithCueAndLog() {
        HwGate.assumeMedia()
        val dev = runCatching { CdDevice("\\\\.\\F:") }.getOrNull()
        Assumptions.assumeTrue(dev != null, "F: 不可用")
        val toc = runCatching { dev!!.readToc() }.getOrNull()
        dev!!.close()
        Assumptions.assumeTrue(toc != null && toc.isAudioCd, "介质不是音频 CD，跳过")
        val scan = RipEngine.scan("\\\\.\\F:")
        val out = Files.createTempDirectory("saltac-live-")
        val settings = RipSettings(
            outputRoot = out.toString(),
            folderTemplate = "%albumartist%/%albumtitle%",
            fileTemplate = "%tracknr2% %title%",
            fetchMetadata = false,
            writeCue = true,
            writeLog = true,
            secureMode = true,
            maxRetries = 2,
            retryDelayMs = 50
        )
        val res = AlbumRipper.rip(scan, settings, { false }, { }, onlyTracks = setOf(1))

        println("album dir=${res.outDir} cue=${res.cueFile} log=${res.logFile} notes=${res.notes}")
        assertEquals(1, res.tracks.size)
        val t = res.tracks[0]
        assertEquals(1, t.number)
        assertEquals("未知艺术家/未知专辑", out.relativize(res.outDir).toString().replace('\\', '/'))
        assertTrue(Files.exists(t.file), "WAV 应存在: ${t.file}")
        // 打完标签后文件会比「44 + 音频」大（LIST/INFO + id3），只能按 data 长度断言
        val facts = com.doggybear233.saltaudiocopy.rip.WavInfo.read(t.file)
            ?: error("不是合法 WAV：${t.file}")
        assertEquals(t.sectors * 2352L, facts.dataBytes, "data 长度应为 扇区*2352")
        assertTrue(Files.size(t.file) > 44L + t.sectors * 2352L, "打完标签的文件应比纯音频大")
        val tagBytes = Files.size(t.file) - 44L - facts.dataBytes
        assertTrue(tagBytes in 1..(1L shl 20), "标签长度应在合理范围，实际 $tagBytes 字节")
        assertTrue(t.crc32 != 0L, "CRC 不应为 0")

        val cue = res.cueFile!!
        assertTrue(Files.exists(cue))
        val cueBytes = Files.readAllBytes(cue)
        assertTrue(
            cueBytes.size > 3 && cueBytes[0] == 0xEF.toByte() &&
                cueBytes[1] == 0xBB.toByte() && cueBytes[2] == 0xBF.toByte(),
            "CUE 应带 UTF-8 BOM"
        )
        val cueText = String(cueBytes, Charsets.UTF_8)
        assertTrue(cueText.contains("TRACK 01 AUDIO"), "CUE 应含 TRACK 01:\n$cueText")
        assertTrue(cueText.contains(t.file.fileName.toString()), "CUE 应引用 WAV 文件名")
        assertTrue(!cueText.replace("\r\n", "").contains("\n"), "不应出现裸 LF")

        val log = String(Files.readAllBytes(res.logFile!!), Charsets.UTF_8)
        assertTrue(log.contains("Salt Audio Copy"), "日志应有版本行")
        assertTrue(
            log.contains(java.lang.Long.toHexString(t.crc32).uppercase().padStart(8, '0')),
            "日志应含该轨 CRC"
        )
        assertTrue(log.contains("secureMode"), "日志应含设置摘要")
    }

    /** 端到端：真盘 → MusicBrainz/gnudb → 封面 → 模板命名 → CUE(含 DISCID) 。联网失败则 skip。 */
    @Test
    fun ripsWithMetadataAndCover() {
        HwGate.assumeMedia()
        val dev = runCatching { CdDevice("\\\\.\\F:") }.getOrNull()
        Assumptions.assumeTrue(dev != null, "F: 不可用")
        val toc = runCatching { dev!!.readToc() }.getOrNull()
        dev!!.close()
        Assumptions.assumeTrue(toc != null && toc.isAudioCd, "介质不是音频 CD，跳过")

        val scan = RipEngine.scan("\\\\.\\F:")
        val out = Files.createTempDirectory("saltac-meta-")
        val settings = RipSettings(
            outputRoot = out.toString(),
            fetchMetadata = true,
            coverSize = 500,
            secureMode = false
        )
        val res = AlbumRipper.rip(scan, settings, { false }, { }, onlyTracks = setOf(1))
        println("meta: album=${res.album?.albumArtist} / ${res.album?.albumTitle} " +
            "src=${res.album?.source} cover=${res.coverFile} notes=${res.notes}")
        Assumptions.assumeTrue(res.album != null, "元数据查询不可用，跳过断言")

        val album = res.album!!
        assertTrue(album.albumArtist.isNotBlank() && album.albumArtist != "未知艺术家", "应拿到艺术家")
        assertTrue(album.albumTitle.isNotBlank() && album.albumTitle != "未知专辑", "应拿到专辑名")
        assertTrue(album.discId.isNotBlank() && album.discId.length == 28, "DiscID 应为 28 字符")

        val cueText = String(Files.readAllBytes(res.cueFile!!), Charsets.UTF_8)
        assertTrue(cueText.contains("REM DISCID ${album.discId}"), "CUE 应含 DISCID")
        assertTrue(cueText.contains(album.albumTitle), "CUE 应含专辑名")

        val track = res.tracks[0]
        assertTrue(!track.title.startsWith("Track "), "曲名应来自元数据: ${track.title}")
        assertTrue(track.file.fileName.toString().contains(track.title.take(4)), "文件名应含曲名: ${track.file}")

        if (res.coverFile != null) {
            assertTrue(Files.size(res.coverFile) > 10_000, "封面应大于 10KB")
        } else {
            println("注意：本次未取到封面（AlbumMeta.cover 为空）")
        }
    }
}
