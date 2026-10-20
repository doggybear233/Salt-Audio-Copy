package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.naming.TrackSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 轨选择规则解析（纯逻辑）。 */
class TrackSelectionTest {

    private val all = (1..10).toSet()

    @Test
    fun blankMeansAll() {
        assertNull(TrackSelection.parse("", all))
        assertNull(TrackSelection.parse("   ", all))
    }

    @Test
    fun singleAndList() {
        assertEquals(setOf(3), TrackSelection.parse("3", all))
        assertEquals(setOf(2, 5, 7), TrackSelection.parse("2,5,7", all))
        assertEquals(setOf(2, 5, 7), TrackSelection.parse(" 2 , 5 , 7 ", all))
    }

    @Test
    fun ranges() {
        assertEquals(setOf(1, 2, 3, 4, 5), TrackSelection.parse("1-5", all))
        assertEquals(setOf(1, 2, 3, 8, 10), TrackSelection.parse("1-3,8,10", all))
    }

    @Test
    fun openEndedRangeClampsToAvailable() {
        assertEquals(setOf(8, 9, 10), TrackSelection.parse("8-", all))
        assertEquals(setOf(8, 9, 10), TrackSelection.parse("8-99", all))
    }

    @Test
    fun junkIsIgnoredNotFatal() {
        // 多余逗号、0 号轨、越界、反向区间、非数字：一律跳过，不抛异常
        assertEquals(setOf(4), TrackSelection.parse(",,4,,", all))
        assertEquals(emptySet(), TrackSelection.parse("0", all))
        assertEquals(emptySet(), TrackSelection.parse("5-3", all))
        assertEquals(emptySet(), TrackSelection.parse("abc", all))
        assertEquals(setOf(1), TrackSelection.parse("1,99,abc", all))
    }

    @Test
    fun semicolonAndSpaceAlsoSeparate() {
        assertEquals(setOf(1, 2, 3), TrackSelection.parse("1;2 3", all))
    }

    @Test
    fun describeCompressesRuns() {
        assertEquals("1-3,5", TrackSelection.describe(listOf(1, 2, 3, 5)))
        assertEquals("7", TrackSelection.describe(listOf(7)))
        assertEquals("(空)", TrackSelection.describe(emptyList()))
        assertEquals("1-3", TrackSelection.describe(listOf(3, 1, 2)))
    }
}
