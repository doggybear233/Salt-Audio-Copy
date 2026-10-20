package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.GnudbClient
import com.doggybear233.saltaudiocopy.meta.Http
import com.doggybear233.saltaudiocopy.meta.RequestCancelled
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 取消只作用于「这一次查询」。
 *
 * 真实事故（F-286）：`Http.abort()` 是进程级粘性开关，唯一的复位点在 `MetadataCandidates.load`。
 * 用户点一次取消抓轨之后，面板上「刷新元数据」「网络自检」全部立刻报「请求已取消」；
 * 更糟的是 CDDB 镜像会把取消当成「连不上」记进 `deadMirrors`，此后整个进程内 CDDB 永久不可用。
 */
class HttpScopeTest {

    private lateinit var server: HttpServer
    private var port = 0

    @BeforeTest
    fun setUp() {
        GnudbClient.resetForTest()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        server.createContext("/ok") { ex ->
            val body = "hello".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        GnudbClient.resetForTest()
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    @Test
    fun cancellingOneScopeDoesNotPoisonTheNext() {
        val first = Http.beginScope()
        Http.abort()
        assertTrue(first.cancelled, "abort 应该取消当前批次")
        assertFailsWith<RequestCancelled> {
            Http.get(url("/ok"), retries = 0, scope = first)
        }
        // 下一次查询用新批次：必须真的发出去并成功
        val second = Http.beginScope()
        val r = Http.get(url("/ok"), retries = 0, scope = second)
        assertEquals(200, r.status)
        assertEquals("hello", r.text)
    }

    @Test
    fun anOldScopeStaysCancelledForItsOwnRequest() {
        val old = Http.beginScope()
        Http.abort()
        Http.beginScope() // 新批次已经开始
        assertFailsWith<RequestCancelled> {
            // 旧请求（例如另一个还活着的线程）仍应看到自己的取消，而不是被复位
            Http.get(url("/ok"), retries = 0, scope = old)
        }
    }

    @Test
    fun cancelDoesNotCountAsADeadMirror() {
        GnudbClient.mirrorsOverride = listOf(url("/ok"))
        val before = GnudbClient.deadMirrorCount()
        val scope = Http.beginScope()
        Http.abort()
        assertFailsWith<RequestCancelled> {
            Http.get(url("/ok"), retries = 0, scope = scope)
        }
        assertEquals(
            before,
            GnudbClient.deadMirrorCount(),
            "取消不是「镜像挂了」：记进 deadMirrors 会让本次运行内 CDDB 永久不可用"
        )
    }

    @Test
    fun cancelledReadStopsInsteadOfDownloadingEverything() {
        server.createContext("/slow") { ex ->
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            try {
                repeat(200) {
                    out.write(ByteArray(1024))
                    out.flush()
                    Thread.sleep(5)
                }
            } catch (_: Throwable) {
                // 客户端断开是预期
            }
            runCatching { ex.close() }
        }
        val scope = Http.beginScope()
        val worker = Thread {
            runCatching { Http.get(url("/slow"), retries = 0, scope = scope) }
        }
        worker.isDaemon = true
        worker.start()
        Thread.sleep(120)
        Http.abort()
        worker.join(3_000)
        assertTrue(!worker.isAlive, "取消后读循环应立即退出，而不是把 200 KB 读完")
    }
}
