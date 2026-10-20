package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 抽盘 / 放回同一张盘的记忆（用户反馈 B6）。
 *
 * 期望的行为：抽盘立刻变「没有光盘」；同一张盘放回来能接着抓；换了盘当新盘。
 */
class DiscMemoryTest {

    private fun info(discId: String, album: String) = PanelState.DiscInfo(
        tracks = 2,
        artist = "Def Tech",
        album = album,
        lengthText = "1:18:54",
        discId = discId,
        audio = true
    )

    @AfterTest
    fun cleanup() {
        DiscMemory.clearForTest()
        PanelState.showDisc(null)
        PanelState.showCandidates(emptyList())
        PanelState.lookupTried = false
        PanelState.ripDone = false
        PanelState.lastOutDir = null
    }

    @Test
    fun ejectionClearsTheCardButRemembersIt() {
        PanelState.showDisc(info("abc", "GREATEST HITS"))
        PanelState.lookupTried = true
        PanelState.ripDone = true
        DiscMemory.remember("fp1")

        PanelState.markEjected("光盘已弹出")

        assertNull(PanelState.disc, "抽盘后不该还显示某张盘")
        assertEquals("光驱里没有光盘", PanelState.status)
        assertTrue(PanelState.detail.contains("放回来"), "要告诉用户能放回同一张盘：${PanelState.detail}")
        assertTrue(DiscMemory.find("fp1") != null, "同一张盘放回来时要能取到记忆")
    }

    @Test
    fun sameDiscRestoresNamesAndRipDone() {
        PanelState.showDisc(info("abc", "GREATEST HITS"))
        PanelState.ripDone = true
        DiscMemory.remember("fp1")
        PanelState.markEjected("弹出")

        val snap = DiscMemory.find("fp1")!!
        PanelState.restoreSnapshot(snap, snap.ripDone)

        assertEquals("GREATEST HITS", PanelState.disc?.album)
        assertTrue(PanelState.ripDone, "已抓完的盘放回来仍应是「已抓完」")
    }

    @Test
    fun differentDiscHasNoMemory() {
        PanelState.showDisc(info("abc", "GREATEST HITS"))
        DiscMemory.remember("fp1")
        assertNull(DiscMemory.find("fp2"), "换盘必须当新盘，不能把 A 盘曲名贴到 B 盘")
    }

    @Test
    fun forgetDropsEverything() {
        PanelState.showDisc(info("abc", "X"))
        DiscMemory.remember("fp1")
        DiscMemory.forget()
        assertNull(DiscMemory.find("fp1"))
    }

    @Test
    fun rememberWithoutDiscDoesNothing() {
        PanelState.showDisc(null)
        DiscMemory.remember("fp9")
        assertNull(DiscMemory.find("fp9"), "没盘可记时不该写空快照")
    }
}
