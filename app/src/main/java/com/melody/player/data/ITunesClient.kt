package com.melody.player.data

import com.melody.player.core.online.ITunesApi
import com.melody.player.core.online.ITunesHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 一次成功的封面匹配：命中的条目 + 已经提升过尺寸的封面地址。 */
data class CoverMatch(val hit: ITunesHit, val artworkUrl: String)

/**
 * iTunes Search API 的取数层。
 *
 * 用 [HttpURLConnection] 与本工程的其它联网代码保持一致（歌词那边也是这么写的），
 * 不引 OkHttp。任何异常都吞掉并返回 null/空：取封面是锦上添花，绝不能影响播放。
 *
 * ## 限流是**必须**的
 * Apple 这个接口没有公开的配额，但实测同一个出口 IP 上高频调用很快会被挡（返回 403）。
 * 这里做两件事：
 *  1. 两次 **API** 调用之间至少隔 [MIN_INTERVAL_MS]（≈20 次/分钟），用 [Mutex] 串行化；
 *  2. 一旦收到 403/429，整体冷却 [BLOCK_COOLDOWN_MS]，期间不再发请求（而不是继续撞墙，
 *     那样只会让封禁时间变长）。
 *
 * 注意**只节流 API**，不节流封面图片下载：图片走的是 `*.mzstatic.com` 的 CDN，
 * 与 Search API 不是一回事，把它一起卡住只会让批量补封面慢得离谱。
 */
class ITunesClient(private val clock: () -> Long = System::currentTimeMillis) {

    private val gate = Mutex()
    private var nextAllowedAtMs = 0L
    private var blockedUntilMs = 0L

    /**
     * 按「歌名 + 歌手 + 时长」找一张封面。
     *
     * 回退链默认是 [ITunesApi.COUNTRIES]（TW → HK → US，**不含 CN**：大陆区没有音乐目录，
     * 实测固定返回 0 条）；用户在设置里自定义了地区就按他选的顺序来（CoverRegion 里
     * 本来就没有 CN）。某个地区能搜到但挑不出够像的一条时会继续往下试 ——
     * 同一个歌名在不同地区的曲库不一样，TW 有卡拉OK 版而 US 有原版是很常见的。
     *
     * @param minScore 匹配门槛，来自设置页；调高会更频繁地"宁可不给"。
     * @param countries 实际搜索的地区 code 顺序。
     */
    suspend fun findCover(
        title: String,
        artist: String?,
        durationMs: Long,
        keyword: String,
        minScore: Int = ITunesApi.MIN_SCORE,
        countries: List<String> = ITunesApi.COUNTRIES
    ): CoverMatch? {
        if (keyword.isBlank()) return null
        for (country in countries) {
            val hits = search(keyword, country) ?: continue
            if (hits.isEmpty()) continue
            val best = ITunesApi.pickBest(hits, title, artist, durationMs, minScore) ?: continue
            val url = ITunesApi.artworkUrl(best.artworkUrl100) ?: continue
            return CoverMatch(best, url)
        }
        return null
    }

    suspend fun search(keyword: String, country: String): List<ITunesHit>? {
        val body = apiGet("${ITunesApi.SEARCH_PATH}?${ITunesApi.searchQuery(keyword, country)}") ?: return null
        return ITunesApi.parseSearch(body)
    }

