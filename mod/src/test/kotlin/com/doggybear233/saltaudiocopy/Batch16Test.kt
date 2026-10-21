package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.settings.RipSettings
import com.doggybear233.saltaudiocopy.settings.SettingsStore
import com.doggybear233.saltaudiocopy.ui.HostTargets
import com.doggybear233.saltaudiocopy.ui.PanelMount
import com.doggybear233.saltaudiocopy.ui.PanelState
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 批次 16：可自愈的挂载 + 破坏性操作要确认。
 */
class Batch16Test {

    private val dir = Files.createTempDirectory("saltac-b16")

    @AfterTest
    fun cleanup() {
        Diag.dataDirOverride = null
        PanelState.resetArmed = false
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun mountTargetsStartFromTheParsedCandidatesAndEndWithTheConstant() {
        val targets = PanelMount.mountTargets()
        assertTrue(targets.isNotEmpty(), "至少要有写死常量兜底")
        assertEquals(
            HostTargets.Target(HostTargets.PAGE_CLASS, HostTargets.PAGE_METHOD),
            targets.last(),
            "常量应排在最后：解析器读到真实类名时更可信"
        )
        assertEquals(targets.size, targets.distinct().size, "候选不能重复尝试")
    }

    @Test
    fun resetNeedsASecondClick() {
        Diag.dataDirOverride = dir
        PanelState.resetArmed = false
        val before = SettingsStore.load()

        ConfigActions.resetDefaults()
        assertTrue(PanelState.resetArmed, "第一次只进入待确认态")
        assertEquals(before.outputRoot, SettingsStore.load().outputRoot, "未确认时不能动设置")

        // 第二次才执行（测试环境里写不回宿主配置，所以只验备份已生成）
        ConfigActions.resetDefaults()
        // 备份文件名固定（settings.backup.txt）：以前按时间戳累积，用户不知道该还原哪一份
        val backups = Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith("settings.backup") }.toList() }
        assertTrue(backups.isNotEmpty(), "重置前必须先备份")
        val text = Files.readString(backups.first())
        assertTrue(text.contains("outputRoot="), text)
        assertFalse(text.isBlank())
    }

    @Test
    fun backupContainsTheRiskierFields() {
        Diag.dataDirOverride = dir
        val path = assertNotNull(
            ConfigActions.writeSettingsBackup(
                RipSettings(offsetSamples = 6, folderTemplate = "%albumartist%/%albumtitle%", driveSpeed = 1200)
            )
        )
        val text = Files.readString(path)
        assertTrue(text.contains("offsetSamples=6"), text)
        assertTrue(text.contains("folderTemplate=%albumartist%/%albumtitle%"), text)
        assertTrue(text.contains("driveSpeed=1200"), text)
    }
}
