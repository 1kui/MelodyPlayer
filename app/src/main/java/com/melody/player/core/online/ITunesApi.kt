package com.melody.player.core.online

import java.net.URLEncoder

/** iTunes 搜到的一首候选歌曲。 */
data class ITunesHit(
    val trackId: Long,
    val collectionId: Long,
    val title: String,
    val artist: String,
    val album: String,
    /**
     * 接口只给 artworkUrl30/60/100 三个小图，高清靠替换 URL 末尾的尺寸段得到，
     * 见 [ITunesApi.artworkUrl]。这里存的是 100×100 那个。
     */
    val artworkUrl100: String?,
    val durationMs: Long,
    /** ISO8601，例如 `2017-03-03T08:00:00Z`。同名多版本时用来偏向最早发行的那版。 */
    val releaseDate: String?,
    val genre: String?
)

/**
 * iTunes / Apple Music Search API 的解析与匹配。
 *
 * 全部是纯函数，与网络、Android 无关。这一层必须可单测，因为**它的失败是静默的**：
 * 匹配错了不会报错，只会给某首歌贴上一张别人的封面 —— 而用户多半也认不出来。
 *
 * 接口形状（官方 <https://performance-partners.apple.com/search-api>）：
 *  - 搜索 `GET /search?term=<关键词>&media=music&entity=song&attribute=songTerm&limit=<n>&country=<cc>`
 *  - 精确 `GET /lookup?id=<trackId|collectionId>&country=<cc>`
 *  - 顶层固定 `{"resultCount": N, "results": [...]}`
 *
 * 三件必须照做的事（都不是"最佳实践"，是不做就不出结果）：
 *  1. **`country` 不能用 CN** —— 中国大陆区 iTunes Store 没有音乐目录。
 *     实测 `country=CN&term=周杰倫+晴天` 返回 `resultCount: 0`，TW 返回 5。
 *     夹具 `app/src/test/resources/itunes/search_qingtian_cn.json` 就是那个空响应。
 *  2. **`term` 必须正确编码**：空格变 `+`、中文走 percent-encoding（`周杰倫` →
 *     `%E5%91%A8%E6%9D%B0%E5%80%AB`）。用 [java.net.URLEncoder] 一次到位。
 *  3. **`artworkUrl100` 可能缺失**（字段仅在有封面时返回），调用方要能接受 null。
 */
object ITunesApi {

    const val SEARCH_PATH = "/search"
    const val LOOKUP_PATH = "/lookup"

    /**
     * 默认地区回退链，按华语歌曲的命中率排序。
     *
     * **不含 CN**：见类注释第 1 条。华语歌优先 TW，港台都有；US 作为兜底
     * （欧美歌在 TW 也大多能搜到，只是曲库不如 US 全）。
     *
     * 用户在设置里开了「自定义获取地区」之后，实际用的是他选的那几个（[CoverRegion]）；
     * 这里保留默认值，让单测、文档与"什么都没设过"的行为有唯一的一份定义。
     */
    val COUNTRIES: List<String> = CoverRegion.DEFAULT_CODES

    /** 默认取的封面边长。1000×1000 实测约 444 KB，播放页足够清晰，也不至于太大。 */
    const val ARTWORK_SIZE = 1000

    /** 源图上限。实测请求 5000 仍只返回 3000×3000，再往上要也是白要。 */
    const val MAX_ARTWORK_SIZE = 3000

    const val SEARCH_LIMIT = 5

    /** 低于这个分数宁可不给封面，也不给一张张冠李戴的。设置页可调，默认值就是它。 */
    const val MIN_SCORE = 90

    /**
     * 评分门槛可调的下限。
     *
     * 再低就等于「来者不拒」了 —— 那不是调门槛该解决的问题，直接用开关关掉自动获取更干脆。
     */
    const val MIN_SCORE_FLOOR = 20

    /** 门槛的上限：满分是歌名 100 + 歌手 40 + 时长 30，再往上谁也过不了。 */
    const val MAX_SCORE = 170

    /**
     * URL 末尾的尺寸段：`…/190295851286.jpg/100x100bb.jpg`。
     *
     * 用正则而不是官方示例里那句 `url.replace("100x100bb.jpg", …)`，
     * 是因为后者有两个静默失效点：格式写成 `.png` 时替换不到、
     * 基准尺寸不是 100 时也替换不到 —— 两种情况都不会报错，
     * 只会安静地退回小图，表现为「封面能显示但特别糊」，很难查到原因。
     */
    private val SIZE_SEGMENT = Regex("""/(\d+)x(\d+)bb\.(\w+)$""")

