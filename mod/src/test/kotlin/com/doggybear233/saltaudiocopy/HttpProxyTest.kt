package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 代理配置的解析：设置里那串文本经常被写成各种样子，别让一个多余字符把网络彻底断掉。
 */
class HttpProxyTest {

    @Test
    fun blankMeansDirect() {
        assertNull(Http.parseProxy(""), "空串 = 直连")
        assertNull(Http.parseProxy("   "), "空白 = 直连")
    }

    @Test
    fun acceptsHostPort() {
        val p = Http.parseProxy("127.0.0.1:7890")
        assertEquals("HTTP", p!!.type().name)
        assertEquals(java.net.InetSocketAddress("127.0.0.1", 7890), p.address())
    }

    @Test
    fun acceptsSchemePrefix() {
        assertEquals(
            java.net.InetSocketAddress("127.0.0.1", 7890),
            Http.parseProxy("http://127.0.0.1:7890")!!.address()
        )
        assertEquals(
            java.net.InetSocketAddress("proxy.lan", 1080),
            Http.parseProxy("https://proxy.lan:1080")!!.address()
        )
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Http.parseProxy("127.0.0.1"), "没有端口")
        assertNull(Http.parseProxy("127.0.0.1:abc"), "端口不是数字")
        assertNull(Http.parseProxy("127.0.0.1:0"), "端口越界")
        assertNull(Http.parseProxy("127.0.0.1:70000"), "端口越界")
    }

    @Test
    fun configureTogglesState() {
        Http.configure("127.0.0.1:7890")
        assertTrue(Http.proxyInUse())
        Http.configure("")
        assertFalse(Http.proxyInUse())
    }
}
