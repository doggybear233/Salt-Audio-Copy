package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.CddbCache
import com.doggybear233.saltaudiocopy.meta.GnudbClient
import com.doggybear233.saltaudiocopy.meta.Http
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 批次 18 · T-07：CDDB 路径的离线覆盖。
 *
 * 以前 GnudbClient 的所有行为只在真网络 + 真盘上试过（`SALTAC_HW` 门控），CI 里等于没测。
 * 这里用 JDK 自带的 HttpServer 起一个桩，把镜像回退、hello 重试、query/read 解析、
 * 「全部连不上」的错误文案全部跑一遍。
 */
class GnudbStubTest {

    private lateinit var server: HttpServer
    private lateinit var cacheDir: Path
    private val requests = ArrayList<String>()

    /** 3 轨的小盘，足够算 CDDB id。 */
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
        cacheDir = Files.createTempDirectory("saltac-cddb")
        CddbCache.dirOverride = cacheDir
        GnudbClient.resetForTest()
        Http.configure("")

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/~cddb/cddb.cgi") { ex -> handle(ex) }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        CddbCache.dirOverride = null
        GnudbClient.resetForTest()
        runCatching { Files.walk(cacheDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun base(): String = "http://127.0.0.1:${server.address.port}/~cddb/cddb.cgi"

    private fun handle(ex: HttpExchange) {
        val q = ex.requestURI.rawQuery ?: ""
        requests += q
        val body = when {
            q.contains("cmd=cddb+query") || q.contains("cmd=cddb%20query") ->
                "200 Found 1 album\r\n" +
                    "misc ${com.doggybear233.saltaudiocopy.meta.DiscId.cddb(toc)} Test Artist / Test Album\r\n" +
                    ".\r\n"

            q.contains("cmd=cddb+read") || q.contains("cmd=cddb%20read") ->
                "210 misc ${com.doggybear233.saltaudiocopy.meta.DiscId.cddb(toc)} OK\r\n" +
                    "# xmcd\r\n#\r\nDTITLE=Test Artist / Test Album\r\n" +
                    "DYEAR=1999\r\nDGENRE=Rock\r\n" +
                    "TTITLE0=First\r\nTTITLE1=Second\r\n" +
                    ".\r\n"

            else -> "500 broken\r\n"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun queryAndReadWorkAgainstAStub() {
        GnudbClient.mirrorsOverride = listOf(base())
        val entries = GnudbClient.query(toc)
        println("DBG requests=$requests")
        assertEquals(1, entries.size, "requests=$requests")
        assertEquals("misc", entries[0].category)

        val album = assertNotNull(GnudbClient.read(entries[0]))
        assertEquals("Test Artist", album.albumArtist)
        assertEquals("Test Album", album.albumTitle)
        assertEquals(listOf("First", "Second"), album.tracks.map { it.title })
        assertEquals("1999", album.year)
        // 缓存由 MetadataCandidates 在选中候选后写；这里直接写入，验证「读缓存不必联网」
        CddbCache.write(entries[0].discId, album, toc)
        assertTrue(Files.list(cacheDir).use { it.count() } > 0, "缓存文件应写出来")
    }

    @Test
    fun deadMirrorFallsThroughToTheNextOne() {
        // 第一个镜像拒绝连接（端口 1 必然失败），第二个是桩
        GnudbClient.mirrorsOverride = listOf("http://127.0.0.1:1/~cddb/cddb.cgi", base())
        val entries = GnudbClient.query(toc)
        assertEquals(1, entries.size, "第一个镜像不通时应自动换下一个")
        assertEquals(base(), GnudbClient.lastMirror)
    }

    @Test
    fun allMirrorsDownExplainsItself() {
        GnudbClient.mirrorsOverride = listOf("http://127.0.0.1:1/~cddb/cddb.cgi")
        val e = assertFailsWith<java.io.IOException> { GnudbClient.query(toc) }
        assertTrue(e.message!!.contains("CDDB 服务全部连不上"), "错误消息要能直接给用户看：${e.message}")
    }

    @Test
    fun cachedEntryIsUsedWithoutHittingTheNetwork() {
        GnudbClient.mirrorsOverride = listOf(base())
        val entries = GnudbClient.query(toc)
        val fetched = assertNotNull(GnudbClient.read(entries[0]))
        CddbCache.write(entries[0].discId, fetched, toc)
        val before = requests.size
        // 换成一个必然失败的镜像：如果还联网就会抛，命中缓存则正常返回
        GnudbClient.mirrorsOverride = listOf("http://127.0.0.1:1/~cddb/cddb.cgi")
        val again = assertNotNull(GnudbClient.read(entries[0]))
        assertEquals("Test Album", again.albumTitle)
        assertEquals(before, requests.size, "命中缓存就不该再发请求")
    }
}
