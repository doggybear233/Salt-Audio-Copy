package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import com.doggybear233.saltaudiocopy.settings.RipSettings
import com.doggybear233.saltaudiocopy.ui.PathPreview
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 坞上「输出预览」必须跟着当前设置走。
 *
 * 真机事故：改过输出根之后预览仍显示上一次抓轨的目录（`C:\Users\…\Music\未知艺术家\未知专辑`），
 * 用户以为设置没生效——实际是预览取了 `lastOutDir`。这里把规则钉死。
 */
class PathPreviewTest {

    private val root: Path = Path.of("D:\\音楽")

    private fun meta(
        artist: String = "十明",
        album: String = "変身のレシピ",
        year: String = "2023",
        totalDiscs: Int = 1
    ) = AlbumMeta(
        albumArtist = artist,
        albumTitle = album,
        year = year,
        genre = "J-Pop",
        discId = "abc",
        cddbId = "",
        source = "manual",
        totalDiscs = totalDiscs,
        tracks = listOf(TrackMeta(1, "灰かぶり", artist))
    )

    @Test
    fun folderFollowsTheConfiguredRoot() {
        val s = RipSettings(outputRoot = root.toString())
        val shown = PathPreview.folder(s, "十明", "変身のレシピ", meta(), root)
        assertEquals("D:\\音楽\\十明\\変身のレシピ", shown)
    }

    @Test
    fun changingRootChangesThePreview() {
        val a = RipSettings(outputRoot = root.toString())
        val b = a.copy(outputRoot = "E:\\Rips")
        val shownA = PathPreview.folder(a, "十明", "変身のレシピ", meta(), root)
        val shownB = PathPreview.folder(b, "十明", "変身のレシピ", meta(), Path.of("E:\\Rips"))
        assertTrue(shownB.startsWith("E:\\Rips"), "换根后预览必须跟着换，实际 $shownB")
        assertTrue(shownA != shownB)
    }

    @Test
    fun templatesAreExpandedLikeTheRealRip() {
        val s = RipSettings(
            outputRoot = root.toString(),
            folderTemplate = "%albumartist% - %albumtitle% (%year%)"
        )
        val shown = PathPreview.folder(s, "十明", "変身のレシピ", meta(), root)
        assertEquals("D:\\音楽\\十明 - 変身のレシピ (2023)", shown)
    }

    @Test
    fun unknownAlbumShowsAHintInsteadOfUnknownArtist() {
        val s = RipSettings(outputRoot = root.toString())
        val shown = PathPreview.folder(s, "", "", null, root)
        assertEquals("D:\\音楽\\…", shown, "没有元数据时不要假装知道专辑名")
    }

    @Test
    fun multiDiscAddsSubFolderOnlyWhenTemplateLacksDiscNumber() {
        val s = RipSettings(outputRoot = root.toString(), multiDiscFolder = "CD%discnumber%")
        val m = meta(totalDiscs = 2)
        assertTrue(
            PathPreview.folder(s, "十明", "変身のレシピ", m, root).endsWith("CD1"),
            "多碟且模板里没有 %discnumber% 时要加子目录"
        )
        val withDisc = s.copy(folderTemplate = "%albumartist%/%albumtitle%/CD%discnumber%")
        assertTrue(
            PathPreview.folder(withDisc, "十明", "変身のレシピ", m, root).endsWith("CD1"),
            "模板里已经写了碟号就不该再加一层"
        )
        val singleDisc = PathPreview.folder(s, "十明", "変身のレシピ", meta(totalDiscs = 1), root)
        assertEquals("D:\\音楽\\十明\\変身のレシピ", singleDisc, "单碟不该出现 CD1 子目录")
    }
}
