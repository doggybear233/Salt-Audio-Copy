package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.Json
import com.doggybear233.saltaudiocopy.settings.RipSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 第 3 轮 MINOR：默认值与翻译的「单一来源」校验。
 *
 * 这两项以前只能靠人眼对照三份副本（数据类 / JSON / 翻译表），
 * 漂移时没有任何测试会红——用户看到的默认值与代码里的实际默认值就不一样了。
 */
class BatchR315Test {

    private val root: Path by lazy {
        var p = Path.of("").toAbsolutePath()
        while (p.parent != null && !Files.exists(p.resolve("mod/build.gradle.kts"))) p = p.parent
        p
    }

    private fun configText(): String =
        Files.readString(root.resolve("mod/src/main/resources/preference_config.json"))

    /** F-396：翻译表里不该有「代码里根本不存在」的键。 */
    @Test
    fun everyTranslatedMessageIsStillUsed() {
        val table = Json.obj(Json.parse(Files.readString(
            root.resolve("mod/src/main/resources/messages-i18n.json")
        )))
        val entries = Json.obj(table["entries"])
        assertTrue(entries.isNotEmpty())

        val haystack = StringBuilder(configText())
        Files.walk(root.resolve("mod/src/main/kotlin")).use { s ->
            s.filter { it.toString().endsWith(".kt") }.forEach { haystack.append(Files.readString(it)) }
        }
        val text = haystack.toString()
        val dead = entries.keys.filter { it !in text }
        assertTrue(dead.isEmpty(), "这些翻译键已经没人用了，应删除：$dead")
    }

    /**
     * F-393：`RipSettings` 的默认值与 `preference_config.json` 的默认值必须一致。
     *
     * 宿主配置页是另一份声明（用户可能先在那里改过），两边不一致时，
     * 面板显示的值和实际生效的值会不一样。
     */
    @Test
    fun jsonDefaultsMatchTheDataClassDefaults() {
        val defaults = RipSettings()
        val cfg = Json.obj(Json.parse(configText()))
        val jsonDefaults = LinkedHashMap<String, String>()
        for (g in Json.arr(cfg["configs"])) {
            for (item in Json.arr(Json.obj(g)["preferences"])) {
                val o = Json.obj(item)
                val key = Json.str(o["key"]) ?: continue
                val raw = o["default_value"] ?: o["defaultValue"] ?: continue
                jsonDefaults[key] = Json.text(raw) ?: continue
            }
        }
        assertTrue(jsonDefaults.isNotEmpty())

        val fields = RipSettings::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val mismatched = ArrayList<String>()
        for (f in fields) {
            val jsonValue = jsonDefaults[f.name] ?: continue
            f.isAccessible = true
            val actual = f.get(defaults)?.toString() ?: continue
            val normalized = when (f.get(defaults)) {
                is Boolean -> jsonValue.lowercase()
                else -> jsonValue
            }
            if (normalized != actual) mismatched += "${f.name}: json=$jsonValue 代码=$actual"
        }
        assertTrue(mismatched.isEmpty(), "默认值有三份副本时必须一致：$mismatched")
    }

    /** F-392：续抓开关打开、已有半截文件时，预览要写「续抓」而不是「覆盖」。 */
    @Test
    fun previewSaysResumeWhenAPartialFileExists() {
        val dir = TempDirs.create("saltac-r315-")
        val wav = dir.resolve("01 Track 01.wav")
        // 44 字节头 + 100 扇区数据（故意短于整轨）
        val header = ByteArray(44)
        fun tag(off: Int, s: String) {
            s.forEachIndexed { i, c -> header[off + i] = c.code.toByte() }
        }
        tag(0, "RIFF"); tag(8, "WAVE"); tag(12, "fmt ")
        header[16] = 16.toByte(); header[20] = 1.toByte(); header[22] = 2.toByte()
        header[24] = 0x44.toByte(); header[25] = 0xAC.toByte()
        header[32] = 4.toByte(); header[34] = 16.toByte()
        tag(36, "data")
        val dataBytes = 100 * 2352
        header[40] = (dataBytes and 0xFF).toByte()
        header[41] = ((dataBytes shr 8) and 0xFF).toByte()
        header[42] = ((dataBytes shr 16) and 0xFF).toByte()
        header[43] = ((dataBytes shr 24) and 0xFF).toByte()
        Files.write(wav, header + ByteArray(dataBytes))

        val s = RipSettings(
            outputRoot = dir.toString(),
            folderTemplate = "",
            fileTemplate = "%tracknr2% %title%",
            resume = true,
            overwritePolicy = "suffix"
        )
        val toc = com.doggybear233.saltaudiocopy.scsi.DiscToc(
            1, 1,
            listOf(
                com.doggybear233.saltaudiocopy.scsi.TocEntry(1, true, 1, 0),
                com.doggybear233.saltaudiocopy.scsi.TocEntry(0xAA, true, 1, 1000)
            )
        )
        val plan = com.doggybear233.saltaudiocopy.album.RipPlan.build(toc, s, null)
        assertEquals("续抓（接着已有的 100 扇区写）", plan.tracks.single().action)
    }
}
