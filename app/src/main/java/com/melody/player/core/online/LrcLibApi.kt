package com.melody.player.core.online

import java.net.URLEncoder
import kotlin.math.roundToLong

/**
 * LRCLIB（https://lrclib.net）歌词接口的解析与地址构造。
 *
 * 与 [NetEaseApi] 一样全是纯函数：接口形状里有一堆只在真机上才会撞上的坑，
 * 放在这里才能用单测钉住（下面每条都是照着真实响应写下来的，不是照抄文档）。
 *
 * ## 接口
 *  - 签名查询 `GET /api/get?track_name=&artist_name=&album_name=&duration=`
 *    （`track_name`、`artist_name` 必填，时长按秒、只接受 1..3600）
 *  - 按 id 取词 `GET /api/get/{id}`
 *  - 关键词搜索 `GET /api/search?q=` 或 `GET /api/search?track_name=[&artist_name=]`
 *    （最多 20 条、不支持翻页；`q` 与 `track_name` 至少要有一个，否则返回空数组）
 *
 * ## 实测与文档不一致、必须照实测来的几处
 *  1. `duration` 是**浮点数**（`233.0`，甚至有 `299.360658`）；直接按整数读会踩空。
 *  2. 传 `duration` 超范围（如 9999）不是 4xx 里的「没找到」，而是
 *     `400 {"name":"ValidationError"}` —— 所以时长未知或超过 1 小时时**必须不带这个参数**，
 *     否则整个请求直接失败。
 *  3. 没找到时 404 的 body 是 `{"message":…,"name":"TrackNotFound","statusCode":404}`：
 *     **没有 `code` 字段**，判断成败只能看 HTTP 状态码。
 *  4. 每条记录都带 `lyricsfile`（YAML 原文），本 App 用不上，不解析。
 *  5. 伴奏（`instrumental=true`）记录的 `plainLyrics` 与 `syncedLyrics` **都是 null** ——
 *     这类记录没有任何可显示的内容，解析阶段就丢掉。
 *  6. 文档说「时长差 ±2 秒内才给歌词」，实测**不成立**：点一首 233 秒的歌传 245 秒，
 *     照样 200 返回那条 233 秒的（服务端匹配不上时长时会退回「歌名 + 歌手」兜底）。
 *     所以拿到结果之后仍要过一遍 [LyricMatch.pickBest]，不能因为「接口给了」就当作对上了。
 */
object LrcLibApi {

    const val BASE = "https://lrclib.net"
    const val GET_PATH = "/api/get"
    const val SEARCH_PATH = "/api/search"

    /** 接口对 `duration` 的硬性范围（秒）。 */
    const val MIN_DURATION_SECONDS = 1L
    const val MAX_DURATION_SECONDS = 3600L

    /** 429 没给（或给不出）Retry-After 时的兜底等待。 */
    const val RETRY_AFTER_FALLBACK_MS = 60_000L

    // ---------------------------------------------------------------- 地址构造

