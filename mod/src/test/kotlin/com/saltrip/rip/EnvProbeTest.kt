package com.saltrip.rip

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 环境自检必须真的能说明问题：用户报「乱码」时，这一条决定我们往哪儿查。
 */
class EnvProbeTest {

    @Test
    fun reportCarriesTheFactsThatDecideEncoding() {
        val r = EnvProbe.collect()
        assertTrue(r.osName.isNotBlank(), "os.name")
        assertTrue(r.ansiCharset.isNotBlank(), "ANSI 字符集（WAV INFO 用）")
        assertTrue(r.jnuEncoding.isNotBlank(), "sun.jnu.encoding 决定路径能不能写中文")
        assertTrue(r.tempDir.isNotBlank(), "java.io.tmpdir")
    }

    @Test
    fun cjkPathRoundTripSucceedsOnThisMachine() {
        assertTrue(EnvProbe.cjkRoundTrip(), "临时目录里写中日韩文件名应当成功")
    }

    @Test
    fun summarySaysWhetherCjkPathsWork() {
        val r = EnvProbe.collect()
        val s = EnvProbe.summary(r)
        assertTrue(s.contains("环境自检"), s)
        assertTrue(s.contains("中日韩"), s)
        assertTrue(s.contains("可写") || s.contains("失败"), s)
    }

    @Test
    fun detailsIncludeCharsetAndEncodings() {
        val d = EnvProbe.details(EnvProbe.collect())
        assertTrue(d.contains("sun.jnu.encoding="), d)
        assertTrue(d.contains("file.encoding="), d)
        assertTrue(d.contains("cjk path round trip="), d)
    }
}
