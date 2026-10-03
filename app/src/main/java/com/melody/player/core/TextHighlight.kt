package com.melody.player.core

/** 文本里一段命中的下标区间，左闭右开（`endExclusive` 适合直接喂给 `subSequence`）。 */
data class HighlightRange(val start: Int, val endExclusive: Int)

/**
 * 搜索关键词高亮：给出命中的区间，渲染交给 Compose 的 `AnnotatedString`。
 *
 * 用 `regionMatches(ignoreCase = true)` 逐位比较，而不是"两边都转小写再 indexOf"：
 * 小写化在少数语言里会**改变字符串长度**（土耳其语的 `İ` 会变成两个字符），
 * 那样算出来的下标落在原串上就是错位的，高亮会偏出去甚至越界。
 */
object TextHighlight {

    /**
     * 命中区间：大小写不敏感、**从左到右不重叠**、按出现顺序。
     * 空文本或空白关键词返回空列表（不画高亮，也不该把整行染上色）。
     */
    fun ranges(text: String, query: String): List<HighlightRange> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val n = text.length
        val m = needle.length
        if (m == 0 || n < m) return emptyList()

        val out = mutableListOf<HighlightRange>()
        var i = 0
        while (i <= n - m) {
            if (text.regionMatches(i, needle, 0, m, ignoreCase = true)) {
                out += HighlightRange(i, i + m)
                i += m            // 跳过整段命中，避免 "aa" 在 "aaa" 里叠出两段交叉区间
            } else {
                i++
            }
        }
        return out
    }
}
