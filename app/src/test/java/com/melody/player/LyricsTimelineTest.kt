package com.melody.player

import com.melody.player.core.LyricLine
import com.melody.player.core.Lyrics
import com.melody.player.core.LyricsAligner
import com.melody.player.core.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimelineTest {

    private fun lyrics(vararg pairs: Pair<Long, String>) = Lyrics(
        lines = pairs.map { LyricLine(it.first, it.second) },
        synced = true,
        estimated = false,
        source = LyricsSource.EMBEDDED_SYNCED
    )

    @Test
    fun `returns minus one before first line`() {
        val model = lyrics(5_000L to "甲", 10_000L to "乙")
        assertEquals(-1, model.indexAt(0L))
        assertEquals(-1, model.indexAt(4_999L))
    }

    @Test
    fun `returns last line not after the position`() {
        val model = lyrics(0L to "甲", 5_000L to "乙", 10_000L to "丙")
        assertEquals(0, model.indexAt(0L))
        assertEquals(0, model.indexAt(4_999L))
        assertEquals(1, model.indexAt(5_000L))
        assertEquals(1, model.indexAt(9_999L))
        assertEquals(2, model.indexAt(10_000L))
        assertEquals(2, model.indexAt(999_999L))
    }

    @Test
    fun `merges duplicate timestamps into one bilingual item`() {
        val model = lyrics(
            1_000L to "Hello",
            1_000L to "你好",
            2_000L to "World",
            2_000L to "世界"
        )
        assertEquals(2, model.items.size)
        assertEquals("Hello\n你好", model.items[0].text)
        assertEquals("World\n世界", model.items[1].text)
        assertEquals(0, model.indexAt(1_500L))
        assertEquals(1, model.indexAt(2_500L))
    }

    @Test
    fun `merges blank first line rather than producing a leading newline`() {
        val model = lyrics(1_000L to "", 1_000L to "正文")
        assertEquals(1, model.items.size)
        assertEquals("正文", model.items[0].text)
    }

    @Test
    fun `empty lyrics are not usable`() {
        assertFalse(Lyrics.NONE.isUsable)
        assertEquals(-1, Lyrics.NONE.indexAt(1_000L))
    }

    @Test
    fun `estimation spreads lines evenly across duration`() {
        val duration = 200_000L
        val lines = LyricsAligner.estimate(listOf("一", "二", "三", "四"), duration)
        assertEquals(4, lines.size)
        assertEquals(25_000L, lines[0].timeMs)
        assertEquals(75_000L, lines[1].timeMs)
        assertEquals(125_000L, lines[2].timeMs)
        assertEquals(175_000L, lines[3].timeMs)
        assertTrue(lines.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
        assertTrue(lines.all { it.timeMs in 0..duration })
    }

    @Test
    fun `estimation drops blank lines`() {
        val lines = LyricsAligner.estimate(listOf("一", "  ", "", "二"), 100_000L)
        assertEquals(2, lines.size)
        assertEquals("一", lines[0].text)
        assertEquals("二", lines[1].text)
    }

    @Test
    fun `estimation falls back to one second spacing when duration unknown`() {
        val lines = LyricsAligner.estimate(listOf("一", "二"), 0L)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(1_000L, lines[1].timeMs)
    }
}
