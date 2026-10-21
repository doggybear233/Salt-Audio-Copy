package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.CddbCache
import com.doggybear233.saltaudiocopy.meta.GnudbClient
import com.doggybear233.saltaudiocopy.meta.Http
import com.doggybear233.saltaudiocopy.meta.RequestCancelled
import com.doggybear233.saltaudiocopy.meta.ResponseTooLarge
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R4-01 · F-002：网络取消与体积上限必须是「立刻、可区分」的。
 *
 * 上一轮的问题：`read` 阻塞时只看标志位，用户取消要等满 readTimeout；
 * 超大响应被当成普通异常重试，还会把镜像记进 deadMirrors。
 */
class BatchR401HttpTest {

    private lateinit var server: HttpServer
    private var port = 0
    private lateinit var cacheDir: Path

    private val toc = DiscToc(
        1, 3,
        listOf(
            TocEntry(1, true, 1, 0),
            TocEntry(2, true, 1, 15_000),
            TocEntry(3, true, 1, 30_000),
            TocEntry(0xAA, true, 1, 45_000)
        )
    )

    @BeforeTest
    fun setUp() {
        Http.startRequest()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        cacheDir = TempDirs.create("saltac-r401")
        CddbCache.dirOverride = cacheDir
        GnudbClient.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        Http.abort()
        CddbCache.dirOverride = null
        GnudbClient.resetForTest()
        runCatching { Files.walk(cacheDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    @Test
    fun oversizedBodyIsTypedAndNotRetried() {
        // 无 Content-Length（chunked）：只能边读边计数。超限必须是可区分的类型，且不重试。
        val chunk = ByteArray(64 * 1024) { 3 }
        server.createContext("/huge") { ex ->
            ex.sendResponseHeaders(200, 0)
            runCatching { repeat(32) { ex.responseBody.write(chunk) } }
            ex.close()
        }
        server.start()
        Http.resetAttemptCounter()
        assertFailsWith<ResponseTooLarge> {
            Http.get(url("/huge"), retries = 3, maxBytes = 128L * 1024)
        }
        assertEquals(1, Http.attemptCount(), "体积超限重试也不会变小，只应尝试一次")
    }

    @Test
    fun cancelBreaksABlockedBodyRead() {
        // 头已经发完、body 迟迟不来：read 会阻塞到 readTimeout（默认 20 秒）。
        // 取消必须断开连接，让调用方在几百毫秒内拿到 RequestCancelled。
        val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/hang") { ex ->
            ex.sendResponseHeaders(200, 100)
            runCatching {
                ex.responseBody.write(1)
                ex.responseBody.flush()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                ex.responseBody.write(ByteArray(99))
            }
            ex.close()
        }
        server.start()
        Http.startRequest()
        val started = System.currentTimeMillis()
        val t = Thread {
            Thread.sleep(250)
            Http.abort()
        }
        t.start()
        assertFailsWith<RequestCancelled> {
            Http.get(url("/hang"), retries = 0, readMs = 20_000)
        }
        val spent = System.currentTimeMillis() - started
        assertTrue(spent < 3_000, "取消应立刻断开阻塞读，实际 ${spent}ms")
        release.countDown()
    }

    @Test
    fun oversizedCddbResponseDoesNotBlacklistMirror() {
        // 单个 CDDB 条目超过 1 MB 是平台异常；镜像本身没挂，不能被记进 deadMirrors。
        server.createContext("/~cddb/cddb.cgi") { ex ->
            ex.sendResponseHeaders(200, 0)
            val chunk = ByteArray(64 * 1024) { 1 }
            runCatching { repeat(32) { ex.responseBody.write(chunk) } }
            ex.close()
        }
        server.start()
        GnudbClient.mirrorsOverride = listOf("http://127.0.0.1:$port/~cddb/cddb.cgi")
        assertFailsWith<java.io.IOException> { GnudbClient.query(toc) }
        assertEquals(0, GnudbClient.deadMirrorCount(), "体积超限不是「镜像连不上」")
    }
}
