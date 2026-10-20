package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.MbCache
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MB 缓存：命中、过期、坏 DiscID、清理。全部离线。 */
class MbCacheTest {

    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltac-mbcache")
        MbCache.dirOverride = dir
    }

    @AfterTest
    fun tearDown() {
        MbCache.dirOverride = null
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun writesAndReadsBack() {
        MbCache.write("abc_DEF-1234", "{\"ok\":true}")
        assertEquals("{\"ok\":true}", MbCache.read("abc_DEF-1234"))
    }

    @Test
    fun pathUnsafeCharsAreSanitised() {
        MbCache.write("a/b:c*d", "x")
        val files = Files.list(dir).use { it.toList() }
        assertEquals(1, files.size)
        // 文件名里不能出现分隔符，否则会写到别的目录去
        assertTrue(files[0].fileName.toString().startsWith("mb-"))
        assertTrue(files[0].fileName.toString().none { it == '/' || it == ':' || it == '*' })
    }

    @Test
    fun expiredEntryIsIgnored() {
        MbCache.write("old", "{\"stale\":1}")
        val f = Files.list(dir).use { it.toList() }.single()
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - 8L * 24 * 3600 * 1000))
        assertNull(MbCache.read("old"))
    }

    @Test
    fun ttlIsHonoured() {
        MbCache.write("fresh", "x")
        // 显式零 TTL 等于「立刻过期」，用来验证 TTL 参数真的被用上
        assertNull(MbCache.read("fresh", ttlMs = -1))
        assertEquals("x", MbCache.read("fresh", ttlMs = 60_000))
    }

    @Test
    fun blankDiscIdIsNotCached() {
        MbCache.write("", "x")
        assertNull(MbCache.read(""))
        assertEquals(0, Files.list(dir).use { it.toList() }.size)
    }

    @Test
    fun clearRemovesOnlyMbEntries() {
        MbCache.write("a", "1")
        Files.writeString(dir.resolve("keep.txt"), "keep")
        val removed = MbCache.clear()
        assertEquals(1, removed)
        assertTrue(Files.isRegularFile(dir.resolve("keep.txt")))
    }
}
