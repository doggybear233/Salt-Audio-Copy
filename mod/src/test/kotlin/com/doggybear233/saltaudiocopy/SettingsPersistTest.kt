package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.settings.RipSettings
import com.doggybear233.saltaudiocopy.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 设置写回必须如实回报成败。
 *
 * 单测环境没有宿主 `WorkshopApi`，`SettingsStore.save` 必然拿不到 ConfigHelper——
 * 正好用来验证「写不进去时返回 false」，而不是假装成功（用户会以为重启后还在）。
 */
class SettingsPersistTest {

    @Test
    fun saveReportsFailureWhenHostConfigIsUnavailable() {
        assertFalse(SettingsStore.save(RipSettings()), "没有宿主配置时必须返回 false")
    }

    @Test
    fun saveStillUpdatesInMemoryCache() {
        val custom = RipSettings(secureMode = false, maxRetries = 3)
        SettingsStore.save(custom)
        val loaded = SettingsStore.load(force = true)
        assertTrue(!loaded.secureMode && loaded.maxRetries == 3, "写盘失败也要保证本次运行内生效：$loaded")
    }
}
