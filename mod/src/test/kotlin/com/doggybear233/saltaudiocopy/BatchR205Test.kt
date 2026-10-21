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

    /** FLAC 模式的实际产物是 .flac：只看 .wav 会把上一版 FLAC 原地覆盖。 */
    @Test
    fun audioTargetAlsoConsidersAnExistingFlac() {
        val dir = Files.createTempDirectory("saltac-ovw")
        try {
            Files.write(dir.resolve("01 x.flac"), byteArrayOf(1, 2, 3))
            assertNull(Overwrite.SKIP.let { Overwrite.resolveAudio(dir, "01 x", it) })
            assertEquals(
                dir.resolve("01 x (2).wav"),
                Overwrite.resolveAudio(dir, "01 x", Overwrite.SUFFIX),
                "suffix 策略必须顺延到 .wav/.flac 都空着的名字"
            )
            assertEquals(
                dir.resolve("01 x.wav"),
                Overwrite.resolveAudio(dir, "01 x", Overwrite.OVERWRITE)
            )
        } finally {
            runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    /** 半截 WAV 仍然要能续写：不能因为「同时看 .flac」就把续抓挡掉。 */
    @Test
    fun audioTargetStillResumesAPartialWav() {
        val dir = Files.createTempDirectory("saltac-ovw")
        try {
            val wav = dir.resolve("01 x.wav")
            // 用真实字段拼一个合法 WAV 头（全零头会被 hasValidHeader 挡掉，测不到续抓）
            val data = 1000
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + data)
                .put("WAVE".toByteArray())
                .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
                .putInt(44_100).putInt(176_400).putShort(4).putShort(16)
                .put("data".toByteArray()).putInt(data)
            Files.write(wav, header.array() + ByteArray(data))

            assertEquals(
                wav,
                Overwrite.resolveAudio(dir, "01 x", Overwrite.SUFFIX, resumeExpectedBytes = 10_000),
                "半截文件应当续写同名文件，而不是另存 (2)"
            )
        } finally {
            runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
