package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelState
import com.doggybear233.saltaudiocopy.ui.Strings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 多语言表与多光驱选择。 */
class StringsAndDrivesTest {

    @AfterTest
    fun reset() {
        Strings.override(null)
        PanelState.drives = emptyList()
        PanelState.selectedDrive = ""
    }

    @Test
    fun threeLanguagesCoverSameKeys() {
        val zh = keysOf(Strings.Lang.ZH)
        val en = keysOf(Strings.Lang.EN)
        val ja = keysOf(Strings.Lang.JA)
        assertEquals(zh, en, "英文表缺键：${zh - en} / 多键：${en - zh}")
        assertEquals(zh, ja, "日文表缺键：${zh - ja} / 多键：${ja - zh}")
        assertTrue(zh.contains("rip_album") && zh.contains("title"))
    }

    @Test
    fun overridePicksLanguage() {
        Strings.override(Strings.Lang.EN)
        assertEquals("Salt Audio Copy", Strings.key("title"))
        Strings.override(Strings.Lang.JA)
        assertEquals("全曲取り込み", Strings.key("rip_album"))
        Strings.override(Strings.Lang.ZH)
        assertEquals("抓整盘", Strings.key("rip_album"))
    }

    @Test
    fun unknownKeyFallsBackToKeyItself() {
        Strings.override(Strings.Lang.EN)
        assertEquals("no_such_key", Strings.key("no_such_key"))
    }

    @Test
    fun driveChipsHoldLetterAndAudioFlag() {
        PanelState.drives = listOf(
            PanelState.DriveChip("F", "F: 12 轨", true),
            PanelState.DriveChip("G", "G: 3 轨", false)
        )
        PanelState.selectedDrive = "F"
        assertEquals(2, PanelState.drives.size)
        assertTrue(PanelState.drives.first { it.letter == "F" }.audio)
        assertTrue(!PanelState.drives.first { it.letter == "G" }.audio)
    }

    /** 反射拿三张表的键集合，避免为测试单独开内部 API。 */
    private fun keysOf(lang: Strings.Lang): Set<String> {
        val f = Strings::class.java.getDeclaredField(
            when (lang) {
                Strings.Lang.ZH -> "ZH"
                Strings.Lang.EN -> "EN"
                Strings.Lang.JA -> "JA"
            }
        )
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (f.get(Strings) as Map<String, String>).keys
    }
}
