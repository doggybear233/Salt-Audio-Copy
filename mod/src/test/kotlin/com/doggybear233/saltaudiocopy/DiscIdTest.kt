package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.DiscId
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.scsi.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DiscID 算法回归测试。期望值由独立实现（Python，已对真实光盘与 MusicBrainz/gnudb 校验过）生成。
 */
class DiscIdTest {

    private fun toc(leadOut: Int, vararg starts: Pair<Int, Int>): DiscToc {
        val entries = starts.map { (t, lba) -> TocEntry(t, true, 1, lba) } + TocEntry(0xAA, true, 1, leadOut)
        return DiscToc(starts.first().first, starts.last().first, entries)
    }

    @Test
    fun musicBrainzTwoTracks() {
        assertEquals("b3ZJke85dEdDPFKFx.uWJtwOzkI-", DiscId.musicBrainz(toc(10000, 1 to 0, 2 to 5000)))
    }

    @Test
    fun musicBrainzThreeTracks() {
        assertEquals(
            "DjuQ0ln11Dszi6wpQ8y3Gumdruo-",
            DiscId.musicBrainz(toc(224166, 1 to 0, 2 to 30000, 3 to 60000))
        )
    }

    @Test
    fun cddbTwoTracks() {
        assertEquals("82020006", DiscId.cddb(toc(10000, 1 to 0, 2 to 5000)))
    }

    @Test
    fun cddbThreeTracks() {
        assertEquals("b503001b", DiscId.cddb(toc(224166, 1 to 0, 2 to 30000, 3 to 60000)))
    }

    @Test
    fun discIdHasNoBase64PaddingChars() {
        val id = DiscId.musicBrainz(toc(10000, 1 to 0, 2 to 5000))
        assertEquals(28, id.length)
        assertEquals(false, id.contains('='))
        assertEquals(false, id.contains('+'))
        assertEquals(false, id.contains('/'))
    }
}
