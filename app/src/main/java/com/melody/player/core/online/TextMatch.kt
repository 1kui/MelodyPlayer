package com.melody.player.core.online

/**
 * 文本匹配的公共工具：歌名清洗、关键词拼接、归一化、相似度。
 *
 * 抽出来是因为**歌词匹配**（[NetEaseApi]）和**封面匹配**（[ITunesApi]）要解决的是同一个问题：
 * 在"同一首歌的多个版本/多种写法"里认出真正的那一首。两边各写一份的话，
 * 出现的第一种情况就是有人改了其中一份的规则 ——
 * 于是同一首歌能匹配到歌词、却匹配不到封面（或反过来），而且看不出为什么。
 *
 * 全部是纯函数，不依赖网络与 Android。
 */
object TextMatch {

    /**
     * 搜索关键词：歌名 + 歌手。
     *
     * 歌名里常带着 `(Live)`、`【】`、`feat.` 这类附属信息，直接搜会大幅降低命中率，
     * 这里先剥掉再拼。返回的关键词为空时说明歌名没法用，交给调用方放弃。
     */
    fun searchKeyword(title: String, artist: String?): String {
        val cleaned = cleanTitle(title)
        if (cleaned.isEmpty()) return ""
        val a = artist?.trim().orEmpty()
        return if (a.isEmpty() || a.equals("<unknown>", ignoreCase = true) || a.startsWith("未知")) {
            cleaned
        } else {
            "$cleaned $a"
        }
    }

    /** 剥掉括号附属信息与常见噪声后缀，保留真正的主标题。 */
    fun cleanTitle(raw: String): String {
        var t = raw.trim()
        // 全角/半角括号、【】、[] 里的内容一般是版本说明
        t = t.replace(Regex("""[（(\[【][^）)\]】]*[）)\]】]"""), " ")
        // feat. / ft. 之后是合作歌手，不属于标题
        t = t.replace(Regex("""(?i)\s*[-–—]?\s*\b(feat|ft)\.?\s.*$"""), " ")
        // 常见无损/音质后缀
        t = t.replace(Regex("""(?i)\b(hifi|hi-res|flac|320k|kbps|mv|live|remaster(ed)?)\b"""), " ")
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 归一化：转小写、去掉标点与空白、常见异体字统一。
     *
     * 只保留字母数字与 CJK，避免「フォニイ」和「フォニイ 」因为空格判成两首歌。
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        raw.lowercase().forEach { c ->
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c.code in 0x3040..0x30FF || c.code in 0x4E00..0x9FFF -> sb.append(c)
                else -> Unit
            }
        }
        return sb.toString()
    }

    /** 逐字重合度的粗略度量，用于「同名但写法略有差异」的兜底比较。 */
    fun tokenOverlap(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val common = a.toSet().intersect(b.toSet()).size
        val ratio = common.toDouble() / maxOf(a.toSet().size, b.toSet().size)
        return (ratio * 40).toInt()
    }
}
