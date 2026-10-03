package com.melody.player

import com.melody.player.core.HighlightRange
import com.melody.player.core.TextHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索关键词高亮。
 *
 * 高亮最怕的是**下标错位**：算出来的区间落在别的字上，用户看到半截词被染色。
 * 所以这里既钉"命中几处"，也钉"命中在哪一段"。
 */
class TextHighlightTest {

    @Test
    fun `多处命中，按出现顺序，不重叠`() {
        assertEquals(
            listOf(HighlightRange(0, 3), HighlightRange(3, 6)),
            TextHighlight.ranges("abcabc", "abc")
        )
    }

    @Test
    fun `重叠时跳过整段命中`() {
        // "aaa" 里找 "aa"：从 0 命中之后跳到 2，不会在 1 处再叠一个交叉区间
        assertEquals(listOf(HighlightRange(0, 2)), TextHighlight.ranges("aaa", "aa"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(listOf(HighlightRange(0, 5)), TextHighlight.ranges("HELLO world", "hello"))
        assertEquals(listOf(HighlightRange(0, 5)), TextHighlight.ranges("hello", "HELLO"))
        assertEquals(listOf(HighlightRange(6, 11)), TextHighlight.ranges("hello WORLD", "world"))
    }

    @Test
    fun `中文子串按下标命中`() {
        assertEquals(listOf(HighlightRange(2, 4)), TextHighlight.ranges("我的晴天呢", "晴天"))
    }

    @Test
    fun `空查询与纯空白不命中`() {
        assertTrue(TextHighlight.ranges("晴天", "").isEmpty())
        assertTrue(TextHighlight.ranges("晴天", "   ").isEmpty())
        assertTrue(TextHighlight.ranges("", "晴天").isEmpty())
    }

    @Test
    fun `关键词比文本长时为空`() {
        assertTrue(TextHighlight.ranges("晴", "晴天").isEmpty())
    }

    @Test
    fun `整串命中时是一段完整区间`() {
        assertEquals(listOf(HighlightRange(0, 2)), TextHighlight.ranges("晴天", "晴天"))
    }
}
