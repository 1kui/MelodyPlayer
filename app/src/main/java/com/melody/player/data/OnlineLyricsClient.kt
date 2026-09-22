package com.melody.player.data

import com.melody.player.core.online.NetEaseApi
import com.melody.player.core.online.OnlineLyric
import com.melody.player.core.online.OnlineSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * 网易云歌词接口的取数层。
 *
 * 用 [java.net.HttpURLConnection]（见 [SimpleHttp]）而不是 OkHttp：本机 Gradle 缓存里没有
 * OkHttp，而这里只需要两个 GET，标准库足够了，也少一份依赖要维护。
 *
 * 几个必须照做的细节（不做就会拿到空数据或 `code: -460 Cheating`）：
 *  - 带 `Referer: https://music.163.com/` 与一个常见的桌面 UA
 *  - 带 `Cookie: appver=2.0.2` —— 接口对「看起来不像网页」的请求会直接拒绝
 *  - 主动声明 `Accept-Encoding: gzip` 并自己解压（`HttpURLConnection` 不会替你解，
 *    这一步在 [SimpleHttp] 里统一做了）
 *
 * 任何异常都吞掉并返回空值：联网取歌词是锦上添花，绝不能因此影响播放。
 */
class OnlineLyricsClient {

    /** 按关键词搜歌。返回空列表表示没搜到或网络不可用。 */
    suspend fun search(keyword: String, limit: Int = SEARCH_LIMIT): List<OnlineSong> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()
            val url = "$BASE${NetEaseApi.SEARCH_HOST_PATH}?s=${encode(keyword)}&type=1&limit=$limit"
            val body = get(url) ?: return@withContext emptyList()
            NetEaseApi.parseSearch(body)
        }

    /** 取一首歌的歌词（原文 + 翻译合并成一份 LRC）。 */
    suspend fun lyricsFor(song: OnlineSong): OnlineLyric? = withContext(Dispatchers.IO) {
        val url = "$BASE${NetEaseApi.LYRIC_HOST_PATH}?os=pc&id=${song.id}&lv=-1&tv=-1"
        val body = get(url) ?: return@withContext null
        NetEaseApi.parseLyric(body, song)
    }

    // ------------------------------------------------------------------ 内部

    private fun encode(raw: String): String = runCatching {
        URLEncoder.encode(raw, "UTF-8")
    }.getOrDefault(raw)

    private fun get(url: String): String? {
        val response = SimpleHttp.get(url, HEADERS) ?: return null
        return if (response.isSuccess) response.body else null
    }

    private companion object {
        const val BASE = "https://music.163.com"
        const val REFERER = "https://music.163.com/"
        const val COOKIE = "appver=2.0.2"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Safari/537.36"
        const val SEARCH_LIMIT = 8

        val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to REFERER,
            "Cookie" to COOKIE
        )
    }
}
