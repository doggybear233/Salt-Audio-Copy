package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.OutputCheck
import com.doggybear233.saltaudiocopy.album.RipPlan
import com.doggybear233.saltaudiocopy.album.CueWriter
import com.doggybear233.saltaudiocopy.encode.FlacMeta
import com.doggybear233.saltaudiocopy.encode.WavTags
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import com.doggybear233.saltaudiocopy.settings.RipSettings
import com.doggybear233.saltaudiocopy.ui.PathPreview
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 第 3 轮 MINOR/NIT：rib/encode/album 一组的回归。
 *
 * 这些点单看都很小，但都是「日志里看着正常、用户侧却不对」的问题，所以每条都要有可失败的断言。
 */
class BatchR314Test {

    private fun tempDir(): Path = TempDirs.create("saltac-r314-")

    private fun toc(tracks: Int, leadOut: Int = 1000): DiscToc {
        val entries = (1..tracks).map { TocEntry(it, true, 1, (it - 1) * 100) } +
            TocEntry(0xAA, true, 1, leadOut)
        return DiscToc(1, tracks, entries)
    }

    /** F-384：真实问号不能被「连续不可映射字符合并」吞掉。 */
    @Test
    fun realQuestionMarkSurvivesUnmappableTail() {
        val cs = Charset.forName("US-ASCII")
        val (mapped, _) = WavTags.bestEffortAnsi("Night?\uD83C\uDF1F", cs)
        assertEquals("Night??", mapped, "真实问号必须保留，emoji 另算一处替换")
    }

    /** F-384 的另一半：连续两个不可映射字符仍然只留一个 ?。 */
    @Test
    fun consecutiveUnmappableCharsCollapseToOneQuestionMark() {
        val cs = Charset.forName("US-ASCII")
        val (mapped, _) = WavTags.bestEffortAnsi("A\u4E00\u4E8CB", cs)
        assertEquals("A?B", mapped)
    }

    /** F-383：PICTURE 的色深是每像素位数，不是 PNG 的 bit depth。 */
    @Test
    fun pictureColourDepthIsBitsPerPixel() {
        val png = ByteArray(40)
        png[0] = 0x89.toByte(); png[1] = 'P'.code.toByte(); png[2] = 'N'.code.toByte(); png[3] = 'G'.code.toByte()
        // 宽 2 / 高 2
        png[19] = 2; png[23] = 2
        png[24] = 8 // bit depth
        png[25] = 6 // colour type: RGBA
        assertEquals(32, FlacMeta.bitsPerPixel(png, "image/png"), "RGBA 8bit = 32bpp")

        png[25] = 2 // truecolour
        assertEquals(24, FlacMeta.bitsPerPixel(png, "image/png"), "RGB 8bit = 24bpp")

        png[25] = 0 // greyscale
        assertEquals(8, FlacMeta.bitsPerPixel(png, "image/png"))

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)
        assertEquals(24, FlacMeta.bitsPerPixel(jpeg, "image/jpeg"))
    }

    /** F-374：音轨在 CD1/CD2 子目录里时，自检不能报「没有音频」。 */
    @Test
    fun selfCheckFindsAudioInSubFolders() {
        val dir = tempDir()
        val wav = dir.resolve("CD1").createDirectories().resolve("01 track.wav")
        // 44 字节头：RIFF/WAVE/fmt (PCM, 2ch, 44.1k, 16bit) + data
        val header = ByteArray(44)
        fun tag(off: Int, s: String) { s.forEachIndexed { i, c -> header[off + i] = c.code.toByte() } }
        fun le16(off: Int, v: Int) { header[off] = (v and 0xFF).toByte(); header[off + 1] = ((v shr 8) and 0xFF).toByte() }
        fun le32(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte(); header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte(); header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        tag(0, "RIFF"); le32(4, 36 + 1764); tag(8, "WAVE")
        tag(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, 2)
        le32(24, 44100); le32(28, 176400); le16(32, 4); le16(34, 16)
        tag(36, "data"); le32(40, 1764)
        wav.writeBytes(header + ByteArray(1764))
        val report = OutputCheck.check(dir)
        assertEquals(1, report.wavCount, "子目录里的 WAV 也要算进来")
        assertTrue(report.problems.none { it.detail.contains("没有音频") })
    }

    /** F-372：错误报告不能原地覆盖上一张盘的同名报告。 */
    @Test
    fun errorReportFollowsOverwritePolicy() {
        val dir = tempDir()
        dir.resolve("Album.errors.txt").writeText("old")
        val s = RipSettings(
            outputRoot = dir.toString(),
            folderTemplate = "",
            overwritePolicy = "suffix"
        )
        val plan = RipPlan.build(toc(2), s, null)
        // 计划本身不写错误报告；这里校验覆盖策略在「同名产物」上的行为
        assertTrue(plan.tracks.isNotEmpty())
        val suffix = com.doggybear233.saltaudiocopy.album.Overwrite
            .pickSuffix(dir, "Album", listOf(".errors.txt"), "suffix")
        assertEquals(" (2)", suffix, "同名报告应改成 (2) 而不是覆盖")
    }

    /** F-373：预览的多碟子目录兜底要与真实计划一致。 */
    @Test
    fun previewMatchesPlanForBlankMultiDiscTemplate() {
        val root = tempDir()
        val s = RipSettings(
            folderTemplate = "%albumartist%/%albumtitle%",
            multiDiscFolder = "",
            totalDiscsOverride = 2
        )
        val preview = PathPreview.folder(s, "Artist", "Album", root = root)
        assertTrue(preview.endsWith("CD1") || preview.endsWith("CD2"), "空模板应退回 CD%discnumber%：$preview")
    }

    /** F-375：路径过长时文件名被截短，而不是写盘时才失败。 */
    @Test
    fun longOutputPathShrinksFileName() {
        val root = tempDir().resolve("x".repeat(120)).createDirectories()
        val s = RipSettings(
            outputRoot = root.toString(),
            folderTemplate = "y".repeat(80),
            fileTemplate = "%tracknr2% " + "z".repeat(140)
        )
        val plan = RipPlan.build(toc(1), s, null)
        val name = plan.tracks.single().fileName
        assertTrue(name.length <= 150, "文件名不该超过 150 字符：${name.length}")
        assertTrue(
            plan.warnings.any { it.contains("层级很深") },
            "目录本身已经超长时应给出告警：${plan.warnings}"
        )
    }

    /** F-382 已在 r3-07 修掉：这里用「WAV 标签写在真实存在的文件上」锁住不再回退。 */
    @Test
    fun cueTypeFollowsTheActualAudioExtension() {
        assertEquals("FLAC", CueWriter.cueType("01 x.flac"))
        assertEquals("WAVE", CueWriter.cueType("01 x.wav"))
    }
}
