package com.melody.player.core.online

import kotlin.math.abs

/**
 * 「这条候选是不是我要的那首歌」的唯一判定入口。
 *
 * 从 [NetEaseApi] 里抽出来，是因为现在有两家提供方（网易云、LRCLIB）要回答同一个问题。
 * 各写一份的后果和 [TextMatch] 那会儿一模一样：慢慢就会有一边改了规则、另一边没改，
 * 于是同一首歌从 A 家能匹配到、从 B 家匹配不到，而且看不出来为什么。
 *
 * 全部是纯函数，可单测。
 */
object LyricMatch {

    /**
     * 从候选里挑出最可能是同一首歌的一条，低于 [MIN_SCORE] 认为「没匹配上」。
     *
     * 打分刻意偏向时长：同名翻唱一大堆，而时长是唯一难以伪造的强特征。
     */
    fun pickBest(
        candidates: List<OnlineSong>,
        title: String,
        artist: String?,
        durationMs: Long
    ): OnlineSong? = candidates
        .map { it to score(it, title, artist, durationMs) }
        .filter { it.second >= MIN_SCORE }
        .maxByOrNull { it.second }
        ?.first

    /** 分数越高越像同一首。公开出来是为了能单独测评分逻辑。 */
    fun score(candidate: OnlineSong, title: String, artist: String?, durationMs: Long): Int {
        var score = 0
        val wantTitle = TextMatch.normalize(title)
        val gotTitle = TextMatch.normalize(candidate.title)
        if (wantTitle.isNotEmpty() && gotTitle.isNotEmpty()) {
            score += when {
                wantTitle == gotTitle -> 100
                gotTitle.startsWith(wantTitle) || wantTitle.startsWith(gotTitle) -> 70
                gotTitle.contains(wantTitle) || wantTitle.contains(gotTitle) -> 65
                else -> TextMatch.tokenOverlap(wantTitle, gotTitle)
            }
        }

        val wantArtist = TextMatch.normalize(artist.orEmpty())
        if (wantArtist.isNotEmpty()) {
            val hit = candidate.artists.any { a ->
                val na = TextMatch.normalize(a)
                na.isNotEmpty() && (na == wantArtist || na.contains(wantArtist) || wantArtist.contains(na))
            }
            score += if (hit) 30 else -10
        }

        if (durationMs > 0L && candidate.durationMs > 0L) {
            val delta = abs(durationMs - candidate.durationMs)
            score += when {
                delta <= 3_000L -> 25
                delta <= 8_000L -> 12
                delta <= 20_000L -> 0
                else -> -35
            }
        }
        return score
    }

    /** 低于这个分数宁可不给歌词，也不给一份对不上的。 */
    const val MIN_SCORE = 90
}
