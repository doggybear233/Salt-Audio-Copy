package com.saltrip.rip

import com.saltrip.rip.settings.RipSettings
import com.saltrip.rip.ui.SettingsAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 插盘 / 换盘自动查元数据。
 *
 * 查询是异步网络请求，慢的时候用户已经换了下一张盘；这条用例守的是「过期结果不许覆盖面板」，
 * 以及开关默认值（用户不想联网时必须能关掉）。
 */
class AutoLookupTest {

    @Test
    fun onByDefault() {
        assertTrue(RipSettings().autoLookupOnInsert, "默认插盘就查，用户不该再点一次按钮")
    }

    @Test
    fun sameDiscResultApplies() {
        assertTrue(ConfigActions.autoResultIsFresh("toc-aaa", "toc-aaa"))
    }

    @Test
    fun swappedDiscResultIsDropped() {
        assertFalse(
            ConfigActions.autoResultIsFresh("toc-aaa", "toc-bbb"),
            "A 盘的专辑名不能写到 B 盘的面板上"
        )
    }

    @Test
    fun manualLookupAlwaysApplies() {
        assertTrue(ConfigActions.autoResultIsFresh(null, "toc-bbb"), "手点按钮不传基准，一律接受")
    }

    @Test
    fun toggleRoundTripsThroughSettingsAccess() {
        val off = SettingsAccess.withBool(RipSettings(), "autoLookupOnInsert", false)
        assertFalse(SettingsAccess.bool(off, "autoLookupOnInsert"))
        assertEquals(false, off.autoLookupOnInsert)
    }
}
