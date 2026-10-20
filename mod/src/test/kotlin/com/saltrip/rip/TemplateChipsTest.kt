package com.saltrip.rip

import com.saltrip.rip.settings.RipSettings
import com.saltrip.rip.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 模板块编辑器：选中字段后追加/清空。 */
class TemplateChipsTest {

    @AfterTest
    fun reset() {
        PanelState.loadDraft(RipSettings())
        PanelState.clearProgress()
    }

    @Test
    fun appendGoesToSelectedField() {
        PanelState.loadDraft(RipSettings())
        PanelState.draft = PanelState.draft.copy(folderTemplate = "", fileTemplate = "", coverFileName = "")
        PanelState.editTarget = 1
        PanelState.appendToken("%tracknr2%")
        PanelState.appendToken(" ")
        PanelState.appendToken("%title%")
        assertEquals("%tracknr2% %title%", PanelState.editFile)
        assertEquals("", PanelState.editFolder, "不该写到别的模板")

        PanelState.editTarget = 0
        PanelState.appendToken("%albumartist%")
        PanelState.appendToken("/")
        PanelState.appendToken("%albumtitle%")
        assertEquals("%albumartist%/%albumtitle%", PanelState.editFolder)
        assertEquals("%tracknr2% %title%", PanelState.editFile, "目标模板不影响文件名")

        PanelState.editTarget = 2
        PanelState.appendToken("cover")
        assertEquals("cover", PanelState.editCover)
    }

    @Test
    fun appendWithoutTargetChangesNothing() {
        PanelState.loadDraft(RipSettings())
        PanelState.draft = PanelState.draft.copy(folderTemplate = "", fileTemplate = "", coverFileName = "")
        PanelState.editTarget = -1
        PanelState.appendToken("%title%")
        assertEquals("", PanelState.editFolder)
        assertEquals("", PanelState.editFile)
        assertEquals("", PanelState.editCover)
    }

    @Test
    fun clearOnlyAffectsSelectedField() {
        PanelState.loadDraft(RipSettings(folderTemplate = "a", fileTemplate = "b", coverFileName = "c"))
        PanelState.editTarget = 0
        PanelState.clearTarget()
        assertEquals("", PanelState.editFolder)
        assertEquals("b", PanelState.editFile)
        assertEquals("c", PanelState.editCover)
    }
}
