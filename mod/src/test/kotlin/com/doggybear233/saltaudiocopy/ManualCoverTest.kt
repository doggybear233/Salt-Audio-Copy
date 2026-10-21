package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.MetaOverride
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 手填元数据旁的封面：`<discId>.album.cover.jpg` 会被自动带进 AlbumMeta。 */
class ManualCoverTest {

    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = TempDirs.create("saltac-cover")
    }

    @AfterTest
    fun tearDown() {
        runCatching {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun writeJson(name: String): java.nio.file.Path {
        val f = dir.resolve(name)
        Files.writeString(
            f,
            """{"albumArtist":"十明","albumTitle":"変身のレシピ","tracks":[{"number":1,"title":"灰かぶり"}]}""",
            Charsets.UTF_8
        )
        return f
    }

    @Test
    fun loadAttachesSiblingCover() {
        val json = writeJson("abc.album.json")
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        Files.write(dir.resolve("abc.album.cover.jpg"), bytes)

        val meta = assertNotNull(MetaOverride.load(json))
        assertEquals("変身のレシピ", meta.albumTitle)
        assertEquals("image/jpeg", meta.coverMime)
        assertNotNull(meta.cover)
        assertEquals(5, meta.cover!!.size)
    }

    @Test
    fun pngCoverGetsPngMime() {
        val json = writeJson("def.album.json")
        Files.write(dir.resolve("def.album.cover.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        val meta = assertNotNull(MetaOverride.load(json))
        assertEquals("image/png", meta.coverMime)
    }

    @Test
    fun missingCoverLeavesCoverNull() {
        val json = writeJson("ghi.album.json")
        val meta = assertNotNull(MetaOverride.load(json))
        assertNull(meta.cover)
    }

    @Test
    fun coverSiblingPrefersJpg() {
        val json = writeJson("jkl.album.json")
        Files.write(dir.resolve("jkl.album.cover.jpg"), byteArrayOf(1))
        Files.write(dir.resolve("jkl.album.cover.png"), byteArrayOf(2))
        val found = assertNotNull(MetaOverride.coverSibling(json))
        assertTrue(found.fileName.toString().endsWith(".cover.jpg"), "found=$found")
    }

    /** 用户选 bmp / gif 时也要认得，别把图片存下来却读不回去。 */
    @Test
    fun bmpAndGifCoversAreRecognised() {
        val bmpJson = writeJson("bmp.album.json")
        Files.write(dir.resolve("bmp.album.cover.bmp"), byteArrayOf(0x42, 0x4D, 1, 2))
        val bmp = assertNotNull(MetaOverride.load(bmpJson))
        assertEquals("image/bmp", bmp.coverMime)

        val gifJson = writeJson("gif.album.json")
        Files.write(dir.resolve("gif.album.cover.gif"), byteArrayOf(0x47, 0x49, 0x46, 1))
        val gif = assertNotNull(MetaOverride.load(gifJson))
        assertEquals("image/gif", gif.coverMime)
    }

    /**
     * 回归：文件选择的过滤器必须**每个扩展名一个参数**。
     * 早先传 `"jpg;jpeg;png;webp"` 一整串，FileNameExtensionFilter 当成一个扩展名，
     * 于是列表里一张图都看不见（只有切「所有文件」才看得到）。
     */
    @Test
    fun fileFilterNeedsSeparateExtensions() {
        val good = javax.swing.filechooser.FileNameExtensionFilter(
            "图片 (jpg / png)", "jpg", "jpeg", "png", "webp"
        )
        assertTrue(good.accept(java.io.File("cover.jpg")), "jpg 必须可见")
        assertTrue(good.accept(java.io.File("cover.JPG")), "大小写都要认")
        assertTrue(good.accept(java.io.File("cover.png")))
        assertTrue(!good.accept(java.io.File("cover.txt")))

        val broken = javax.swing.filechooser.FileNameExtensionFilter("图片", "jpg;jpeg;png;webp")
        assertTrue(!broken.accept(java.io.File("cover.jpg")), "这正是旧 bug 的形态：一张都不显示")
    }
}
