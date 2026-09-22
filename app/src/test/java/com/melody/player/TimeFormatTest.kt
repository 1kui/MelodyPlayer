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
}
