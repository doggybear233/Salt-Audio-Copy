package com.saltrip.rip

import com.saltrip.rip.rip.EtaEstimator
import com.saltrip.rip.ui.PanelFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 剩余时间估算的回归测试。
 *
 * 老实现（累计平均）的病灶：`elapsed / done * remaining`，一旦中途出现慢轨，
 * 估算会**变大**。这里的用例专门盯住这一点。
 */
class EtaTest {

    @Test
    fun tooEarlyReturnsNull() {
        val e = EtaEstimator()
        // 只有 1 个样本：什么也算不出来，界面上应显示「正在估算…」而不是乱猜
        assertNull(e.sample(10, 10_000, 100))
    }

    @Test
    fun steadyRateGivesStableEstimate() {
        val e = EtaEstimator()
        // 每 100ms 完成 10 扇区 → 0.1 扇区/ms；共 1000 扇区
        var last: Long? = null
        var t = 0L
        var done = 0
        while (done < 500) {
            t += 100
            done += 10
            last = e.sample(done, 1000, t)
        }
        val eta = assertNotNull(last)
        // 剩 500 扇区、0.1/ms → 5000ms，允许 ±20% 平滑误差
        assertTrue(eta in 4000..6000, "eta=$eta")
    }

    @Test
    fun etaNeverJumpsUpWhenSpeedHalves() {
        val e = EtaEstimator()
        var t = 0L
        var done = 0
        // 前 4 秒：0.1 扇区/ms
        while (t < 4000) {
            t += 100
            done += 10
            e.sample(done, 10_000, t)
        }
        val before = assertNotNull(e.sample(done, 10_000, t))
        // 后 4 秒：速度砍半（0.05）
        while (t < 8000) {
            t += 100
            done += 5
            e.sample(done, 10_000, t)
        }
        val after = assertNotNull(e.sample(done, 10_000, t))
        // 真变慢了，估算允许上升，但不能「跳」：最多到上一版的两倍
        assertTrue(after <= before * 2 + 1_000, "before=$before after=$after")
        assertTrue(after >= before, "速度变慢时不应反而更乐观：before=$before after=$after")
    }

    @Test
    fun etaDropsImmediatelyWhenSpeedRecovers() {
        val e = EtaEstimator()
        var t = 0L
        var done = 0
        while (t < 4000) {
            t += 100
            done += 5
            e.sample(done, 10_000, t)
        }
        val slow = assertNotNull(e.sample(done, 10_000, t))
        while (t < 8000) {
            t += 100
            done += 40
            e.sample(done, 10_000, t)
        }
        val fast = assertNotNull(e.sample(done, 10_000, t))
        assertTrue(fast < slow / 2, "读顺了应立即给好消息：slow=$slow fast=$fast")
    }

    @Test
    fun doneReachingTotalReturnsZero() {
        val e = EtaEstimator()
        e.sample(500, 500, 1000)
        assertEquals(0L, e.sample(500, 500, 2000))
        assertEquals(0L, e.sample(600, 500, 3000))
    }

    @Test
    fun resetClearsHistory() {
        val e = EtaEstimator()
        var t = 0L
        var done = 0
        while (t < 3000) {
            t += 100
            done += 10
            e.sample(done, 1000, t)
        }
        e.reset()
        assertNull(e.sample(done, 1000, t + 100), "reset 后必须重新积累样本")
    }

    @Test
    fun backwardsProgressDoesNotPoisonEstimate() {
        val e = EtaEstimator()
        var t = 0L
        var done = 0
        while (t < 3000) {
            t += 100
            done += 10
            e.sample(done, 1000, t)
        }
        // 续抓/回退：进度变小
        val eta = e.sample(50, 1000, t + 100)
        assertTrue(eta == null || eta > 0, "回退后不能算出负数或 0 以外的乱值：$eta")
    }

    @Test
    fun formatterBucketsAndStates() {
        assertEquals("正在估算…", PanelFormat.eta(-1))
        assertEquals("即将完成", PanelFormat.eta(0))
        assertEquals("剩余约 5 秒", PanelFormat.eta(2))
        assertEquals("剩余约 45 秒", PanelFormat.eta(45))
        assertEquals("剩余约 1 分 15 秒", PanelFormat.eta(62))
        assertEquals("剩余约 10 分", PanelFormat.eta(600))
        assertEquals("剩余约 1 小时", PanelFormat.eta(3600))
        assertEquals("剩余约 1 小时 5 分", PanelFormat.eta(3900))
    }
}
