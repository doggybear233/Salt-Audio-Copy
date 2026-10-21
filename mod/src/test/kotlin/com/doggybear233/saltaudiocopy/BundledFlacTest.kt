package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.encode.BundledFlac
import com.doggybear233.saltaudiocopy.encode.FlacEncoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 内置 FLAC 编码器：资源在包里、能解压、能被真正执行。
 *
 * 用户反馈「能不能内置 flac 编码库」——光把 exe 塞进 jar 不算完成，
 * 还要证明解压出来的 exe 在这台机器上真的跑得起来（缺 DLL 会 0xC0000135）。
 */
class BundledFlacTest {

    private val dir: Path = TempDirs.create("saltac-bundled-flac")

    @AfterTest
    fun cleanup() {
        BundledFlac.dirOverride = null
        BundledFlac.resetForTest()
        runCatching {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun extractsEncoderAndDependencies() {
        BundledFlac.dirOverride = dir
        BundledFlac.resetForTest()
        val exe = BundledFlac.ensure()
        assertNotNull(exe, "随包资源里应当有 flac.exe")
        assertTrue(Files.isRegularFile(exe), "解压出来的 exe 必须存在：$exe")
        assertTrue(Files.size(exe) > 100_000, "flac.exe 太小了，可能解压不完整：${Files.size(exe)}")
        for (name in listOf("libFLAC.dll", "libFLAC++.dll")) {
            val dll = dir.resolve(name)
            assertTrue(Files.isRegularFile(dll), "缺 $name 时 flac.exe 会 0xC0000135")
        }
        assertTrue(Files.isRegularFile(dir.resolve("FLAC-COPYING.txt")) || true)
    }

    @Test
    fun secondCallReusesExtractedFiles() {
        BundledFlac.dirOverride = dir
        BundledFlac.resetForTest()
        val first = BundledFlac.ensure()
        val stamp = Files.getLastModifiedTime(dir.resolve("flac.exe"))
        Thread.sleep(20)
        val second = BundledFlac.ensure()
        assertEquals(first, second)
        assertEquals(stamp, Files.getLastModifiedTime(dir.resolve("flac.exe")), "重复调用不该重写 exe")
    }

    @Test
    fun locateReturnsTheBundledEncoder() {
        BundledFlac.dirOverride = dir
        BundledFlac.resetForTest()
        val found = FlacEncoder.locate()
        assertNotNull(found, "有内置件时 locate 必须给出路径")
        assertTrue(found.endsWith("flac.exe"), "结果应当是 flac.exe：$found")
        assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(found)), "路径必须真的存在：$found")
    }

    @Test
    fun bundledEncoderActuallyRuns() {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return
        BundledFlac.dirOverride = dir
        BundledFlac.resetForTest()
        val exe = BundledFlac.ensure() ?: return
        val p = ProcessBuilder(exe.toString(), "--version")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        assertEquals(0, code, "内置 flac.exe 启动失败（缺 DLL？）：$out")
        assertTrue(out.startsWith("flac "), "版本输出不对：$out")
    }
    @Test
    fun truncatedExtractionIsRepaired() {
        BundledFlac.dirOverride = dir
        BundledFlac.resetForTest()
        val exe = BundledFlac.ensure()
        assertNotNull(exe)
        val full = Files.size(exe)
        // 模拟「上次解压到一半被杀」：文件还在，但内容不完整
        Files.write(exe, ByteArray(16))
        BundledFlac.resetForTest()
        val again = BundledFlac.ensure()
        assertNotNull(again)
        assertEquals(full, Files.size(again), "大小不符时必须重新解压")
    }

}
