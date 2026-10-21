package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.rip.FakeTrackSource
import com.doggybear233.saltaudiocopy.rip.RipEngine
import com.doggybear233.saltaudiocopy.rip.ScanResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「只测不写」回归：不能因为输出路径没有父目录就 NPE。
 *
 * 用户实测 0.22.x：点「只测不写」直接报
 * `Cannot invoke "java.nio.file.Path.getFileSystem()" because "<parameter1>" is null`，
 * 之后 CD 工具箱整页卡住只能重启。根因是 `ripTrack` 无条件 `Files.createDirectories(outFile.parent)`。
 */
class NoWriteRipTest {

    private val dir: Path by lazy { TempDirs.create("saltac-nowrite") }

    @AfterTest
    fun tearDown() {
        runCatching {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun scan(sectors: Int, leadOut: Int = sectors) = ScanResult(
        "\\\\.\\X:",
        FakeTrackSource.inquiry(),
        FakeTrackSource.disc3Tracks(leadOut)
    )

    @Test
    fun writeFileFalseToleratesPathWithoutParent() {
        val fake = FakeTrackSource(sectors = 6000)
        val result = RipEngine.ripTrack(
            scan = scan(6000),
            trackIndex = 0,
            outFile = Path.of("__test_only__.tmp"),
            cancel = { false },
            onProgress = {},
            writeFile = false,
            source = fake
        )
        assertEquals(2000, result.sectors)
        assertFalse(result.cancelled)
        assertEquals(2000L * 2352, result.bytes)
        assertFalse(
            Files.exists(Path.of("__test_only__.tmp")),
            "只测不写不允许在磁盘上留下任何文件"
        )
    }

    @Test
    fun writeFileFalseStillMatchesNormalCrc() {
        val fake = FakeTrackSource(sectors = 6000)
        val normal = RipEngine.ripTrack(
            scan = scan(6000),
            trackIndex = 0,
            outFile = dir.resolve("01.wav"),
            cancel = { false },
            onProgress = {},
            source = fake
        )
        val testOnly = RipEngine.ripTrack(
            scan = scan(6000),
            trackIndex = 0,
            outFile = dir.resolve("02.wav"),
            cancel = { false },
            onProgress = {},
            writeFile = false,
            source = fake
        )
        assertEquals(normal.crc32, testOnly.crc32, "测试通道的 CRC 必须与正式抓轨一致")
        assertFalse(Files.exists(dir.resolve("02.wav")), "测试通道不应写出文件")
    }

    @Test
    fun writeFileTrueStillCreatesDirectories() {
        val fake = FakeTrackSource(sectors = 6000)
        val nested = dir.resolve("a").resolve("b").resolve("01.wav")
        val crc = CRC32()
        for (lba in 0 until 2000) crc.update(fake.sector(lba))
        val result = RipEngine.ripTrack(
            scan = scan(6000),
            trackIndex = 0,
            outFile = nested,
            cancel = { false },
            onProgress = {},
            source = fake
        )
        assertTrue(Files.exists(nested))
        assertEquals(crc.value, result.crc32)
    }
}
