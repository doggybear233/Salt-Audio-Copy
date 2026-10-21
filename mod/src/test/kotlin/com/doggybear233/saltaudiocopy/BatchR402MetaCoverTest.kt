package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.MetaOverride
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R4-02 · F-481：选候选时只写 album.json 不写封面，下次抓轨就变回无封面。
 *
 * 封面是 JSON 的兄弟文件（`<stem>.cover.<ext>`），换格式时必须把旧后缀删掉，
 * 否则 coverSibling 按固定顺序找到的还是旧图。
 */
class BatchR402MetaCoverTest {

    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    @Test
    fun `封面写到 JSON 旁边并能读回`() {
        val dir = Files.createTempDirectory("saltac-cover")
        val json = dir.resolve("album-abc.json")
        Files.writeString(json, "{}")

        MetaOverride.writeCoverSibling(json, jpg, "image/jpeg")

        val sibling = assertNotNull(MetaOverride.coverSibling(json))
        assertTrue(Files.isRegularFile(sibling))
        assertContentEquals(jpg, Files.readAllBytes(sibling))
    }

    @Test
    fun `换封面格式会清掉旧后缀，不会被旧图盖过`() {
        val dir = Files.createTempDirectory("saltac-cover")
        val json = dir.resolve("album-abc.json")
        Files.writeString(json, "{}")
        MetaOverride.writeCoverSibling(json, jpg, "image/jpeg")
        MetaOverride.writeCoverSibling(json, png, "image/png")

        assertTrue(!Files.exists(dir.resolve("album-abc.cover.jpg")))
        val sibling = assertNotNull(MetaOverride.coverSibling(json))
        assertContentEquals(png, Files.readAllBytes(sibling))
    }

    @Test
    fun `没有封面数据时什么都不写`() {
        val dir: Path = Files.createTempDirectory("saltac-cover")
        val json = dir.resolve("album-abc.json")
        Files.writeString(json, "{}")
        MetaOverride.writeCoverSibling(json, null, null)
        assertTrue(MetaOverride.coverSibling(json) == null)
    }
}
