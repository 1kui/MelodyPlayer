package com.melody.player

import com.melody.player.core.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses basic timestamps`() {
        val parsed = LrcParser.parse(
            """
            [00:12.00]第一行
            [00:15.50]第二行
            [01:02.25]第三行
            """.trimIndent()
        )
        assertEquals(3, parsed.lines.size)
        assertEquals(12_000L, parsed.lines[0].timeMs)
        assertEquals(15_500L, parsed.lines[1].timeMs)
        assertEquals(62_250L, parsed.lines[2].timeMs)
        assertEquals("第二行", parsed.lines[1].text)
    }

    @Test
    fun `supports multiple timestamps on one line`() {
        val parsed = LrcParser.parse("[00:10.00][01:20.00]副歌")
        assertEquals(2, parsed.lines.size)
        assertEquals(10_000L, parsed.lines[0].timeMs)
        assertEquals(80_000L, parsed.lines[1].timeMs)
        assertTrue(parsed.lines.all { it.text == "副歌" })
    }

    @Test
    fun `supports colon as fraction separator`() {
        val parsed = LrcParser.parse("[00:12:34]笔误写法")
        assertEquals(1, parsed.lines.size)
        assertEquals(12_340L, parsed.lines[0].timeMs)
    }

    @Test
    fun `supports three digit fraction`() {
        val parsed = LrcParser.parse("[00:01.234]毫秒精度")
        assertEquals(1_234L, parsed.lines[0].timeMs)
    }

    @Test
    fun `reads metadata tags`() {
        val parsed = LrcParser.parse(
            """
            [ti:测试歌曲]
            [ar:测试歌手]
            [al:测试专辑]
            [by:someone]
            [00:01.00]正文
            """.trimIndent()
        )
        assertEquals("测试歌曲", parsed.title)
        assertEquals("测试歌手", parsed.artist)
        assertEquals("测试专辑", parsed.album)
        assertEquals("someone", parsed.by)
        assertEquals(1, parsed.lines.size)
    }

    @Test
    fun `applies offset by shifting lyrics earlier`() {
        // 社区约定：正 offset 表示歌词提前显示，所以最终时间 = 标签时间 - offset
        val parsed = LrcParser.parse("[offset:500]\n[00:10.00]提前了")
        assertEquals(500L, parsed.offsetMs)
        assertEquals(9_500L, parsed.lines[0].timeMs)
    }

    @Test
    fun `negative offset shifts lyrics later`() {
        val parsed = LrcParser.parse("[offset:-300]\n[00:10.00]延后了")
        assertEquals(10_300L, parsed.lines[0].timeMs)
    }

    @Test
    fun `clamps negative results to zero`() {
        val parsed = LrcParser.parse("[offset:5000]\n[00:01.00]会被夹到零")
        assertEquals(0L, parsed.lines[0].timeMs)
    }

    @Test
    fun `sorts out of order lines`() {
        val parsed = LrcParser.parse(
            """
            [00:30.00]第三
            [00:10.00]第一
            [00:20.00]第二
            """.trimIndent()
        )
        assertEquals(listOf(10_000L, 20_000L, 30_000L), parsed.lines.map { it.timeMs })
    }

    @Test
    fun `treats plain text as untimed`() {
        val parsed = LrcParser.parse("第一行歌词\n第二行歌词\n\n第三行歌词")
        assertFalse(parsed.hasTimestamps)
        assertEquals(listOf("第一行歌词", "第二行歌词", "第三行歌词"), parsed.untimedText)
    }

    @Test
    fun `handles crlf and blank lines`() {
        val parsed = LrcParser.parse("[00:01.00]甲\r\n\r\n[00:02.00]乙\r\n")
        assertEquals(2, parsed.lines.size)
        assertEquals("甲", parsed.lines[0].text)
    }

    @Test
    fun `keeps empty lyric lines for pacing`() {
        val parsed = LrcParser.parse("[00:01.00]甲\n[00:05.00]\n[00:09.00]乙")
        assertEquals(3, parsed.lines.size)
        assertEquals("", parsed.lines[1].text)
    }

    @Test
    fun `empty input yields nothing`() {
        val parsed = LrcParser.parse("")
        assertFalse(parsed.hasTimestamps)
        assertTrue(parsed.untimedText.isEmpty())
    }
}