    /** 带签名的精确查询地址；缺必填项（歌名/歌手）时返回 null，调用方据此别发这个请求。 */
    fun lookupUrl(title: String, artist: String?, album: String?, durationMs: Long): String? {
        val t = title.trim()
        val a = usableArtist(artist) ?: return null
        if (t.isEmpty()) return null
        return buildString {
            append(BASE).append(GET_PATH).append('?')
            append("track_name=").append(encode(t))
            append("&artist_name=").append(encode(a))
            album?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("&album_name=").append(encode(it))
            }
            durationSecondsOrNull(durationMs)?.let { append("&duration=").append(it) }
        }
    }

    fun byIdUrl(id: Long): String? = if (id > 0L) "$BASE$GET_PATH/$id" else null

    /** 结构化搜索（歌手可省略 —— 接口允许只给 `track_name`）。 */
    fun searchBySignatureUrl(title: String, artist: String?): String? {
        val t = title.trim()
        if (t.isEmpty()) return null
        return buildString {
            append(BASE).append(SEARCH_PATH).append('?')
            append("track_name=").append(encode(t))
            usableArtist(artist)?.let { append("&artist_name=").append(encode(it)) }
        }
    }

    /** 关键词搜索：歌名、歌手、专辑任一字段里含这个词就能命中。 */
    fun searchByKeywordUrl(keyword: String): String? {
        val k = keyword.trim()
        if (k.isEmpty()) return null
        return "$BASE$SEARCH_PATH?q=${encode(k)}"
    }

    // ---------------------------------------------------------------- 解析

    /** 解析 `/api/get` 的单条响应；不是歌词记录（含 404 的错误体）返回 null。 */
    fun parseGet(body: String): OnlineSong? = parseRecord(MiniJson.parse(body))

    /** 解析 `/api/search` 的数组响应；结构不符时返回空列表。 */
    fun parseSearch(body: String): List<OnlineSong> {
        val array = MiniJson.parse(body) as? JsonValue.Arr ?: return emptyList()
        return array.items.mapNotNull { parseRecord(it) }
    }

    /** 把一条 LRCLIB 记录收敛成 [OnlineSong]；没有任何歌词内容的记录直接丢掉。 */
    fun parseRecord(node: JsonValue?): OnlineSong? {
        val obj = node as? JsonValue.Obj ?: return null
        // name 是 trackName 的别名，两者都可能缺席
        val title = (obj.str("trackName") ?: obj.str("name"))?.trim().orEmpty()
        if (title.isEmpty()) return null

        val lyrics = lyricsOf(node) ?: return null
        val artist = obj.str("artistName")?.trim().orEmpty()
        // 时长是浮点秒；四舍五入到毫秒，0.5 秒的偏差不值得留着
        val durationMs = obj.num("duration")?.let { (it * 1000.0).roundToLong() } ?: 0L

        return OnlineSong(
            id = obj.long("id") ?: 0L,
            title = title,
            artists = if (artist.isEmpty()) emptyList() else listOf(artist),
            album = obj.str("albumName")?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = durationMs,
            provider = LyricProvider.LRCLIB,
            inlineLyrics = lyrics,
            synced = obj.str("syncedLyrics")?.isNotBlank() == true
        )
    }

    /**
     * 取这条记录的歌词正文：**带时间轴的优先**（`syncedLyrics`），否则退回纯文本
     * （`plainLyrics`，交给上层按时长自动对齐并如实标注）。两者都空说明是伴奏记录。
     */
    fun lyricsOf(node: JsonValue?): String? {
        val synced = node.str("syncedLyrics")?.takeIf { it.isNotBlank() }
        if (synced != null) return synced.trim()
        return node.str("plainLyrics")?.takeIf { it.isNotBlank() }?.trim()
    }

    // ---------------------------------------------------------------- 时长与限流

    /**
     * 本地时长 → 接口要的秒数。
     *
     * 未知（0 或负数）与超出 1..3600 都返回 null 表示"这个参数别带"：带了会被判
     * `ValidationError` 直接失败，而不带时接口仍会按歌名+歌手尽量匹配。
     */
    fun durationSecondsOrNull(durationMs: Long): String? {
        if (durationMs <= 0L) return null
        val seconds = (durationMs / 1000.0).roundToLong()
        if (seconds < MIN_DURATION_SECONDS || seconds > MAX_DURATION_SECONDS) return null
        return seconds.toString()
    }

    /**
     * 解析 429 响应的 `Retry-After`（秒）为等待毫秒数。
     *
     * 文档明确要求客户端**必须**遵守它，无视会被临时封禁。非数字（HTTP 日期格式等）
     * 或缺失时退回 [RETRY_AFTER_FALLBACK_MS]，宁可多等也不去撞。
     */
    fun retryAfterMs(header: String?, nowMs: Long): Long {
        val seconds = header?.trim()?.toLongOrNull() ?: return RETRY_AFTER_FALLBACK_MS
        if (seconds <= 0L) return RETRY_AFTER_FALLBACK_MS
        // 上限一天，防止对面给个离谱的值把客户端永久锁死
        return (seconds.coerceAtMost(86_400L)) * 1000L
    }

    /** 歌手字段可用吗：空、`<unknown>`、以「未知」开头都当作没有。 */
    fun usableArtist(artist: String?): String? {
        val a = artist?.trim().orEmpty()
        if (a.isEmpty()) return null
        if (a.equals("<unknown>", ignoreCase = true)) return null
        if (a.startsWith("未知")) return null
        return a
    }

    private fun encode(raw: String): String = runCatching {
        URLEncoder.encode(raw, "UTF-8")
    }.getOrDefault(raw)
}
