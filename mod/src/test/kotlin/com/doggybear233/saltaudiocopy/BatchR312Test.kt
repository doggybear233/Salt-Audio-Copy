package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import com.doggybear233.saltaudiocopy.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第 3 轮 MINOR 清理：设置备份可恢复、卸载清理、混合模式盘时长。
 *
 * 三个都是「用户能看见但以前没人管」的小问题：备份攒成一堆无从还原、卸载后日志残留、
 * 带数据轨的盘时长虚高。
 */
class BatchR312Test {

    private val tmp: Path = Files.createTempDirectory("r312")

    @AfterTest
    fun cleanup() {
        Diag.dataDirOverride = null
        Files.walk(tmp).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    private fun toc(vararg tracks: Triple<Int, Boolean, Int>, leadOut: Int): DiscToc {
        val entries = tracks.map { (n, audio, lba) -> TocEntry(n, audio, 1, lba) } +
            TocEntry(0xAA, false, 1, leadOut)
        return DiscToc(tracks.first().first, tracks.last().first, entries)
    }

    @Test
    fun backupCanBeReadBackIntoTheSettings() {
        Diag.dataDirOverride = tmp
        val base = RipSettings()
        val file = ConfigActions.writeSettingsBackup(
            base.copy(offsetSamples = -116, driveSpeed = 2400, secureMode = false, folderTemplate = "%albumtitle%")
        )
        assertTrue(file != null && Files.isRegularFile(file), "备份应该写出来")

        val data = Files.readAllLines(file!!, Charsets.UTF_8)
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1)
            }
            .toMap()
        val restored = ConfigActions.applySettingValues(base, data)
        assertEquals(-116, restored.offsetSamples)
        assertEquals(2400, restored.driveSpeed)
        assertFalse(restored.secureMode)
        assertEquals("%albumtitle%", restored.folderTemplate)
    }

    @Test
    fun unknownOrBrokenValuesAreIgnoredInsteadOfCrashing() {
        val base = RipSettings()
        val restored = ConfigActions.applySettingValues(
            base,
            mapOf("noSuchKey" to "1", "maxRetries" to "not-a-number", "secureMode" to "maybe")
        )
        assertEquals(base, restored)
    }

    @Test
    fun settingsBackupUsesOneFixedFile() {
        Diag.dataDirOverride = tmp
        val a = ConfigActions.writeSettingsBackup(RipSettings(driveSpeed = 1200))
        val b = ConfigActions.writeSettingsBackup(RipSettings(driveSpeed = 2400))
        assertEquals(a, b, "备份文件名固定，重复恢复默认不该攒一堆文件")
    }

    @Test
    fun uninstallRemovesLogAndBackupButKeepsAlbumCache() {
        Diag.dataDirOverride = tmp
        val backup = ConfigActions.writeSettingsBackup(RipSettings())!!
        val logs = Files.createDirectories(tmp.resolve("logs"))
        val log = logs.resolve(LOG_NAME)
        Files.writeString(log, "x")
        val cache = Files.createDirectories(tmp.resolve("album-cache"))
        val cached = cache.resolve("album-x.json")
        Files.writeString(cached, "{}")

        ConfigActions.cleanupOnDelete()

        assertFalse(Files.exists(backup), "卸载该删掉设置备份")
        assertFalse(Files.exists(log), "卸载该删掉日志")
        assertTrue(Files.exists(cached), "专辑缓存是用户数据，不能删")
    }

    @Test
    fun mixedModeDiscLengthCountsOnlyAudioTracks() {
        // 第 1 轨是数据轨（5000 扇区），第 2 轨才是音频（3000 扇区）
        val disc = toc(
            Triple(1, false, 0),
            Triple(2, true, 5000),
            leadOut = 8000
        )
        assertEquals(3000, ConfigActions.playableSectors(disc))
    }

    @Test
    fun pureAudioDiscLengthIsUnchanged() {
        val disc = toc(Triple(1, true, 0), Triple(2, true, 4000), leadOut = 8000)
        assertEquals(8000, ConfigActions.playableSectors(disc))
    }
}
