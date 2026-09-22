package com.melody.player.core.online

/**
 * 在线歌词的提供方。
 *
 * 有了两家之后，[OnlineSong] 必须自己说清「我来自哪」—— 取词要打到对应的接口上，
 * 界面上的来源标注也要如实，不能一律写「网易云」。
 */
enum class LyricProvider(val label: String) {
    /** 网易云：按关键词搜、可带官方翻译，歌词要再发一次请求才拿得到。 */
    NETEASE("网易云"),

    /** LRCLIB：按「歌名 + 歌手 + 时长」签名查询，歌词随搜索结果一起返回。 */
    LRCLIB("LRCLIB")
}

/**
 * 在线搜到的一首候选歌曲。
 *
 * 两个提供方的字段形状不同（网易云给艺术家数组、时长是毫秒；LRCLIB 给单个 artistName、
 * 时长是秒），解析时统一收敛到这里的形状，打分与展示就只有一套规则。
 */
data class OnlineSong(
    val id: Long,
    val title: String,
    val artists: List<String>,
    val album: String?,
    /** 接口给的时长（毫秒），拿不到时为 0。 */
    val durationMs: Long,
    val provider: LyricProvider = LyricProvider.NETEASE,
    /**
     * 服务端随搜索结果一起返回的歌词正文。
     *
     * LRCLIB 就是这样（搜索与取词是同一个响应），有它就不必再发第二次请求；
     * 网易云要单独取词，所以这里是 null。
     */
    val inlineLyrics: String? = null,
    /** 这份歌词是否带时间轴；提供方没说明时为 null（网易云要取到词才知道）。 */
    val synced: Boolean? = null
) {
    val artistText: String get() = artists.filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "未知歌手" }

    /** 界面上展示的一行文案。 */
    val display: String get() = if (album.isNullOrBlank()) title else "$title · $album"
}

/** 一次「搜索 + 取词」的结果。 */
data class OnlineLyric(
    val song: OnlineSong,
    /** 合并了翻译之后的 LRC 文本。 */
    val lrcText: String,
    val hasTranslation: Boolean
)
