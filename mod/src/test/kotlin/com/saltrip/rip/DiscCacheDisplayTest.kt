package com.saltrip.rip

import com.saltrip.rip.album.AlbumJsonWriter
import com.saltrip.rip.album.DiscCache
import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.meta.DiscId
import com.saltrip.rip.meta.TrackMeta
import com.saltrip.rip.rip.ScanResult
import com.saltrip.rip.scsi.DiscToc
import com.saltrip.rip.scsi.InquiryData
import com.saltrip.rip.scsi.TocEntry
import com.saltrip.rip.ui.ManualDraft
import com.saltrip.rip.ui.PanelState
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「换盘监视每 2 秒重建 DiscInfo」不能把用户手填的元数据抹掉。
 *
 * 用户实测：保存成功（日志有 `manual meta persisted`），两秒后面板又变回
 * 「未知艺术家 / 未知专辑」。原因是 [Discs.publish] 每次都用空名字重建 DiscInfo。
 */
class DiscCacheDisplayTest {

    private lateinit var root: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("saltrip-discinfo")
        Discs.rootOverride = root
        PanelState.disc = null
        PanelState.discCover = null
        PanelState.forgetMeta()
        PanelState.showCandidates(emptyList())
    }

    @AfterTest
    fun tearDown() {
        Discs.rootOverride = null
        PanelState.disc = null
        PanelState.discCover = null
        runCatching {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun toc(): DiscToc = DiscToc(
        firstTrack = 1,
        lastTrack = 2,
        entries = listOf(
            TocEntry(1, true, 1, 0),
            TocEntry(2, true, 1, 15_000),
            TocEntry(0xAA, true, 1, 30_000)
        )
    )

    private fun scan(): ScanResult =
        ScanResult("\\\\.\\F:", InquiryData("SALT", "FAKE CD", "1.00", 0x05), toc())

    private fun writeCache(album: AlbumMeta) {
        val t = toc()
        val id = DiscId.musicBrainz(t)
        val p = DiscCache.path(root, id)
        Files.createDirectories(p.parent)
        Files.writeString(p, AlbumJsonWriter.build(album, t), Charsets.UTF_8)
        // 封面不走 JSON，而是 album.json 旁边的兄弟文件（MetaOverride.load 负责读回来）
        album.cover?.let { bytes ->
            val stem = p.fileName.toString().removeSuffix(".json")
            Files.write(p.parent.resolve("$stem.cover.jpg"), bytes)
        }
    }

    @Test
    fun infoCarriesCachedNames() {
        writeCache(
            AlbumMeta(
                albumArtist = "十明",
                albumTitle = "変身のレシピ",
                discId = "id-1",
                cddbId = "cddb-1",
                source = "manual",
                tracks = listOf(TrackMeta(1, "一目惚れ", "十明"))
            )
        )
        val info = Discs.info(scan())
        assertEquals("十明", info.artist)
        assertEquals("変身のレシピ", info.album)
        assertEquals(2, info.tracks)
        assertTrue(info.audio)
    }

    @Test
    fun infoWithoutCacheHasEmptyNames() {
        val info = Discs.info(scan())
        assertEquals("", info.artist)
        assertEquals("", info.album)
        assertEquals(2, info.tracks)
    }

    @Test
    fun publishRestoresNamesAndCoverAfterRepublish() {
        val cover = byteArrayOf(1, 2, 3, 4, 5)
        writeCache(
            AlbumMeta(
                albumArtist = "十明",
                albumTitle = "変身のレシピ",
                discId = "id-1",
                cddbId = "cddb-1",
                source = "manual",
                tracks = emptyList(),
                cover = cover,
                coverMime = "image/jpeg"
            )
        )
        // 第一次发布（等价于插盘）
        Discs.publish(scan())
        assertEquals("十明", PanelState.disc?.artist)
        assertEquals("変身のレシピ", PanelState.disc?.album)
        assertNotNull(PanelState.discCover)

        // 模拟换盘监视又跑了一轮：状态被重建也必须还是缓存里的名字与封面
        Discs.publish(scan())
        assertEquals("十明", PanelState.disc?.artist)
        assertEquals(5, PanelState.discCover?.size)
    }

    @Test
    fun showDiscNullClearsCover() {
        PanelState.discCover = byteArrayOf(9)
        PanelState.showDisc(null)
        assertNull(PanelState.discCover)
    }

    @Test
    fun manualDraftSurvivesAsCachedNames() {
        // 保存手填元数据后，下一次 publish 从缓存读回来的名字必须和填的一致
        val draft = ManualDraft(
            albumArtist = "王菲",
            albumTitle = "唱游",
            year = "1998",
            tracks = listOf(
                com.saltrip.rip.ui.ManualTrack(1, "感情生活", ""),
                com.saltrip.rip.ui.ManualTrack(2, "脸", "")
            )
        )
        writeCache(draft.toAlbumMeta(DiscId.musicBrainz(toc())))
        val info = Discs.info(scan())
        assertEquals("王菲", info.artist)
        assertEquals("唱游", info.album)
    }
}
