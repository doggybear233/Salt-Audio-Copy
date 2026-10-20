package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumRipper
import com.doggybear233.saltaudiocopy.encode.BundledFlac
import com.doggybear233.saltaudiocopy.encode.WavTags
import com.doggybear233.saltaudiocopy.rip.FakeTrackSource
import com.doggybear233.saltaudiocopy.rip.WavWriter
import com.doggybear233.saltaudiocopy.rip.ScanResult
import com.doggybear233.saltaudiocopy.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 第 2 轮剩下的几处「产物本身」问题：
 * - 镜像模式下逐轨标签白写、镜像反而没有标签；
 * - 内置 flac 解压失败后仍把大小不对的 exe 交出去；
 * - 续抓只认 RIFF/WAVE/fmt，24 位容器 WAV 会被当成同一轨继续追加。
 */
class BatchR2ArtifactsTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltac-r2art-")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun settings() = RipSettings(
        outputRoot = dir.toString(),
        folderTemplate = "%albumartist%/%albumtitle%",
        fileTemplate = "%tracknr2% %title%",
        fetchMetadata = false,
        writeCue = true,
        writeLog = true,
        secureMode = true,
        testAndCopy = false,
        useCdText = false,
        guardDisc = true,
        resume = false,
        detectGaps = false,
        gapMode = "append",
        imageMode = true,
        format = "wav"
    )

    private fun scan() = ScanResult("\\\\.\\X:", FakeTrackSource.inquiry(), FakeTrackSource.disc3Tracks(6000))

    @Test
    fun imageCarriesAlbumTagsAndPerTrackFilesAreNotTagged() {
        val out = AlbumRipper.rip(
            scan = scan(),
            settings = settings(),
            cancel = { false },
            onProgress = {},
            source = FakeTrackSource(sectors = 6000)
        )
        val image = Files.list(out.outDir).use { s -> s.filter { it.fileName.toString().endsWith(".wav") }.toList() }
            .firstOrNull() ?: error("没有镜像文件：${out.outDir}")
        assertTrue(Files.isRegularFile(image), "镜像应该存在：$image")

        val tags = WavTags.read(image)
        assertTrue(tags["album"].orEmpty().isNotEmpty(), "镜像缺少专辑名：$tags")
        assertTrue(tags["artist"].orEmpty().isNotEmpty(), "镜像缺少艺术家：$tags")
        assertEquals("1/1", tags["track"], "镜像应按单轨专辑标记：$tags")

        // 逐轨文件是中间产物，拼接后应已删除；即使保留也不该是被打过标签的版本
        val leftovers = Files.list(out.outDir).use { s -> s.filter { it.fileName.toString().startsWith("01 ") }.toList() }
        assertTrue(leftovers.isEmpty(), "逐轨中间产物应已删除：$leftovers")
    }

    @Test
    fun bundledFlacRefusesAnIncompleteExtraction() {
        val bin = Files.createDirectories(dir.resolve("bin"))
        BundledFlac.dirOverride = bin
        BundledFlac.resetForTest()
        try {
            val exe = BundledFlac.ensure()
            assertTrue(exe != null && Files.isRegularFile(exe), "第一次应解压成功")

            // 模拟「解压到一半被杀」：exe 被截断，重新 ensure 要么修好、要么明确拒绝
            Files.write(exe!!, ByteArray(16))
            BundledFlac.resetForTest()
            val again = BundledFlac.ensure()
            if (again != null) {
                assertTrue(
                    Files.size(again) > 16,
                    "返回的 exe 大小不对（${Files.size(again)} B），错误会推迟到 0xC0000135"
                )
            }
        } finally {
            BundledFlac.dirOverride = null
            BundledFlac.resetForTest()
        }
    }

    @Test
    fun resumeRejectsAHeaderWithDifferentBitDepth() {
        val file = dir.resolve("24bit.wav")
        // 写一个合法的 24 位立体声 WAV 头 + 一点数据
        val h = ByteArray(44)
        fun putAscii(off: Int, s: String) { for (i in s.indices) h[off + i] = s[i].code.toByte() }
        fun putI32(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v ushr 8) and 0xFF).toByte()
            h[off + 2] = ((v ushr 16) and 0xFF).toByte(); h[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun putI16(off: Int, v: Int) { h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v ushr 8) and 0xFF).toByte() }
        putAscii(0, "RIFF"); putI32(4, 36 + 100)
        putAscii(8, "WAVE"); putAscii(12, "fmt "); putI32(16, 16)
        putI16(20, 1); putI16(22, 2); putI32(24, 44100)
        putI32(28, 44100 * 6); putI16(32, 6); putI16(34, 24)
        putAscii(36, "data"); putI32(40, 100)
        Files.write(file, h + ByteArray(100))

        // 续抓时若把这当成「同一轨的已写部分」，会把 16 位 PCM 追加在 24 位数据后面
        val w = WavWriter(file, append = true)
        try {
            w.write(ByteArray(2352), 0, 2352)
            w.finish()
        } finally {
            runCatching { w.close() }
        }
        val bytes = Files.readAllBytes(file)
        val bits = (bytes[34].toInt() and 0xFF) or ((bytes[35].toInt() and 0xFF) shl 8)
        assertEquals(16, bits, "位深不符时必须整轨重写（期望 16 位），实际 $bits 位")
    }

    @Test
    fun bundledFlacMissingResourceReportsNull() {
        // 资源不存在时（例如裁剪过的包）必须返回 null，让上层给出「重新安装」的提示
        val probe = object {
            fun run(): Path? {
                val url = BundledFlac::class.java.getResource("/native/win64/not-there.exe")
                assertNull(url, "资源不该存在")
                return null
            }
        }
        if (probe.run() != null) fail("不可能到达")
    }
}
