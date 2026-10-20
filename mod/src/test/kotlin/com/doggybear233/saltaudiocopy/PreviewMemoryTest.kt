package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.settings.RipSettings
import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 预览路径必须是**纯内存**计算。
 *
 * 以前 `outputPreview()` 在组合期直接 `SettingsStore.load()` + 读 `album.json`，
 * 输出根放在 OneDrive / 网络盘时每一帧都要等文件系统，设置页一敲字就顿。
 * 这里用「只给内存状态、不给磁盘」的方式验证：哪怕宿主配置完全不可用，
 * 预览也要立刻给出基于草稿/快照的路径。
 */
class PreviewMemoryTest {

    @BeforeTest
    fun reset() {
        PanelState.showDisc(null)
        PanelState.showCandidates(emptyList())
        PanelState.forgetMeta()
        PanelState.settingsOpen = false
        PanelState.settingsSnapshot = RipSettings()
    }

    @AfterTest
    fun cleanup() {
        PanelState.showDisc(null)
        PanelState.forgetMeta()
        PanelState.settingsOpen = false
    }

    @Test
    fun previewFollowsTheDraftWithoutTouchingDisk() {
        PanelState.loadDraft(RipSettings(outputRoot = "D:\\音楽", folderTemplate = "%albumartist%/%albumtitle%"))
        PanelState.showDisc(PanelState.DiscInfo(3, "十明", "変身のレシピ", "50:36", "DISC-A", true))
        // 草稿只在设置页打开时才是「正在改的那一份」，否则预览跟已保存的设置走
        PanelState.settingsOpen = true
        PanelState.updateDraft { it.copy(outputRoot = "E:\\测试") }

        val shown = PanelState.previewPath
        assertTrue(shown.contains("E:\\测试"), "预览应立刻用草稿里的新根目录，实际是 $shown")
        assertTrue(shown.contains("十明"), "预览应带上专辑艺术家，实际是 $shown")
    }

    @Test
    fun previewSurvivesWithoutHostConfiguration() {
        PanelState.settingsSnapshot = RipSettings()
        PanelState.refreshPreview()
        assertTrue(PanelState.previewPath.isNotBlank(), "没有宿主配置时也要给出可读的预览")
        assertTrue(PanelState.previewPath != "…", "至少应算出一个默认根目录下的路径")
    }

    @Test
    fun discChangeUpdatesPreviewImmediately() {
        PanelState.settingsSnapshot = RipSettings(outputRoot = "D:\\out")
        PanelState.showDisc(PanelState.DiscInfo(2, "A", "B", "10:00", "DISC-A", true))
        val first = PanelState.previewPath
        PanelState.showDisc(PanelState.DiscInfo(2, "C", "D", "10:00", "DISC-B", true))
        val second = PanelState.previewPath
        assertTrue(first != second, "换盘后预览要变，两次都是 $first")
        assertTrue(second.contains("C"), "预览要用新盘的艺术家，实际是 $second")
    }

    @Test
    fun openingSettingsKeepsTheDraftRootInPreview() {
        PanelState.settingsSnapshot = RipSettings()
        PanelState.loadDraft(RipSettings(outputRoot = "F:\\草稿根"))
        PanelState.settingsOpen = true
        PanelState.refreshPreview()
        assertTrue(PanelState.previewPath.contains("F:\\草稿根"), "设置页打开时预览跟草稿走，实际是 ${PanelState.previewPath}")
        PanelState.settingsOpen = false
        PanelState.refreshPreview()
        assertTrue(
            !PanelState.previewPath.contains("F:\\草稿根"),
            "关掉设置页后应回到已保存的设置，实际是 ${PanelState.previewPath}"
        )
    }
}
