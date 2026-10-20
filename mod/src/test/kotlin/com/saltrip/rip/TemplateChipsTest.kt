package com.saltrip.rip

import com.saltrip.rip.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 模板块编辑器：选中字段后追加/清空。 */
class TemplateChipsTest {

    @AfterTest
    fun reset() {
        PanelState.editFolder = ""
        PanelState.editFile = ""
        PanelState.editCover = ""
        PanelState.editTarget = 1
        PanelState.clearProgress()
    }

    @Test
    fun appendGoesToSelectedField() {
        PanelState.editTarget = 1
        PanelState.appendToken("%tracknr2%")
        PanelState.appendToken(" ")
        PanelState.appendToken("%title%")
        assertEquals("%tracknr2% %title%", PanelState.editFile)
        assertEquals("", PanelState.editFolder, "不该串到别的字段")

        PanelState.editTarget = 0
        PanelState.appendToken("%albumartist%")
        PanelState.appendToken("/")
        PanelState.appendToken("%albumtitle%")
        assertEquals("%albumartist%/%albumtitle%", PanelState.editFolder)
        assertEquals("%tracknr2% %title%", PanelState.editFile, "目录编辑不影响文件名")

        PanelState.editTarget = 2
        PanelState.appendToken("cover")
        assertEquals("cover", PanelState.editCover)
    }

    @Test
    fun clearOnlyAffectsSelectedField() {
        PanelState.editFolder = "a"
        PanelState.editFile = "b"
        PanelState.editCover = "c"
        PanelState.editTarget = 0
        PanelState.clearTarget()
        assertEquals("", PanelState.editFolder)
        assertEquals("b", PanelState.editFile)
        assertEquals("c", PanelState.editCover)
    }
}
