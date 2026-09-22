package com.melody.player.data

import com.melody.player.BuildConfig
import com.melody.player.core.online.LrcLibApi
import com.melody.player.core.online.LyricMatch
import com.melody.player.core.online.OnlineLyric
import com.melody.player.core.online.OnlineSong
import com.melody.player.core.online.TextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LRCLIB（https://lrclib.net）的取数层。
 *
 * 与网易云那个客户端相比，这里的规矩多两条，都是**接口方明确要求**的，不是可选项：
 *
 *  - **必须**在 `User-Agent` 里表明身份（应用名 + 版本 + 联系方式）。带版本号是有意的：
 *    对面看日志时能分清是哪一版在打请求；版本号直接取 [BuildConfig.VERSION_NAME]，
 *    不会随版本迭代过期。
 *  - **必须**遵守 429 的 `Retry-After`，并且请求之间要有间隔（文档建议 200–500ms）。
 *    无视它的后果是被临时封禁 —— 那不是"歌词取不到"这种小事，是整个来源废掉。
 *
 * 因为歌词是随响应一起返回的（[OnlineSong.inlineLyrics]），正常路径下**一次请求就能拿到词**，
 * 只有万不得已（记录里没带正文）才会按 id 再取一次。
 *
 * 任何异常都吞掉返回空值：联网取歌词是锦上添花，绝不能因此影响播放。
 */
class LrcLibClient {

    /**
     * 按签名查一首歌：歌名 + 歌手 +（可选）专辑 + 时长。
     *
     * 时长带上之后服务端会挑最接近的那个录音版本；歌名一旦查不到，会再用
     * 「剥掉括号附属信息」的歌名试一次 —— 本地文件的歌名常带 `(Live)`、`【320K】`
     * 这类尾巴，而对面库里存的是干净版本。
     */
    suspend fun lookup(
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long
    ): OnlineSong? = withContext(Dispatchers.IO) {
        val usableArtist = LrcLibApi.usableArtist(artist)
        if (usableArtist != null) {
            fetchGet(title, usableArtist, album, durationMs)?.let { return@withContext it }
            val cleaned = TextMatch.cleanTitle(title)
            if (cleaned.isNotEmpty() && cleaned != title.trim()) {
                fetchGet(cleaned, usableArtist, album, durationMs)?.let { return@withContext it }
            }
            return@withContext null
        }

        // 本地标签根本没写歌手（接口不接这种输入），退化成只按歌名搜。
        // 少了歌手这一路强信号，就必须靠 [LyricMatch] 把时长也算进来，否则同名歌会串。
        val url = LrcLibApi.searchBySignatureUrl(title, null) ?: return@withContext null
        val records = request(url)?.let { LrcLibApi.parseSearch(it.body) }.orEmpty()
        LyricMatch.pickBest(records, title, null, durationMs)
    }

    /**
     * 按歌名 + 歌手搜候选。
     *
     * 先用结构化参数（`track_name` / `artist_name`，对面按字段匹配，命中率高）；
     * 一条都没有时再用关键词 `q` 兜一次 —— 实测有些中文曲目只在关键词搜索里能被召回。
     */
    suspend fun search(title: String, artist: String?): List<OnlineSong> = withContext(Dispatchers.IO) {
        val cleaned = TextMatch.cleanTitle(title).ifEmpty { title.trim() }
        val structured = LrcLibApi.searchBySignatureUrl(cleaned, artist)
            ?.let { request(it)?.let { resp -> LrcLibApi.parseSearch(resp.body) } }
            .orEmpty()
        if (structured.isNotEmpty()) return@withContext structured

        val keyword = TextMatch.searchKeyword(title, artist)
        if (keyword.isEmpty()) return@withContext emptyList()
        val loose = LrcLibApi.searchByKeywordUrl(keyword)
            ?.let { request(it)?.let { resp -> LrcLibApi.parseSearch(resp.body) } }
            .orEmpty()
        // 同一个 id 可能同时出现在两批结果里，按 id 去重
        (structured + loose).distinctBy { it.id }
    }

    /** 取一条候选的歌词；正文随搜索结果一起来了就直接用，没带才按 id 再取。 */
    suspend fun lyricsFor(candidate: OnlineSong): OnlineLyric? = withContext(Dispatchers.IO) {
        candidate.inlineLyrics?.takeIf { it.isNotBlank() }?.let {
            return@withContext OnlineLyric(song = candidate, lrcText = it, hasTranslation = false)
        }
        val url = LrcLibApi.byIdUrl(candidate.id) ?: return@withContext null
        val record = request(url)?.let { LrcLibApi.parseGet(it.body) } ?: return@withContext null
        val text = record.inlineLyrics?.takeIf { it.isNotBlank() } ?: return@withContext null
        OnlineLyric(song = record, lrcText = text, hasTranslation = false)
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun fetchGet(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long
    ): OnlineSong? {
        val url = LrcLibApi.lookupUrl(title, artist, album, durationMs) ?: return null
        val response = request(url) ?: return null
        // 404 = 对面库里没有这首，属于正常结果，不当错误处理
        if (response.code == 404) return null
        if (!response.isSuccess) return null
        return LrcLibApi.parseGet(response.body)
    }

    /**
     * 发一个请求，顺带执行限流纪律。
     *
     * 两道闸：被限流罚过之后在解禁时间前一个包都不发；两次请求之间至少隔
     * [MIN_REQUEST_GAP_MS]（文档建议 200–500ms，取下限，弱网下也别排太久）。
     *
     * 解禁时间只在两种情况更新：429（文档规定的限流响应），或者响应里带了
     * `Retry-After`。后者是有意的 —— 实测查一首库里没有的歌会偶发 503
     * （来自前置的 Cloudflare，而不是这个接口本身），它也可能带上这个头，
     * 服务器让等就等，别去撞。
     */
    private suspend fun request(url: String): SimpleHttp.Response? {
        val now = System.currentTimeMillis()
        if (now < blockedUntilMs) return null
        val wait = lastRequestAtMs + MIN_REQUEST_GAP_MS - now
        if (wait > 0L) Thread.sleep(wait)
        lastRequestAtMs = System.currentTimeMillis()

        val response = SimpleHttp.get(url, headers()) ?: return null
        val retryAfter = response.header("Retry-After")
        if (response.code == 429 || retryAfter != null) {
            blockedUntilMs = System.currentTimeMillis() + LrcLibApi.retryAfterMs(retryAfter, now)
        }
        return response
    }

    private fun headers(): Map<String, String> = mapOf("User-Agent" to USER_AGENT)

    private companion object {
        /**
         * 接口要求表明客户端身份：应用名 + 版本 + 联系方式。
         * 本 App 没有主页，用包名当作可追溯的标识。
         */
        val USER_AGENT: String = "MelodyPlayer v${BuildConfig.VERSION_NAME} (com.melody.player)"

        const val MIN_REQUEST_GAP_MS = 250L

        /** 被限流后的解禁时刻（毫秒时间戳）。跨实例共享：限流是按来源 IP 算的，不是按对象。 */
        @Volatile
        var blockedUntilMs: Long = 0L

        @Volatile
        var lastRequestAtMs: Long = 0L
    }
}
