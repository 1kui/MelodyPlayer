package com.melody.player.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 两个在线歌词接口共用的那一层 HTTP。
 *
 * 抽出来是因为网易云与 LRCLIB 要处理的是**同一套麻烦**：主动声明 gzip 就得自己解压
 * （`HttpURLConnection` 不会替你解）、必须设超时否则弱网下会挂住播放线程、响应要封顶
 * （对面返回了意料之外的大东西时不能把内存吃光）。各写一份的下场是其中一份漏掉某条，
 * 而这种漏在真机上表现为「偶尔拿不到歌词」，极难复现。
 *
 * 任何异常一律吞掉返回 null：联网取歌词是锦上添花，绝不能因此影响播放。
 */
internal object SimpleHttp {

    class Response(
        val code: Int,
        val body: String,
        private val headers: Map<String, List<String>>
    ) {
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

        val isSuccess: Boolean get() = code in 200..299
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val code = connection.responseCode
            val gzipped = connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
            // 响应头先取下来：像 Retry-After 这种只出现在错误响应里的头，
            // 一旦因为「没有 body」提前返回就会丢掉，调用方再也没机会遵守它
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
            // 错误体也要读：LRCLIB 的 4xx 会带上 name（如 ValidationError），排查时比状态码有用
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = if (stream == null) {
                ""
            } else {
                stream.let { if (gzipped) GZIPInputStream(it) else it }.use { readCapped(it) }
            }
            Response(code, body, headers)
        } finally {
            runCatching { connection.disconnect() }
        }
    }.getOrNull()

    /** 读满上限就停：正常响应几十 KB，超出说明对面返回了意料之外的东西。 */
    private fun readCapped(stream: InputStream): String {
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_BODY_BYTES) break
            sink.write(buffer, 0, read)
        }
        return sink.toString("UTF-8")
    }

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
}
