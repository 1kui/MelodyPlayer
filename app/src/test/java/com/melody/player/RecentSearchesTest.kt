package com.melody.player

import com.melody.player.core.RecentSearches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最近搜索。
 *
 * 写入口径错了会很难用：一边打字一边存会让历史里堆满"晴""晴天"这样的半成品，
 * 而用户真正想找的是他刚才**搜过**的那一次。
 */
class RecentSearchesTest {

    @Test
    fun `去重并置顶，保留下新输入的大小写`() {
        var list = RecentSearches.push(emptyList(), "晴天")
        list = RecentSearches.push(list, "七里香")
        list = RecentSearches.push(list, "晴天")
        assertEquals(listOf("晴天", "七里香"), list)

        var latin = RecentSearches.push(emptyList(), "Hello")
        latin = RecentSearches.push(latin, "hello")
        assertEquals(listOf("hello"), latin)
    }

    @Test
    fun `空白串不写入`() {
        assertTrue(RecentSearches.push(emptyList(), "   ").isEmpty())
        assertTrue(RecentSearches.push(emptyList(), "").isEmpty())
        // 原列表原样返回，不清空已有历史
        assertEquals(listOf("晴天"), RecentSearches.push(listOf("晴天"), "  "))
    }

    @Test
    fun `首尾空白被去掉`() {
        assertEquals(listOf("晴天"), RecentSearches.push(emptyList(), " 晴天 "))
    }

    @Test
    fun `超过上限时丢掉最旧的`() {
        var list = emptyList<String>()
        repeat(RecentSearches.MAX + 3) { list = RecentSearches.push(list, "词$it") }
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("词${RecentSearches.MAX + 2}", list.first())
        assertEquals("词3", list.last())
    }

    @Test
    fun `删除忽略大小写，删不存在的项是无操作`() {
        assertEquals(emptyList<String>(), RecentSearches.remove(listOf("Hello"), "hello"))
        assertEquals(listOf("晴天"), RecentSearches.remove(listOf("晴天"), "七里香"))
        assertEquals(listOf("晴天"), RecentSearches.remove(listOf("晴天"), "  "))
    }
}
