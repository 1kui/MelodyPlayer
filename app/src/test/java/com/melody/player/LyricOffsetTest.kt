package com.melody.player

import com.melody.player.core.LyricOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌词时间轴偏移。
 *
 * 这块判算错了**不会崩**：用户只会觉得"调了没用"或者"歌词跑到奇怪的地方去了"，
 * 而且偏移是逐曲记的，很难复现出是哪一首、哪一挡出问题。所以边界全钉在这里。
 */
class LyricOffsetTest {

    @Test
    fun `步进一挡，方向正确`() {
        assertEquals(500L, LyricOffset.step(0L, 1))
        assertEquals(0L, LyricOffset.step(500L, -1))
        assertEquals(1_000L, LyricOffset.step(500L, 1))
    }

    @Test
    fun `顶在上下限上不会越界`() {
        assertEquals(LyricOffset.MAX_MS, LyricOffset.step(LyricOffset.MAX_MS, 1))
        assertEquals(-LyricOffset.MAX_MS, LyricOffset.step(-LyricOffset.MAX_MS, -1))
        // 只差一点点也照样钳到边界，不会走出 MAX
        assertEquals(LyricOffset.MAX_MS, LyricOffset.step(LyricOffset.MAX_MS - 100L, 1))
        assertEquals(-LyricOffset.MAX_MS, LyricOffset.step(-LyricOffset.MAX_MS + 100L, -1))
    }

    @Test
    fun `延后时查询位置变小，提前时变大`() {
        // 正偏移 = 歌词延后 = 查更早的行
        assertEquals(9_500L, LyricOffset.apply(10_000L, 500L))
        // 负偏移 = 歌词提前 = 查更晚的行
        assertEquals(10_500L, LyricOffset.apply(10_000L, -500L))
        assertEquals(10_000L, LyricOffset.apply(10_000L, 0L))
    }

    @Test
    fun `提前到歌开头时不钳零，避免开头几行塌成一行`() {
        // 钳 0 会让开头几行落在同一时刻、被合并成一行 ——
        // 用户看到的是"第一行变成奇怪的一长条"，而不是"歌词整体提前"
        assertEquals(-1_500L, LyricOffset.apply(0L, 1_500L))
    }

    @Test
    fun `标注三态，秒数不带多余小数点`() {
        assertEquals("未校正", LyricOffset.label(0L))
        assertEquals("延后 0.5 秒", LyricOffset.label(500L))
        assertEquals("延后 1 秒", LyricOffset.label(1_000L))
        assertEquals("延后 1.5 秒", LyricOffset.label(1_500L))
        assertEquals("提前 2 秒", LyricOffset.label(-2_000L))
    }
}
