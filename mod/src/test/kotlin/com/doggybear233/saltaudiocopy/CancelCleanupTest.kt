package com.doggybear233.saltaudiocopy

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 批次 17 · T-05：取消后「删除本次生成的文件」这条承诺。
 *
 * 生产里只有 [ConfigActions.deleteCreated] 一个入口；它漏记文件会留垃圾，
 * 多记会删掉用户既有文件。这里把两种情形都钉住。
 */
class CancelCleanupTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = TempDirs.create("saltac-cancel")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun deletesOnlyWhatWasCreated() {
        val created = dir.resolve("01.wav")
        val mine = dir.resolve("notes.txt")
        val kept = dir.resolve("existing.wav")
        Files.write(created, byteArrayOf(1))
        Files.write(mine, byteArrayOf(2))
        Files.write(kept, byteArrayOf(3))

        val n = ConfigActions.deleteCreated(listOf(created, mine))

        assertEquals(2, n)
        assertFalse(Files.exists(created))
        assertFalse(Files.exists(mine))
        assertTrue(Files.exists(kept), "用户既有文件不能被删")
        assertTrue(Files.isDirectory(dir), "目录要保留")
    }

    @Test
    fun missingFilesAreNotCounted() {
        val gone = dir.resolve("gone.wav")
        assertEquals(0, ConfigActions.deleteCreated(listOf(gone)), "本来就不存在的不算删除")
    }

    @Test
    fun failuresAreSwallowed() {
        // 目录用 Files.deleteIfExists 删不掉（非空），只能记日志、不能抛出
        val sub = dir.resolve("sub")
        Files.createDirectories(sub)
        Files.write(sub.resolve("x"), byteArrayOf(1))
        val n = ConfigActions.deleteCreated(listOf(sub))
        assertEquals(0, n)
        assertTrue(Files.exists(sub), "删不掉的目录应保持原样")
    }
}
