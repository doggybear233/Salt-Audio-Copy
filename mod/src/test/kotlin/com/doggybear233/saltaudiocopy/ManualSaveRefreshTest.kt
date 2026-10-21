package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.ManualDraft
import com.doggybear233.saltaudiocopy.ui.ManualTrack
import com.doggybear233.saltaudiocopy.ui.PanelState
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 保存手填元数据时的「立刻刷新」逻辑。
 *
 * 用户反馈：点保存后坞上还是旧名字，要手动刷新才变。
 * 根因是保存动作排在读盘 + 查元数据的工作队列后面；现在先用草稿改面板状态，再后台落盘。
 */
class ManualSaveRefreshTest {

    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = TempDirs.create("saltac-manualsave")
        resetPanel()
    }

    @AfterTest
    fun tearDown() {
        resetPanel()
        runCatching {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun resetPanel() {
        PanelState.disc = null
        PanelState.showCandidates(emptyList())
        PanelState.manualOpen = false
        PanelState.lookupTried = false
        PanelState.manual = ManualDraft()
    }

    private fun draft(artist: String = "十明", album: String = "変身のレシピ", titles: Int = 3) =
        ManualDraft(
            albumArtist = artist,
            albumTitle = album,
            year = "2024",
            discNumber = 1,
            totalDiscs = 1,
            tracks = (1..titles).map { ManualTrack(number = it, title = "曲$it", artist = "") }
        )

    @Test
    fun optimisticDiscUsesDraftNamesImmediately() {
        val before = PanelState.DiscInfo(
            tracks = 13, artist = "未知艺术家", album = "未知专辑",
            lengthText = "1:18:54", discId = "abc123", audio = true
        )
        val info = ConfigActions.optimisticDisc(draft(), before)
        assertEquals("十明", info.artist)
        assertEquals("変身のレシピ", info.album)
        assertEquals(3, info.tracks)
        // 盘上已知的东西要保留，不能因为手填而丢
        assertEquals("1:18:54", info.lengthText)
        assertEquals("abc123", info.discId)
        assertTrue(info.audio)
    }

    @Test
    fun optimisticDiscWorksBeforeAnyScan() {
        val info = ConfigActions.optimisticDisc(draft(artist = "", album = "只有专辑名"), null)
        assertEquals("只有专辑名", info.album)
        assertEquals("", info.lengthText)
        assertEquals("", info.discId)
        assertTrue(info.audio, "没扫过盘时按音频 CD 处理，允许直接抓")
    }

    @Test
    fun namesSurviveAnIntermediatePublishWithoutCache() {
        // 复现用户报告：保存后 freshScan/publish 在 JSON 写好之前跑了一轮，
        // 名字被刷空，之后必须能盖回（否则要「再开一次手动填写」才显示）。
        val draft = draft()
        PanelState.showDisc(
            PanelState.DiscInfo(13, "", "", "1:18:54", "abc", true)
        )
        // 中间那一次：缓存还没有，publish 只能给出空名字
        PanelState.showDisc(PanelState.DiscInfo(13, "", "", "1:18:54", "abc", true))
        // 保存流程的补偿：再盖一次乐观值
        PanelState.showDisc(ConfigActions.optimisticDisc(draft, PanelState.disc))
        assertEquals("十明", PanelState.disc?.artist)
        assertEquals("変身のレシピ", PanelState.disc?.album)
    }

    @Test
    fun savingUpdatesPanelStateRightAway() {
        // 只验证第 1 步（界面刷新）；落盘依赖光驱，硬件用例在 SALTAC_HW=1 下另跑
        val draft = draft()
        PanelState.openManual(draft)
        PanelState.showDisc(
            PanelState.DiscInfo(13, "未知艺术家", "未知专辑", "1:18:54", "abc", true)
        )
        PanelState.showDisc(ConfigActions.optimisticDisc(PanelState.manual, PanelState.disc))
        PanelState.showCandidates(emptyList())
        PanelState.lookupTried = true
        PanelState.closeManual()

        assertEquals("十明", PanelState.disc?.artist)
        assertEquals("変身のレシピ", PanelState.disc?.album)
        assertEquals(3, PanelState.disc?.tracks)
        assertTrue(PanelState.lookupTried)
        assertTrue(!PanelState.manualOpen)
    }
}
