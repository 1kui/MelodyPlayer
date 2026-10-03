package com.melody.player

import com.melody.player.core.SleepOption
import com.melody.player.core.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 睡眠定时的时间算术。
 *
 * 只有加减法，但两个边界的后果都很直接：
 * `remaining` 返回负数 → 界面显示 `-0:01`；
 * `expired` 差一毫秒不判过期 → 定时器永远不触发。
 */
class SleepTimerTest {

    @Test
    fun `deadline 精确到毫秒`() {
        assertEquals(1_000L + 30 * 60_000L, SleepTimer.deadlineFrom(1_000L, SleepOption.M30))
        assertEquals(0L + 90 * 60_000L, SleepTimer.deadlineFrom(0L, SleepOption.M90))
    }

    @Test
    fun `剩余时间不为负`() {
        assertEquals(0L, SleepTimer.remaining(1_000L, 5_000L))
        assertEquals(0L, SleepTimer.remaining(5_000L, 5_000L))
        assertEquals(4_000L, SleepTimer.remaining(5_000L, 1_000L))
    }

    @Test
    fun `正好到点就算过期`() {
        assertTrue(SleepTimer.expired(5_000L, 5_000L))
        assertTrue(SleepTimer.expired(5_000L, 5_001L))
        assertFalse(SleepTimer.expired(5_000L, 4_999L))
    }

    @Test
    fun `倒计时文案跨分钟与小时`() {
        assertEquals("不到 1 分钟", SleepTimer.remainingLabel(59_999L))
        assertEquals("不到 1 分钟", SleepTimer.remainingLabel(0L))
        assertEquals("1:00", SleepTimer.remainingLabel(60_000L))
        assertEquals("29:59", SleepTimer.remainingLabel(29 * 60_000L + 59_000L))
        assertEquals("1:30:00", SleepTimer.remainingLabel(90 * 60_000L))
    }

    @Test
    fun `每挡时长换算成毫秒`() {
        assertEquals(15 * 60_000L, SleepOption.M15.millis)
        assertEquals(90 * 60_000L, SleepOption.M90.millis)
    }
}
