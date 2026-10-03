package com.melody.player.core

/**
 * 最近搜索过的关键词。
 *
 * 只在**用户真的搜过一次**之后才写入（[push] 不接收空白串），
 * 否则一边打字一边存，历史里会堆出"晴""晴天""晴天 "一串半成品。
 */
object RecentSearches {
    /** 最多留几条。搜过的东西通常几分钟内就会再点一次，8 条够用。 */
    const val MAX = 8

    /**
     * 记一次搜索：去首尾空白、去重（**大小写不敏感**，但保留下新输入的大小写）、
     * 最新的排最前、截断到 [MAX]。空白串原样返回，不写入。
     */
    fun push(list: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
    }

    /** 删掉一条。不存在时为无操作（返回原列表，不抛异常）。 */
    fun remove(list: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return list
        return list.filterNot { it.equals(q, ignoreCase = true) }
    }
}
