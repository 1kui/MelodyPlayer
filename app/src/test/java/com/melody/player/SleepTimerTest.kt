package com.melody.player

import com.melody.player.core.SleepOption
import com.melody.player.core.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ------------------------------------------------------------- 自定义时长

    @Test
    fun `自定义时长走同一条 deadline 算式`() {
        assertEquals(30 * 60_000L, SleepTimer.deadlineFrom(0L, 30))
        assertEquals(1_000L + 7 * 60_000L, SleepTimer.deadlineFrom(1_000L, 7))
        // 与预设那条重载必须给出同一个数，否则"选 30 分钟"和"填 30 分钟"到点会差一截
        assertEquals(
            SleepTimer.deadlineFrom(1_234L, SleepOption.M30),
            SleepTimer.deadlineFrom(1_234L, 30)
        )
    }

    @Test
    fun `自定义分钟数只认 1 到 600 之间的整数`() {
        assertEquals(25, SleepTimer.customMinutes("25"))
        assertEquals(60, SleepTimer.customMinutes(" 60 "))
        assertEquals(1, SleepTimer.customMinutes("1"))
        assertEquals(600, SleepTimer.customMinutes("600"))
    }

    @Test
    fun `越界与非法输入一律判死，不做夹取`() {
        // 夹取会让按"确定"的那一刻发生一次看不见的修改：
        // 填 0 变成 1 分钟，定时会比用户以为的早 1 分钟
        assertNull(SleepTimer.customMinutes("0"))
        assertNull(SleepTimer.customMinutes("601"))
        assertNull(SleepTimer.customMinutes("9999"))
        assertNull(SleepTimer.customMinutes(""))
        assertNull(SleepTimer.customMinutes("   "))
        assertNull(SleepTimer.customMinutes("abc"))
        assertNull(SleepTimer.customMinutes("九十分钟"))
    }

    @Test
    fun `顺手剥掉数字以外的字符`() {
        assertEquals(30, SleepTimer.customMinutes("30 分钟"))
        assertEquals(45, SleepTimer.customMinutes("45min"))
        assertEquals(90, SleepTimer.customMinutes("90分"))
        // 连负号也一起剥掉 —— 输入框本来就把非数字滤在外面，
        // 这里的剥字符只是"粘贴进来一串带单位的话"这一路的兜底
        assertEquals(30, SleepTimer.customMinutes("-30"))
    }

    @Test
    fun `自定义文案超过一小时换算成小时加分钟`() {
        assertEquals("25 分钟", SleepTimer.customLabel(25))
        assertEquals("59 分钟", SleepTimer.customLabel(59))
        assertEquals("1 小时", SleepTimer.customLabel(60))
        assertEquals("1 小时 30 分钟", SleepTimer.customLabel(90))
        assertEquals("10 小时", SleepTimer.customLabel(600))
    }
}
