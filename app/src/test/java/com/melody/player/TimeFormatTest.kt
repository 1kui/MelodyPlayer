package com.melody.player

import com.melody.player.core.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `unknown duration renders placeholder`() {
        assertEquals("--:--", TimeFormat.clock(-1L))
        assertEquals("--:--", TimeFormat.clock(-99_999L))
    }

    @Test
    fun `formats under one hour`() {
        assertEquals("0:00", TimeFormat.clock(0L))
        assertEquals("0:05", TimeFormat.clock(5_000L))
        assertEquals("1:05", TimeFormat.clock(65_000L))
        assertEquals("59:59", TimeFormat.clock(3_599_000L))
    }

    @Test
    fun `formats over one hour`() {
        assertEquals("1:00:00", TimeFormat.clock(3_600_000L))
        assertEquals("1:02:03", TimeFormat.clock(3_723_000L))
    }

    @Test
    fun `truncates instead of rounding up`() {
        // 10 分 59.9 秒应该显示 10:59，不能进位成 11:00 让人以为已经跳到下一分钟
        assertEquals("10:59", TimeFormat.clock(659_900L))
    }

    @Test
    fun `human readable duration`() {
        assertEquals("0 秒", TimeFormat.durationText(0L))
        assertEquals("45 秒", TimeFormat.durationText(45_000L))
        assertEquals("3 分 45 秒", TimeFormat.durationText(225_000L))
        assertEquals("5 分", TimeFormat.durationText(300_000L))
        assertEquals("2 小时", TimeFormat.durationText(7_200_000L))
        assertEquals("1 小时 2 分", TimeFormat.durationText(3_720_000L))
    }

    @Test
    fun `delta carries sign`() {
        assertEquals("+10 秒", TimeFormat.delta(10_000L))
        assertEquals("-10 秒", TimeFormat.delta(-10_000L))
    }

    // ---------------------------------------------------------------- ago

    /** 固定"现在"，否则这一组断言会随日期漂移（今天是"3 天前"，明天就变"4 天前"）。 */
    private val now = 1_700_000_000L
    private fun ago(epochSec: Long) = TimeFormat.ago(epochSec, now)

    @Test
    fun `ago 说相对时间，一个月以上给日期`() {
        assertEquals("今天保存", ago(now))
        assertEquals("今天保存", ago(now - 3_600L))
        assertEquals("昨天保存", ago(now - 86_400L))
        assertEquals("3 天前保存", ago(now - 3 * 86_400L))
        assertEquals("29 天前保存", ago(now - 29 * 86_400L))
        // 到 30 天就换成具体日期：再往下"37 天前"要心算，日期不用
        val fortyDays = ago(now - 40 * 86_400L)
        assertEquals("2023-10-06", fortyDays)
    }

    @Test
    fun `ago 把未知时间单独说出来`() {
        // 索引里没有时间戳（老版本的记录）时是 0，不能显示成"1970-01-01"
        assertEquals("保存时间未知", ago(0L))
        assertEquals("保存时间未知", ago(-5L))
    }

    @Test
    fun `ago 对未来的时间戳不倒着说`() {
        // 设备时钟被改过、或索引里写进了未来时间：days <= 0 一律按"今天"，
        // 别冒出"-2 天前保存"这种句子
        assertEquals("今天保存", ago(now + 86_400L))
    }
}
