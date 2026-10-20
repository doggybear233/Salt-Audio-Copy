package com.saltrip.rip

import com.saltrip.rip.meta.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 校验 `preference_config.json` 与宿主解析器（ModConfigParser）的契约，
 * 并确认每个 button 的 `on_click` 真能解析到 `ConfigActions` 的公开静态方法。
 *
 * 这一步能拦住"JSON 里写了按钮但类名/方法名拼错"这类只能在宿主 UI 里才发现的问题。
 */
class PreferenceConfigTest {

    private val allowedTypes = setOf("switch", "list", "button", "seekbar", "edittext")

    private fun load(): Any? {
        val text = javaClass.getResourceAsStream("/preference_config.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        assertNotNull(text, "资源 preference_config.json 必须打进 jar 根")
        return Json.parse(text)
    }

    @Test
    fun schemaMatchesHostParser() {
        val root = load()
        val configs = Json.arr(Json.path(root, "configs"))
        assertTrue(configs.size >= 3, "至少要有抓轨/输出/校验三组")

        for (g in configs) {
            val group = Json.obj(g)
            assertTrue(!Json.str(group["title"]).isNullOrBlank(), "分组必须有 title")
            assertEquals("config.json", Json.str(group["config"]), "分组统一写同一个配置文件")
            val prefs = Json.arr(group["preferences"])
            assertTrue(prefs.isNotEmpty(), "分组不能为空")

            for (p in prefs) {
                val pref = Json.obj(p)
                val type = Json.str(pref["type"])
                assertNotNull(type, "preference 必须有 type")
                assertTrue(type in allowedTypes, "未知 type=$type（宿主只认 $allowedTypes）")
                assertTrue(!Json.str(pref["title"]).isNullOrBlank(), "preference 必须有 title")

                when (type) {
                    "button" -> {
                        val onClick = Json.str(pref["on_click"])
                        assertNotNull(onClick, "button 缺少 on_click")
                        assertResolvable(onClick)
                    }
                    "switch" -> assertTrue(
                        pref["default_value"] is Boolean,
                        "switch 的 default_value 必须是布尔"
                    )
                    "edittext" -> assertTrue(
                        pref["default_value"] is String,
                        "edittext 的 default_value 必须是字符串"
                    )
                    "list" -> {
                        val entries = Json.arr(pref["entries"])
                        val values = Json.arr(pref["entry_values"])
                        assertTrue(entries.isNotEmpty(), "list 必须有 entries")
                        assertEquals(entries.size, values.size, "entries 与 entry_values 长度必须一致")
                        assertTrue(Json.str(pref["default_value"]) in values.map { Json.str(it) })
                    }
                    "seekbar" -> {
                        val min = Json.long(pref["min"]) ?: 0L
                        val max = Json.long(pref["max"]) ?: 100L
                        assertTrue(min < max, "seekbar 需要 min < max")
                    }
                }
                if (type != "button") {
                    assertTrue(!Json.str(pref["key"]).isNullOrBlank(), "$type 必须有 key")
                }
            }
        }
    }

    /** `com.saltrip.rip.ConfigActions.scanDrives` → 反射查公开静态无参方法。 */
    private fun assertResolvable(onClick: String) {
        val idx = onClick.lastIndexOf('.')
        assertTrue(idx > 0, "on_click 必须形如 包.类.方法：$onClick")
        val className = onClick.substring(0, idx)
        val methodName = onClick.substring(idx + 1)
        val cls = runCatching { Class.forName(className) }.getOrNull()
        assertNotNull(cls, "on_click 指向的类不存在：$className")
        val m = runCatching { cls.getMethod(methodName) }.getOrNull()
        assertNotNull(m, "类 $className 没有公开方法 $methodName")
        assertTrue(
            java.lang.reflect.Modifier.isStatic(m.modifiers),
            "$className.$methodName 必须是 static（宿主反射调用）"
        )
    }

    @Test
    fun settingsKeysAreKnown() {
        val known = setOf(
            "trackSelection", "overwritePolicy", "guardDisc", "driveLetter", "driveSpeed", "readCommand", "ejectAfterRip", "outputRoot", "folderTemplate", "fileTemplate", "coverFileName", "multiDiscFolder",
            "secureMode", "maxRetries", "retryDelayMs", "fetchMetadata", "autoLookupOnInsert", "coverSize", "proxyUrl",
            "writeCue", "writeLog", "writeAlbumJson", "reuseAlbumJson", "format", "offsetSamples", "annotateCueCrc", "useC2", "defeatCache", "testAndCopy",
            "detectGaps", "resume", "errorRecovery", "gapMode", "gapMethod",
            "flacExe", "encoderExe", "encoderArgs", "encoderExt", "flacLevel", "keepWav",
            "releaseIndex", "preferTrackCount", "overrideFile", "useCdText", "discNumberOverride", "totalDiscsOverride",
            "imageMode", "analyzeBeforeRip", "writeTags", "embedCover", "testMode", "writeWavTags"
        )
        val configs = Json.arr(Json.path(load(), "configs"))
        val keys = configs.flatMap { Json.arr(Json.obj(it)["preferences"]) }
            .mapNotNull { Json.str(Json.obj(it)["key"]) }
            .toSet()
        assertTrue(keys.isNotEmpty())
        val unknown = keys - known
        assertTrue(unknown.isEmpty(), "配置页出现了 SettingsStore 不认识的键: $unknown")
    }
}