    /**
     * 测一个地区的连接延迟（毫秒）；连不上返回 null。
     *
     * ## 为什么不走 [awaitTurn] 的 3 秒节流
     * 那道闸门是给"批量取封面"守额度用的（20 次/分钟）。套在这里，8 个地区测一轮
     * 要 24 秒 —— 用户点一下得等半分钟，这个功能就等于不存在。
     * 代价是短时间内会连发几个请求，所以两头都收着：
     *  - 调用方（ViewModel）在两次探测之间留一小段间隔；
     *  - 这里仍然读 [blockedUntilMs]，正在冷却就**直接返回 null**，不再去撞那堵墙。
     *
     * 判"通"的标准是拿到 2xx，而不是"解析出内容"：目的是量这一路的往返时延，
     * 不是验证搜索结果。所以关键词用一个必然有结果的词，只看响应码。
     *
     * 超时也比正常请求短（[PROBE_CONNECT_TIMEOUT_MS]）：测速最怕"卡住不动"，
     * 一个不通的地区占着 12 秒会让整轮测速迟迟出不来结果。
     */
    suspend fun probe(country: String): Long? {
        if (clock() < blockedUntilMs) return null
        val started = clock()
        val (body, code) = withContext(Dispatchers.IO) {
            fetchText(
                "$BASE${ITunesApi.SEARCH_PATH}?${ITunesApi.searchQuery(PROBE_KEYWORD, country, limit = 1)}",
                connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS,
                readTimeoutMs = PROBE_READ_TIMEOUT_MS
            )
        }
        if (code == 403 || code == 429) {
            // 被挡了：整体冷却（与正常请求共用同一个冷却位），并如实报"连不上"
            blockedUntilMs = clock() + BLOCK_COOLDOWN_MS
            return null
        }
        if (code !in 200..299 || body == null) return null
        return clock() - started
    }

    /** 取封面图片原始字节。失败返回 null（调用方据此保留原状，而不是清掉已有封面）。 */
    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/*")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use { readCapped(it, MAX_IMAGE_BYTES) }
            } finally {
                runCatching { connection.disconnect() }
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun apiGet(path: String): String? {
        if (!awaitTurn()) return null
        val url = "$BASE$path"
        val (body, code) = withContext(Dispatchers.IO) { fetchText(url) }
        if (code == 403 || code == 429) {
            // 被封了：整体冷却，继续撞只会延长封禁
            blockedUntilMs = clock() + BLOCK_COOLDOWN_MS
            return null
        }
        return body
    }

    /**
     * 串行 + 最小间隔。放在锁里 `delay` 是安全的：Mutex 是协程感知的，不会阻塞线程。
     *
     * @return false 表示正处于被封的冷却期，本次直接放弃（不发请求）。
     */
    private suspend fun awaitTurn(): Boolean = gate.withLock {
        val now = clock()
        if (now < blockedUntilMs) return@withLock false
        val wait = nextAllowedAtMs - now
        if (wait > 0) delay(wait)
        nextAllowedAtMs = clock() + MIN_INTERVAL_MS
        true
    }

    private fun fetchText(
        url: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Pair<String?, Int> = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) return@runCatching null to code
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding?.contains("gzip", true) == true) GZIPInputStream(raw) else raw
            stream.use { readCapped(it, MAX_BODY_BYTES)?.toString(Charsets.UTF_8) } to code
        } finally {
            runCatching { connection.disconnect() }
        }
    }.getOrElse { null to -1 }

    /** 读满上限就停：正常响应/图片都在几百 KB 内，超出说明对面返回了意料之外的东西。 */
    private fun readCapped(stream: java.io.InputStream, limit: Int): ByteArray? {
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            sink.write(buffer, 0, read)
        }
        return sink.toByteArray()
    }

    private companion object {
        const val BASE = "https://itunes.apple.com"
        const val USER_AGENT = "MelodyPlayer/1.9 (Android)"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_BODY_BYTES = 1 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

        /** 20 次/分钟。 */
        const val MIN_INTERVAL_MS = 3_000L

        /** 被挡之后的冷却时长。 */
        const val BLOCK_COOLDOWN_MS = 60_000L

        /** 测速用的关键词：只要"这个请求能不能通"，用一个必然有结果的词。 */
        const val PROBE_KEYWORD = "test"

        /** 测速的超时。比正常请求短：卡住不动的地区要让它在几秒内失败，不能拖住整轮测速。 */
        const val PROBE_CONNECT_TIMEOUT_MS = 4_000
        const val PROBE_READ_TIMEOUT_MS = 5_000
    }
}
