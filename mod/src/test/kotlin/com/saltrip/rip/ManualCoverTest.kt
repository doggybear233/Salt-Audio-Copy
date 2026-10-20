package com.saltrip.rip

import com.saltrip.rip.meta.MetaOverride
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
        dir = Files.createTempDirectory("saltrip-cover")
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
}
