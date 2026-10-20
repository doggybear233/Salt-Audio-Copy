package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.Http
import com.doggybear233.saltaudiocopy.meta.writeTextAtomically
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 网络层的「不堵死、不撑爆」约束：退避可取消、Retry-After 有上限、响应体有上限。
 * 全部用本机 HttpServer 打桩，不联网。
 */
class HttpLimitsTest {

    private lateinit var server: HttpServer
    private var port = 0

    @BeforeTest
    fun setUp() {
        // 取消标志是进程级状态：上一个用例 abort 过，这里要复位才会真的发请求
        Http.startRequest()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        Http.abort()
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    @Test
    fun retryAfterIsCapped() {
        // 服务器说「300 秒后再来」，插件不能真睡 300 秒。
        server.createContext("/throttle") { ex ->
            ex.responseHeaders.add("Retry-After", "300")
            ex.sendResponseHeaders(429, -1)
            ex.close()
        }
        server.start()
        assertTrue(Http.MAX_RETRY_WAIT_MS <= 20_000, "上限不该超过 20 秒")
        val started = System.currentTimeMillis()
        val r = Http.get(url("/throttle"), retries = 0)
        val spent = System.currentTimeMillis() - started
        assertEquals(429, r.status)
        assertTrue(spent < 5_000, "429 且 retries=0 时不该等待，实际等了 ${spent}ms")
    }

    @Test
    fun abortBreaksTheBackoffInsteadOfWaiting() {
        // 503 会进入退避；另一个线程 abort，请求要立刻失败而不是睡完 600ms×3。
        server.createContext("/busy") { ex ->
            ex.sendResponseHeaders(503, -1)
            ex.close()
        }
        server.start()
        Http.startRequest()
        val started = System.currentTimeMillis()
        val t = Thread {
            Thread.sleep(150)
            Http.abort()
        }
        t.start()
        assertFailsWith<java.io.IOException> {
            Http.get(url("/busy"), retries = 5)
        }
        val spent = System.currentTimeMillis() - started
        assertTrue(spent < 3_000, "取消后应立即中止，实际 ${spent}ms")
    }

    @Test
    fun oversizedBodyIsRejectedBeforeItIsFullyRead() {
        val chunk = ByteArray(64 * 1024) { 7 }
        server.createContext("/big") { ex ->
            ex.sendResponseHeaders(200, chunk.size.toLong() * 64) // 4 MiB
            try {
                repeat(64) { ex.responseBody.write(chunk) }
            } catch (_: Throwable) {
                // 客户端提前断开是预期行为
            }
            ex.close()
        }
        server.start()
        val err = assertFailsWith<java.io.IOException> {
            Http.getBytes(url("/big"), maxBytes = 256 * 1024)
        }
        assertTrue(err.message.orEmpty().contains("过大"), "错误要说清是体积超限：${err.message}")
    }

    @Test
    fun atomicWriteLeavesNoTempFile() {
        val dir = Files.createTempDirectory("saltac-atomic")
        try {
            val target = dir.resolve("cache.json")
            writeTextAtomically(target, "{\"a\":1}")
            assertEquals("{\"a\":1}", Files.readString(target))
            assertTrue(Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
