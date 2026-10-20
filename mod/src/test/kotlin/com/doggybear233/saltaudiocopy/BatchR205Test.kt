package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.OutputCheck
import com.doggybear233.saltaudiocopy.album.Overwrite
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R2-05：整盘一套产物（音频 + CUE + 日志 + 封面 + 镜像）必须遵守同一套覆盖策略，
 * 自检也必须认得 FLAC 的 CUE。
 */
class BatchR205Test {

    private fun tempDir(name: String): Path {
        val d = Files.createTempDirectory("b205-$name")
        return d
    }

    @Test
    fun suffixPolicyPicksTheNextFreeSuffix() {
        val dir = tempDir("suffix")
        Files.writeString(dir.resolve("A.cue"), "")
        assertEquals(" (2)", Overwrite.pickSuffix(dir, "A", listOf(".cue", ".log"), Overwrite.SUFFIX))
        Files.writeString(dir.resolve("A (2).cue"), "")
        assertEquals(" (3)", Overwrite.pickSuffix(dir, "A", listOf(".cue", ".log"), Overwrite.SUFFIX))
    }

    @Test
    fun suffixPolicyIgnoresUnrelatedExtensions() {
        val dir = tempDir("unrelated")
        // 只有 .log 占位时，只看 .cue 的调用不该被迫顺延
        Files.writeString(dir.resolve("A.log"), "")
        assertEquals("", Overwrite.pickSuffix(dir, "A", listOf(".cue"), Overwrite.SUFFIX))
    }

    @Test
    fun skipPolicyReturnsNullWhenAnyArtifactExists() {
        val dir = tempDir("skip")
        assertEquals("", Overwrite.pickSuffix(dir, "A", listOf(".cue", ".log"), Overwrite.SKIP))
        Files.writeString(dir.resolve("A.log"), "")
        assertNull(Overwrite.pickSuffix(dir, "A", listOf(".cue", ".log"), Overwrite.SKIP))
    }

    @Test
    fun overwritePolicyAlwaysKeepsThePlainName() {
        val dir = tempDir("overwrite")
        Files.writeString(dir.resolve("A.cue"), "")
        assertEquals("", Overwrite.pickSuffix(dir, "A", listOf(".cue"), Overwrite.OVERWRITE))
    }

    @Test
    fun flacCueIsAcceptedByOutputCheck() {
        val dir = tempDir("flac-cue")
        val flac = dir.resolve("01 x.flac")
        Files.write(flac, ByteArray(4096))
        Files.writeString(
            dir.resolve("Album.cue"),
            """
            REM GENRE "Test"
            FILE "01 x.flac" FLAC
              TRACK 01 AUDIO
                INDEX 01 00:00:00
            """.trimIndent()
        )
        val r = OutputCheck.check(dir)
        assertEquals(0, r.wavCount)
        assertEquals(1, r.flacCount, "FLAC 文件应被统计")
        assertEquals(1, r.cueCount)
        assertTrue(r.problems.none { it.detail.contains("没有任何 FILE 行") }, "FLAC 的 CUE 不该被判成没有 FILE 行")
        assertTrue(r.ok, "正常产物不该报问题：${r.problems}")
    }

    @Test
    fun cuePointingAtMissingFileIsStillReported() {
        val dir = tempDir("missing")
        Files.writeString(dir.resolve("Album.cue"), """FILE "gone.flac" FLAC""")
        val r = OutputCheck.check(dir)
        assertTrue(r.problems.any { it.detail.contains("引用的文件不存在") })
    }
}
