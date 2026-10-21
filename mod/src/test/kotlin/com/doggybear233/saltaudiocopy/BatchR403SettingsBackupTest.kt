package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.settings.RipSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R4-03 · F-492：设置备份写了 46 个键，恢复只套 41 个，剩下的静默丢掉。
 *
 * 这里用「导出键 → 是否都能套用」的对照测试，新增设置项时漏掉恢复分支会直接失败。
 */
class BatchR403SettingsBackupTest {

    @Test
    fun `导出的每个键都必须能被恢复`() {
        val keys = RipSettings().dumpKeys()
        // 46 个设置页可见项 + appearance（外观，随设置一起持久化但不在 preference_config 里）
        assertEquals(47, keys.size, "设置键数量变了（实际 ${keys.size}：${keys.keys}）：同步 options-table 与备份测试")
        val unknown = keys.keys.filterNot { ConfigActions.isKnownSettingKey(it) }
        assertTrue(unknown.isEmpty(), "这些键写进备份却恢复不了：$unknown")
    }

    @Test
    fun `之前漏掉的五个键现在真的会写回设置`() {
        val base = RipSettings()
        val applied = ConfigActions.applySettingValues(
            base,
            mapOf(
                "useC2" to "true",
                "testAndCopy" to "true",
                "trackSelection" to "1-5",
                "overrideFile" to "D:/x/album.json",
                "appearance" to "dark"
            )
        )
        assertTrue(applied.useC2)
        assertTrue(applied.testAndCopy)
        assertEquals("1-5", applied.trackSelection)
        assertEquals("D:/x/album.json", applied.overrideFile)
        assertEquals("dark", applied.appearance)
    }
}
