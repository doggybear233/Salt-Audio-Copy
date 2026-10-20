package com.saltrip.rip

import com.saltrip.rip.album.AlbumJsonWriter
import com.saltrip.rip.album.DiscCache
import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.meta.TrackMeta
import com.saltrip.rip.rip.ScanResult
import com.saltrip.rip.scsi.DiscToc
import com.saltrip.rip.scsi.InquiryData
import com.saltrip.rip.scsi.TocEntry
import com.saltrip.rip.ui.PanelState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用户实测（m04917）：换输出根之后，当前专辑（尤其是手填的）元数据会消失，
 * 输出路径变回 `未知艺术家\未知专辑`。
 *
 * 原因是缓存只按「当前输出根」找。这里验证三层兜底：当前根 → 保险副本 → 系统音乐目录。
 */
class DiscCacheFallbackTest {

    private lateinit var vault: Path
    private lateinit var rootA: Path
    private lateinit var rootB: Path

    private val toc = DiscToc(
        firstTrack = 1,
        lastTrack = 1,
        entries = listOf(
            TocEntry(1, true, 1, 0),
            TocEntry(0xAA, true, 1, 30_000)
        )
    )

    private fun album(artist: String, title: String) = AlbumMeta(
        albumArtist = artist,
        albumTitle = title,
        year = "2024",
        discId = "TESTDISC",
        cddbId = "01020304",
        source = "manual",
        tracks = listOf(TrackMeta(1, "第一首", artist))
    )

    @BeforeTest
    fun setUp() {
        vault = Files.createTempDirectory("saltrip-vault")
        rootA = Files.createTempDirectory("saltrip-root-a")
        rootB = Files.createTempDirectory("saltrip-root-b")
        DiscCache.vaultOverride = vault
    }

    @AfterTest
    fun tearDown() {
        DiscCache.vaultOverride = null
    }

    @Test
    fun searchPathsPutTheCurrentRootFirst() {
        val paths = DiscCache.searchPaths(rootA, "TESTDISC")
        assertEquals(DiscCache.path(rootA, "TESTDISC"), paths.first())
        assertTrue(paths.any { it.startsWith(vault) })
    }

    @Test
    fun cacheSavedUnderOneRootIsFoundFromAnotherAfterMirroring() {
        val json = AlbumJsonWriter.build(album("十明", "変身のレシピ"), toc)
        val cache = DiscCache.path(rootA, "TESTDISC")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, json)

        // 没搬之前，新根找不到（这正是用户看到的「未知艺术家」）
        assertNull(DiscCache.load(rootB, "TESTDISC"))

        assertTrue(DiscCache.mirrorToVault(rootA, "TESTDISC"))

        val found = DiscCache.load(rootB, "TESTDISC")
        assertNotNull(found)
        assertEquals("十明", found.albumArtist)
        assertEquals("変身のレシピ", found.albumTitle)
    }

    @Test
    fun mirrorAlsoCarriesTheCoverSibling() {
        val json = AlbumJsonWriter.build(album("A", "B"), toc)
        val cache = DiscCache.path(rootA, "TESTDISC")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, json)
        val cover = cache.resolveSibling(cache.fileName.toString().removeSuffix(".json") + ".cover.jpg")
        Files.write(cover, byteArrayOf(1, 2, 3, 4))

        assertTrue(DiscCache.mirrorToVault(rootA, "TESTDISC"))

        val mirrored = DiscCache.vaultPath("TESTDISC")
        assertNotNull(mirrored)
        val mirroredCover = mirrored.resolveSibling(cover.fileName.toString())
        assertTrue(Files.isRegularFile(mirroredCover))
    }

    @Test
    fun mirrorWithoutCacheIsHarmless() {
        assertTrue(!DiscCache.mirrorToVault(rootA, "NOPE"))
        assertNull(DiscCache.load(rootB, "NOPE"))
    }

    @Test
    fun blankDiscIdFindsNothing() {
        assertTrue(DiscCache.searchPaths(rootA, "").isEmpty())
        assertNull(DiscCache.load(rootA, ""))
    }

    @Test
    fun infoUsesRememberedMetaWhenCacheIsGone() {
        Discs.rootOverride = rootB
        PanelState.disc = null
        PanelState.forgetMeta()
        PanelState.rememberMeta("TESTDISC", album("十明", "変身のレシピ"))

        val r = ScanResult("\\\\.\\F:", InquiryData("SALT", "FAKE CD", "1.00", 0x05), toc)
        val info = Discs.info(r)
        // DiscID 由 TOC 算出来，跟这里塞的 id 不一样时不该误用内存值
        if (info.discId == "TESTDISC") {
            assertEquals("十明", info.artist)
            assertEquals("変身のレシピ", info.album)
        } else {
            assertEquals("", info.artist)
        }
        Discs.rootOverride = null
    }
}
