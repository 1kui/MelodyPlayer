package com.melody.player.core.online

import com.melody.player.core.LrcParser
import com.melody.player.core.TimeFormat

/**
 * 网易云歌词接口的解析与匹配。
 *
 * 全部是纯函数，与网络、Android 无关 —— 这一层最容易出错（匹配错了用户就拿到别人的
 * 歌词），所以刻意抽出来做成可单测的形式。
 *
 * 接口形状（见 https://www.techcrafts.cn/4.html）：
 *  - 搜索 `GET /api/search/get/?s=<关键词>&type=1&limit=<n>`
 *  - 歌词 `GET /api/song/lyric?os=pc&id=<id>&lv=-1&tv=-1`
 *
 * 候选与歌词的数据类、以及「是不是同一首歌」的打分规则都不在这里：
 * 前者见 [OnlineSong]，后者见 [LyricMatch]（LRCLIB 用的是同一套）。
 */
object NetEaseApi {

    const val SEARCH_HOST_PATH = "/api/search/get/"
    const val LYRIC_HOST_PATH = "/api/song/lyric"

    // ---------------------------------------------------------------- 解析

    /** 解析搜索响应，`code != 200` 或结构不符时返回空列表。 */
    fun parseSearch(body: String): List<OnlineSong> {
        val root = MiniJson.parse(body) ?: return emptyList()
        val songs = root.field("result").array("songs")
        return songs.mapNotNull { node ->
            val id = node.long("id") ?: return@mapNotNull null
            val name = node.str("name")?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            // artists 是对象数组，但字段名在历史版本里出现过 singer / artists 两种
            val artistNodes = node.array("artists").ifEmpty { node.array("singer") }
            OnlineSong(
                id = id,
                title = name,
                artists = artistNodes.mapNotNull { it.str("name")?.trim()?.takeIf(String::isNotEmpty) },
                album = node.field("album")?.str("name")?.trim()?.takeIf(String::isNotEmpty),
                durationMs = node.long("duration") ?: node.long("dt") ?: 0L
            )
        }
    }

    /**
     * 解析歌词响应，把原文与翻译按时间轴合并成一份 LRC。
     *
     * 无歌词时接口会返回 `nolyric: true` 或 `uncollected: true`，两者都当作没取到。
     * 翻译的时间戳通常与原文一一对应，合并后交给 `Lyrics.mergeEqualTimestamps`
     * 自然就成了「一行原文 + 一行译文」的双语歌词。
     */
    fun parseLyric(body: String, song: OnlineSong): OnlineLyric? {
        val root = MiniJson.parse(body) ?: return null
        if (root.bool("nolyric") == true) return null
        if (root.bool("uncollected") == true) return null

        val original = root.field("lrc").str("lyric")?.takeIf { it.isNotBlank() } ?: return null
        val translation = root.field("tlyric").str("lyric")?.takeIf { it.isNotBlank() }

        val merged = merge(original, translation)
        if (LrcParser.parse(merged).lines.isEmpty()) return null
        return OnlineLyric(song = song, lrcText = merged, hasTranslation = translation != null)
    }

    // ---------------------------------------------------------------- 合并

    /**
     * 原文与翻译按时间戳交错合并；没有翻译时**原样返回原文**，
     * 这样 `[ti:]`、`[offset:]` 这些头部信息一点不丢。
     *
     * 同一时刻的行顺序固定为「原文在前、译文在后」，与 [com.melody.player.core.Lyrics]
     * 的合并规则配合，显示出来就是「上面原文、下面译文」。
     */
    fun merge(original: String, translation: String?): String {
        if (translation.isNullOrBlank()) return original.trim()

        val origin = LrcParser.parse(original).lines
        if (origin.isEmpty()) return original.trim()
        val trans = LrcParser.parse(translation).lines
        if (trans.isEmpty()) return original.trim()

        // 译文有时会多出「纯时间轴但没有文字」的占位行，丢掉
        val transByTime = trans.filter { it.text.isNotBlank() }.groupBy { it.timeMs }

        val out = StringBuilder()
        origin.forEach { line ->
            out.append('[').append(TimeFormat.lrcStamp(line.timeMs)).append(']').append(line.text).append('\n')
            transByTime[line.timeMs]?.forEach { t ->
                out.append('[').append(TimeFormat.lrcStamp(t.timeMs)).append(']').append(t.text).append('\n')
            }
        }
        return out.toString().trimEnd()
    }

    // ---------------------------------------------------------------- 匹配

    /**
     * 从搜索结果里挑出最可能是同一首歌的一条，低于 [MIN_SCORE] 认为「没匹配上」。
     *
     * 实现已抽到 [LyricMatch]（LRCLIB 要回答同一个问题，不能有两套规则），
     * 这里保留同名入口是为了不打断既有调用点与测试。
     */
    fun pickBest(
        candidates: List<OnlineSong>,
        title: String,
        artist: String?,
        durationMs: Long
    ): OnlineSong? = LyricMatch.pickBest(candidates, title, artist, durationMs)

    /** 分数越高越像同一首。见 [LyricMatch.score]。 */
    fun score(candidate: OnlineSong, title: String, artist: String?, durationMs: Long): Int =
        LyricMatch.score(candidate, title, artist, durationMs)

    /**
     * 搜索关键词：歌名 + 歌手。
     *
     * 实现已抽到 [TextMatch]（封面匹配要解决同一个问题，不能有两套规则），
     * 这里保留同名入口是为了不打断既有调用点与测试。
     */
    fun searchKeyword(title: String, artist: String?): String = TextMatch.searchKeyword(title, artist)

    /** 剥掉括号附属信息与常见噪声后缀，保留真正的主标题。见 [TextMatch.cleanTitle]。 */
    fun cleanTitle(raw: String): String = TextMatch.cleanTitle(raw)

    /** 归一化：转小写、去掉标点与空白。见 [TextMatch.normalize]。 */
    fun normalize(raw: String): String = TextMatch.normalize(raw)

    /** 低于这个分数宁可不给歌词，也不给一份对不上的。见 [LyricMatch.MIN_SCORE]。 */
    const val MIN_SCORE = LyricMatch.MIN_SCORE
}
