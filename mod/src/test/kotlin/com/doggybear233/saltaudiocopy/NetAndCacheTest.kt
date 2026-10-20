package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.CddbCache
import com.doggybear233.saltaudiocopy.meta.NetCheck
import com.doggybear233.saltaudiocopy.meta.TrackMeta
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.34.0 新增的两块「不用联网也能验证」的逻辑：网络自检的结论文案、CDDB 结果缓存。
 */
class NetAndCacheTest {

    private lateinit var tmp: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("saltac-cache")
        CddbCache.dirOverride = tmp
    }

    @AfterTest
    fun tearDown() {
        CddbCache.dirOverride = null
        runCatching {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun item(name: String, ok: Boolean, ms: Long = 100) =
        NetCheck.Item(name, "https://$name.test/", ok, ms, if (ok) "" else "连接超时")

    private fun toc(): DiscToc = DiscToc(
        firstTrack = 1,
        lastTrack = 2,
        entries = listOf(
            TocEntry(1, isAudio = true, adr = 1, lba = 0),
            TocEntry(2, isAudio = true, adr = 1, lba = 15_000),
            TocEntry(0xAA, isAudio = true, adr = 1, lba = 30_000)
        )
    )

    private fun album(): AlbumMeta = AlbumMeta(
        albumArtist = "Def Tech",
        albumTitle = "GREATEST HITS",
        discId = "jCz7JPOv0e14IuzcyS.StppLPio-",
        cddbId = "df0d0006",
        source = "gnudb",
        tracks = listOf(
            TrackMeta(1, "Lovin' You"),
            TrackMeta(2, "My Way")
        )
    )

    @Test
    fun summaryAllReachable() {
        val s = NetCheck.summary(listOf(item("mb", true, 120), item("cddb", true, 900)))
        assertEquals("网络自检：全部可达（最慢 900ms）", s)
    }

    @Test
    fun summaryPartialFailureNamesTheBadOnes() {
        val s = NetCheck.summary(listOf(item("mb", true), item("gnudb", false), item("dbpoweramp", false)))
        assertTrue(s.contains("1/3"), s)
        assertTrue(s.contains("gnudb"), s)
        assertTrue(s.contains("dbpoweramp"), s)
    }

    @Test
    fun summaryAllDownSuggestsProxy() {
        val s = NetCheck.summary(listOf(item("mb", false)))
        assertTrue(s.contains("全部不通"), s)
        assertTrue(s.contains("代理"), s)
    }

    @Test
    fun emptySummaryIsExplicit() {
        assertEquals("网络自检：没有可检测的目标", NetCheck.summary(emptyList()))
    }

    @Test
    fun briefTranslatesCommonFailures() {
        assertEquals("域名解析失败", NetCheck.brief(java.net.UnknownHostException("nope")))
        assertEquals("连接超时", NetCheck.brief(java.net.SocketTimeoutException("connect timed out")))
        assertEquals("连接超时", NetCheck.brief(java.io.IOException("Connect timed out")))
        assertTrue(NetCheck.brief(java.io.IOException("boom")).contains("boom"))
    }

    @Test
    fun cddbCacheRoundTrip() {
        CddbCache.write("df0d0006", album(), toc())
        assertNotNull(CddbCache.file("df0d0006"), "缓存文件应存在")

        val back = CddbCache.read("df0d0006")
        assertNotNull(back, "应能读回")
        assertEquals("Def Tech", back.albumArtist)
        assertEquals("GREATEST HITS", back.albumTitle)
        assertEquals(2, back.tracks.size)
        assertEquals("Lovin' You", back.tracks[0].title)
        assertEquals("gnudb", back.source, "读回的来源应标记为 gnudb")
    }

    @Test
    fun cddbCacheMissReturnsNull() {
        assertNull(CddbCache.read("nosuchid"))
    }

    @Test
    fun cddbCacheIgnoresBlankId() {
        assertNull(CddbCache.file(""))
        assertNull(CddbCache.read(""))
    }

    @Test
    fun cddbIdsAreSanitized() {
        assertEquals("a_b", CddbCache.safeId("a/b"))
        assertEquals("10-3", CddbCache.safeId("10-3"))
    }
}
