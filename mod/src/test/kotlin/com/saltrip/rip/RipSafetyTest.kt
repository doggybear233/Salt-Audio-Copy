package com.saltrip.rip

import com.saltrip.rip.album.DiscGuard
import com.saltrip.rip.album.FreeSpace
import com.saltrip.rip.album.Overwrite
import com.saltrip.rip.scsi.DiscToc
import com.saltrip.rip.scsi.TocEntry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 覆盖策略、余量估算、换碟守卫。全部离线。 */
class RipSafetyTest {

    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("saltrip-safety")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    // ---------- Overwrite ----------

    @Test
    fun freeNameIsReturnedAsIs() {
        val p = Overwrite.resolve(dir, "01 song", ".wav", Overwrite.SUFFIX)
        assertEquals("01 song.wav", p!!.fileName.toString())
    }

    @Test
    fun suffixAddsCounterAndNeverOverwrites() {
        Files.createFile(dir.resolve("01 song.wav"))
        val p = Overwrite.resolve(dir, "01 song", ".wav", Overwrite.SUFFIX)!!
        assertEquals("01 song (2).wav", p.fileName.toString())

        Files.createFile(dir.resolve("01 song (2).wav"))
        val q = Overwrite.resolve(dir, "01 song", ".wav", Overwrite.SUFFIX)!!
        assertEquals("01 song (3).wav", q.fileName.toString())
    }

    @Test
    fun overwriteKeepsSameName() {
        Files.createFile(dir.resolve("a.wav"))
        val p = Overwrite.resolve(dir, "a", ".wav", Overwrite.OVERWRITE)!!
        assertEquals("a.wav", p.fileName.toString())
    }

    @Test
    fun skipReturnsNullWhenPresent() {
        Files.createFile(dir.resolve("a.wav"))
        assertNull(Overwrite.resolve(dir, "a", ".wav", Overwrite.SKIP))
        assertNotNull(Overwrite.resolve(dir, "b", ".wav", Overwrite.SKIP))
    }

    @Test
    fun groupShiftsTogetherSoCueNeverPointsAtStaleFile() {
        Files.createFile(dir.resolve("Album.cue"))
        val g = Overwrite.resolveGroup(dir, "Album", listOf(".wav", ".cue", ".log"), Overwrite.SUFFIX)
        assertEquals(listOf("Album (2).wav", "Album (2).cue", "Album (2).log"), g.map { it!!.fileName.toString() })
    }

    @Test
    fun groupWithNothingExistingKeepsBaseName() {
        val g = Overwrite.resolveGroup(dir, "X", listOf(".wav", ".cue"), Overwrite.SUFFIX)
        assertEquals(listOf("X.wav", "X.cue"), g.map { it!!.fileName.toString() })
    }

    // ---------- FreeSpace ----------

    @Test
    fun estimateCountsHeaderPerTrack() {
        assertEquals(2352L * 1000 + 44L * 2, FreeSpace.estimateBytes(1000, 2))
    }

    @Test
    fun requirementAddsSlack() {
        val audio = FreeSpace.estimateBytes(340_448, 21)
        val need = FreeSpace.requiredBytes(340_448, 21)
        assertTrue(need > audio, "应该有 15% + 8MB 的余量")
    }

    @Test
    fun checkReportsShortageAndPassesWhenPlenty() {
        assertNull(FreeSpace.check(50L * 1024 * 1024 * 1024, 340_448, 21, keepWav = true))
        val msg = FreeSpace.check(10L * 1024 * 1024, 340_448, 21, keepWav = true)
        assertNotNull(msg)
        assertTrue(msg.contains("磁盘空间不足"))
    }

    @Test
    fun keepingWavOffNeedsMoreHeadroom() {
        val sectors = 340_448L
        val a = FreeSpace.requiredBytes(sectors, 21)
        // 不保留 WAV 时 WAV 与 FLAC 要同时存在，需求应更大
        val msgKeep = FreeSpace.check(a + 1, sectors, 21, keepWav = true)
        val msgDrop = FreeSpace.check(a + 1, sectors, 21, keepWav = false)
        assertNull(msgKeep)
        assertNotNull(msgDrop)
    }

    // ---------- DiscGuard ----------

    private fun toc(leadOut: Int, lbas: List<Int>): DiscToc = DiscToc(
        firstTrack = 1,
        lastTrack = lbas.size,
        entries = lbas.mapIndexed { i, lba -> TocEntry(i + 1, isAudio = true, adr = 1, lba = lba) } +
            TocEntry(0xAA, isAudio = true, adr = 1, lba = leadOut)
    )

    @Test
    fun sameDiscComparesLeadOutAndEveryTrack() {
        val a = toc(200_000, listOf(0, 10_000, 20_000))
        assertTrue(DiscGuard.sameDisc(a, toc(200_000, listOf(0, 10_000, 20_000))))
        assertTrue(!DiscGuard.sameDisc(a, toc(210_000, listOf(0, 10_000, 20_000))))
        assertTrue(!DiscGuard.sameDisc(a, toc(200_000, listOf(0, 11_000, 20_000))))
        assertTrue(!DiscGuard.sameDisc(a, toc(200_000, listOf(0, 10_000))))
    }

    @Test
    fun mismatchMessageTellsUserWhatHappened() {
        val m = DiscGuard.mismatchMessage(200_000, 210_000, "第 5 轨之前")
        assertTrue(m.contains("光盘已变更"))
        assertTrue(m.contains("200000"))
    }
}
