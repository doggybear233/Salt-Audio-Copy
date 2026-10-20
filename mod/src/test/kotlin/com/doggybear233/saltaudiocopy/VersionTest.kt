package com.doggybear233.saltaudiocopy

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 版本号漂移保护。
 *
 * 0.36.5 之前 `AlbumRipper.APP_VERSION` 一直写着 `0.7.0`：日志抬头、CUE 注释、
 * WAV 的 comment 都在骗人。这里把「构建脚本里的 version」和「代码里的 Version.VALUE」
 * 绑在一起，漏改一边就红。
 */
class VersionTest {

    @Test
    fun appVersionMatchesBuildScript() {
        // 测试工作目录是 mod/；找不到就跳过（例如从 IDE 用别的目录跑）
        val script = Path.of("build.gradle.kts")
        if (!Files.isRegularFile(script)) return
        val text = Files.readString(script)
        val declared = Regex("""^\s*version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1)
        assertEquals(declared, Version.VALUE, "mod/build.gradle.kts 与 Version.VALUE 不一致，改一处不足以发版")
    }

    @Test
    fun logHeaderUsesTheSharedVersion() {
        assertEquals(Version.VALUE, com.doggybear233.saltaudiocopy.album.AlbumRipper.APP_VERSION, "日志抬头必须用同一个版本号")
        assertTrue(Version.VALUE.matches(Regex("""\d+\.\d+\.\d+""")), "版本号应是 x.y.z：${Version.VALUE}")
    }
}