    /** 拼搜索查询串（不含域名）。[term] 自行编码，调用方不用管。 */
    fun searchQuery(term: String, country: String, limit: Int = SEARCH_LIMIT): String = buildString {
        append("term=").append(encode(term))
        append("&media=music")
        // entity=song：不指定时默认返回 track，会混进 music video（封面不是专辑封面）
        append("&entity=song")
        append("&attribute=songTerm")
        append("&limit=").append(limit.coerceIn(1, 200))
        append("&country=").append(country)
    }

    /** 按 Apple 的 id 精确取（官方推荐：ID 查询更快、误判更少）。 */
    fun lookupQuery(id: Long, country: String): String =
        "id=$id&country=$country&entity=song&limit=1"

    /**
     * 把封面提升到指定边长。
     *
     * Apple CDN 会按请求尺寸实时裁切；超过源图时返回源图尺寸而不是报错（5000 → 3000）。
     * 图片**会被强制裁成正方形**，需要横图得自己裁。
     *
     * 尺寸段识别不出来时**原样返回**（而不是拼一个自己猜的 URL）：
     * 原 URL 至少一定是能打开的图，猜出来的 URL 可能 404。
     */
    fun artworkUrl(raw: String?, size: Int = ARTWORK_SIZE): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        val want = size.coerceIn(1, MAX_ARTWORK_SIZE)
        val found = SIZE_SEGMENT.find(url) ?: return url
        return url.replaceRange(found.range, "/${want}x${want}bb.${found.groupValues[3]}")
    }

    /** 解析搜索响应。解析不出来、或结构不对，一律返回空列表。 */
    fun parseSearch(body: String): List<ITunesHit> {
        val root = MiniJson.parse(body) ?: return emptyList()
        return root.array("results").mapNotNull { node ->
            val kind = node.str("kind")
            // entity=song 时理论上只回歌曲；kind 明确不是 song 的（music-video 之类）丢掉，
            // 它们的封面是视频封面，贴到歌曲上是错的
            if (kind != null && kind != "song") return@mapNotNull null
            val title = node.str("trackName")?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val trackId = node.long("trackId") ?: return@mapNotNull null
            ITunesHit(
                trackId = trackId,
                collectionId = node.long("collectionId") ?: 0L,
                title = title,
                artist = node.str("artistName")?.trim().orEmpty(),
                album = node.str("collectionName")?.trim().orEmpty(),
                artworkUrl100 = node.str("artworkUrl100")?.takeIf { it.isNotBlank() },
                durationMs = node.long("trackTimeMillis") ?: 0L,
                releaseDate = node.str("releaseDate"),
                genre = node.str("primaryGenreName")
            )
        }
    }

    /**
     * 从搜索结果里挑出最可能是同一首歌的一条，低于 [minScore] 认为「没匹配上」。
     *
     * 拿不准时**返回 null 比返回错的更有价值**：封面错了用户会以为是标签写错了，
     * 而去翻自己的文件，比"暂时没有封面"麻烦得多。
     *
     * @param minScore 门槛，默认 [MIN_SCORE]。设置页可调（「匹配评分下限」），
     *                 调低更容易匹配上、也更容易贴错；调高宁可不给。
     */
    fun pickBest(
        candidates: List<ITunesHit>,
        title: String,
        artist: String?,
        durationMs: Long,
        minScore: Int = MIN_SCORE
    ): ITunesHit? = candidates
        .map { it to score(it, title, artist, durationMs) }
        .filter { it.second >= minScore }
        .maxWithOrNull(
            // `thenByDescending` 与 `maxWith` 组合起来是**反的**：`maxWith` 取"最大"，
            // 而 descending 把小的当成大的，所以选择器本身写"越小越优先"就对了。
            //
            // 这里原先写成 `-durationGap(...)` / `-trackId`（读起来像"取更大的"），
            // 实际效果是**优先时长差最大的那一版**、同分取 id 最大的。
            // 危险在于它照样能返回一条 Yesterday，肉眼看不出错 ——
            // 只有夹具里那两条 0ms / 347ms 之差的候选能把它揭穿。
            compareBy<Pair<ITunesHit, Int>> { it.second }
                // 同分时优先时长更接近的 —— 同一首歌的专辑版/单曲版差别往往只有几秒
                .thenByDescending { durationGap(it.first, durationMs) }
                // 再同分取 id 小的，保证同一份输入永远给出同一个结果（可测）
                .thenByDescending { it.first.trackId }
        )
        ?.first

    /**
     * 打分。满分 [MAX_SCORE]，[MIN_SCORE] = 90 大致对应
     * 「歌名对得上 + 歌手或时长至少对上一个」。
     *
     * [title] / [artist] 传**原始标签值**，不要先 [TextMatch.cleanTitle]：
     * 版本惩罚要靠原始标题判断"用户本来要的就是 Live 版吗"，
     * 清洗过的标题里 `live` 已经被抹掉了，会把自己想要的版本也罚下去。
     */
    fun score(candidate: ITunesHit, title: String, artist: String?, durationMs: Long): Int {
        var score = 0

        val wantTitle = TextMatch.normalize(TextMatch.cleanTitle(title))
        val gotTitle = TextMatch.normalize(candidate.title)
        score += when {
            wantTitle.isEmpty() -> 0
            gotTitle == wantTitle -> 100
            gotTitle.contains(wantTitle) -> 55
            TextMatch.tokenOverlap(wantTitle, gotTitle) >= 24 -> 20
            else -> -60
        }

        val wantArtist = TextMatch.normalize(artist.orEmpty())
        if (wantArtist.isNotEmpty()) {
            // 合作曲的 artistName 常是 "A & B" / "A feat. B"，逐个比
            val parts = candidate.artist.split('&', ',', '/', '、', '，', '&')
                .map { TextMatch.normalize(it) }
                .filter { it.isNotEmpty() }
            val hit = parts.any { it == wantArtist || it.contains(wantArtist) || wantArtist.contains(it) }
            score += if (hit) 40 else -20
        }

        if (durationMs > 0L && candidate.durationMs > 0L) {
            score += when (val gap = durationGap(candidate, durationMs)) {
                in 0..3_000 -> 30
                in 0..10_000 -> 15
                in 0..25_000 -> 0
                else -> -40
            }
        }

        score += versionPenalty(title, candidate.title)
        return score
    }

    /** 两个条目是否是「同一张专辑的同一首」，用于避免重复下载同一张封面。 */
    fun sameArtwork(a: ITunesHit?, b: ITunesHit?): Boolean {
        if (a == null || b == null) return false
        return a.collectionId != 0L && a.collectionId == b.collectionId
    }

    // ------------------------------------------------------------------ 内部

    private fun durationGap(candidate: ITunesHit, durationMs: Long): Long =
        if (durationMs <= 0L || candidate.durationMs <= 0L) {
            0L
        } else {
            kotlin.math.abs(durationMs - candidate.durationMs)
        }

    /**
     * 版本词惩罚。
     *
     * 同一首歌在 Apple 的目录里往往有几十个条目：专辑版、单曲版、豪华版、Live、
     * Remix、伴奏、KTV……`limit=1` 只拿相关性最高的一条，很容易拿到现场版。
     * 规则是**只要用户自己要的标题里没有这个词，就给候选扣分**；
     * 用户标题里本来就带 `Live` 时不扣，这样他想要的版本才有机会胜出。
     */
    private fun versionPenalty(wantRaw: String, gotRaw: String): Int {
        val want = wantRaw.lowercase()
        val got = gotRaw.lowercase()
        var penalty = 0
        for ((pattern, cost) in VERSION_WORDS) {
            if (pattern.containsMatchIn(got) && !pattern.containsMatchIn(want)) penalty += cost
        }
        return penalty
    }

    /**
     * 版本词表。按「有多不想拿到这一版」分档，不是所有版本都一样坏：
     * 重制版通常可以接受，伴奏/KTV 版则完全不是同一件事。
     */
    private val VERSION_WORDS: List<Pair<Regex, Int>> = listOf(
        Regex("""(?i)\b(karaoke|instrumental|backing track|sing[- ]?along)\b""") to -70,
        Regex("(伴奏|纯音乐|卡拉|伴唱)") to -70,
        Regex("""(?i)\b(cover|tribute|in the style of)\b""") to -50,
        Regex("(翻唱|模仿秀)") to -50,
        Regex("""(?i)\b(sped up|slowed|nightcore|8d audio|reverb)\b""") to -60,
        Regex("""(?i)\b(live|concert|unplugged|session|tour)\b""") to -45,
        Regex("(现场|演唱会|巡演)") to -45,
        Regex("""(?i)\b(remix|mashup|bootleg|flip)\b""") to -45,
        Regex("(混音|串烧|铃声|抖音)") to -45,
        Regex("""(?i)\b(demo|rough mix|alternate take|rehearsal)\b""") to -30,
        // 重制/豪华/纪念版音乐本身是同一版录音，只有轻微偏好差异
        Regex("""(?i)\b(remaster(ed)?|deluxe|anniversary|expanded)\b""") to -8
    )

    private fun encode(raw: String): String = runCatching {
        URLEncoder.encode(raw, "UTF-8")
    }.getOrDefault(raw)
}
