package com.melody.player

import com.melody.player.core.LrcParser
import com.melody.player.core.LyricsSource
import com.melody.player.core.online.LrcLibApi
import com.melody.player.core.online.LyricMatch
import com.melody.player.core.online.LyricProvider
import com.melody.player.core.online.NetEaseApi
import com.melody.player.core.online.OnlineSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LRCLIB 响应解析、地址构造与限流纪律。
 *
 * 下面的载荷都是从真实接口抄回来的（`/api/get`、`/api/search`），**不是照文档编的**——
 * 文档与实测有几处不一致，正是这些不一致最容易把请求做废：
 * `duration` 是浮点秒、超范围会被判 `ValidationError` 而不是"没找到"、
 * 伴奏记录的两个歌词字段都是 null。把它们钉在单测里，改代码时不会悄悄退化。
 */
class LrcLibApiTest {

    /** 真实 `/api/get` 响应（节选：字段齐全，歌词截短）。 */
    private val getBody = """
        {"id":3396226,"name":"I Want to Live","trackName":"I Want to Live",
         "artistName":"Borislav Slavov","albumName":"Baldur's Gate 3 (Original Game Soundtrack)",
         "duration":233.0,"instrumental":false,
         "plainLyrics":"I feel your breath upon my neck\nA soft caress as cold as death\n",
         "syncedLyrics":"[00:17.12] I feel your breath upon my neck\n[00:20.41] A soft caress as cold as death\n",
         "lyricsfile":"version: '1.0'\nmetadata:\n  title: I Want to Live\n"}
    """.trimIndent()

    /** 真实 `/api/search` 响应：同一个歌名有好几个版本（时长不同），第三条是伴奏。 */
    private val searchBody = """
        [
         {"id":17788,"name":"晴天","trackName":"晴天","artistName":"周杰伦","albumName":"叶惠美",
          "duration":270.0,"instrumental":false,
          "plainLyrics":"故事的小黄花","syncedLyrics":"[00:00.00] 故事的小黄花\n",
          "lyricsfile":"version: '1.0'"},
         {"id":23053743,"name":"晴天","trackName":"晴天","artistName":"周杰伦","albumName":"",
          "duration":249.80898,"instrumental":false,
          "plainLyrics":"故事的小黄花","syncedLyrics":null,
          "lyricsfile":"version: '1.0'"},
         {"id":999,"name":"Durga (Instrumental)","trackName":"Durga (Instrumental)","artistName":"Someone",
          "albumName":null,"duration":200.0,"instrumental":true,
          "plainLyrics":null,"syncedLyrics":null,"lyricsfile":"version: '1.0'"}
        ]
    """.trimIndent()

    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析单条记录：字段映射与浮点时长`() {
        val song = LrcLibApi.parseGet(getBody)
        assertNotNull(song)
        assertEquals(3396226L, song!!.id)
        assertEquals("I Want to Live", song.title)
        assertEquals(listOf("Borislav Slavov"), song.artists)
        assertEquals("Baldur's Gate 3 (Original Game Soundtrack)", song.album)
        // duration 是浮点秒（233.0），毫秒换算不能丢精度也不要留小数
        assertEquals(233_000L, song.durationMs)
        assertEquals(LyricProvider.LRCLIB, song.provider)
        assertEquals(true, song.synced)
    }

    @Test
    fun `带时间轴的歌词优先，没有才退回纯文本`() {
        val withSynced = LrcLibApi.parseGet(getBody)!!
        assertTrue(withSynced.inlineLyrics!!.startsWith("[00:17.12]"))

        val plainOnly = LrcLibApi.parseSearch(searchBody)[1]
        assertEquals(false, plainOnly.synced)
        assertEquals("故事的小黄花", plainOnly.inlineLyrics)
    }

    @Test
    fun `同步歌词能被本地 LRC 解析器直接吃下`() {
        // 这是两套东西的接缝：LRCLIB 给的是 `[mm:ss.xx] 文本`（] 后面还有一个空格）
        val raw = LrcLibApi.parseGet(getBody)!!.inlineLyrics!!
        val parsed = LrcParser.parse(raw)
        assertEquals(2, parsed.lines.size)
        assertEquals(17_120L, parsed.lines[0].timeMs)
        assertEquals("I feel your breath upon my neck", parsed.lines[0].text)
        assertEquals(20_410L, parsed.lines[1].timeMs)
    }

    @Test
    fun `伴奏记录没有任何歌词，直接丢掉`() {
        val records = LrcLibApi.parseSearch(searchBody)
        assertEquals(listOf(17788L, 23053743L), records.map { it.id })
        assertTrue(records.none { it.title.contains("Durga") })

        val instrumental = """{"id":1,"trackName":"Durga (Instrumental)","artistName":"X",
            "duration":200.0,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""
        assertNull(LrcLibApi.parseGet(instrumental))
    }

    @Test
    fun `时长带小数时保留到毫秒`() {
        // 实测里出现过 duration=249.80898 这种值
        assertEquals(249_809L, LrcLibApi.parseSearch(searchBody)[1].durationMs)
    }

    @Test
    fun `没有歌名的记录丢掉，没有 id 的仍然可用`() {
        val body = """[{"id":5,"plainLyrics":"有词没歌名","duration":100.0},
                       {"id":6,"trackName":"有歌名","plainLyrics":"正文","duration":100.0},
                       {"id":7,"trackName":"只有歌词文件字段","duration":100.0},
                       {"trackName":"没有id","plainLyrics":"正文","duration":100.0}]"""
        val records = LrcLibApi.parseSearch(body)
        assertEquals(listOf("有歌名", "没有id"), records.map { it.title })
        // 没 id 不影响展示与选用（歌词是随搜索结果一起回来的），只是不能再按 id 重取
        assertEquals(0L, records[1].id)
        assertNull(LrcLibApi.byIdUrl(records[1].id))
    }

    @Test
    fun `解析失败与错误体都不抛异常`() {
        // 404 的错误体：注意它**没有 code 字段**（文档里写的是有），只有 name/statusCode
        assertNull(LrcLibApi.parseGet("""{"message":"Failed to find specified track","name":"TrackNotFound","statusCode":404}"""))
        assertNull(LrcLibApi.parseGet("""{"message":"duration: must be between 1 and 3600","name":"ValidationError","statusCode":400}"""))
        assertNull(LrcLibApi.parseGet("not json"))
        assertNull(LrcLibApi.parseGet(""))
        assertEquals(emptyList<OnlineSong>(), LrcLibApi.parseSearch("[]"))
        assertEquals(emptyList<OnlineSong>(), LrcLibApi.parseSearch("<html>502</html>"))
    }

    // ---------------------------------------------------------------- 地址构造

    @Test
    fun `签名查询带上歌名 歌手 专辑与时长`() {
        val url = LrcLibApi.lookupUrl("晴天", "周杰伦", "叶惠美", 269_400L)!!
        assertTrue(url.startsWith("https://lrclib.net/api/get?"))
        assertTrue(url.contains("track_name=%E6%99%B4%E5%A4%A9"))
        assertTrue(url.contains("artist_name=%E5%91%A8%E6%9D%B0%E4%BC%A6"))
        assertTrue(url.contains("album_name=%E5%8F%B6%E6%83%A0%E7%BE%8E"))
        assertTrue(url.contains("duration=269"))
    }

    @Test
    fun `时长在非法范围时宁可不带这个参数`() {
        // 实测传 9999 秒会被判 ValidationError（整个请求失败），而不是"没找到"
        assertNull(LrcLibApi.durationSecondsOrNull(0L))
        assertNull(LrcLibApi.durationSecondsOrNull(-1000L))
        assertNull(LrcLibApi.durationSecondsOrNull(3_700_000L))
        assertNull(LrcLibApi.durationSecondsOrNull(99_000_000L))
        assertFalse(LrcLibApi.lookupUrl("晴天", "周杰伦", null, 3_700_000L)!!.contains("duration="))
        assertEquals("233", LrcLibApi.durationSecondsOrNull(233_000L))
        // 四舍五入到最接近的秒
        assertEquals("270", LrcLibApi.durationSecondsOrNull(269_600L))
        assertEquals("3600", LrcLibApi.durationSecondsOrNull(3_600_000L))
    }

    @Test
    fun `缺歌名或歌手时不给查询地址`() {
        assertNull(LrcLibApi.lookupUrl("", "周杰伦", null, 0L))
        assertNull(LrcLibApi.lookupUrl("晴天", null, null, 0L))
        assertNull(LrcLibApi.lookupUrl("晴天", "  ", null, 0L))
        // 歌手是占位值时同样不能拿去查
        assertNull(LrcLibApi.lookupUrl("晴天", "未知歌手", null, 0L))
        assertNull(LrcLibApi.lookupUrl("晴天", "<unknown>", null, 0L))
        // 结构化搜索允许省掉歌手
        assertNotNull(LrcLibApi.searchBySignatureUrl("晴天", null))
        assertNull(LrcLibApi.searchBySignatureUrl("   ", "周杰伦"))
        assertEquals("https://lrclib.net/api/search?q=%E6%99%B4%E5%A4%A9", LrcLibApi.searchByKeywordUrl("晴天"))
        assertNull(LrcLibApi.searchByKeywordUrl("  "))
    }

    @Test
    fun `按 id 取词的地址只在 id 有效时给出`() {
        assertEquals("https://lrclib.net/api/get/3396226", LrcLibApi.byIdUrl(3396226L))
        assertNull(LrcLibApi.byIdUrl(0L))
        assertNull(LrcLibApi.byIdUrl(-1L))
    }

    @Test
    fun `歌手占位值不算可用歌手`() {
        assertNull(LrcLibApi.usableArtist(null))
        assertNull(LrcLibApi.usableArtist(""))
        assertNull(LrcLibApi.usableArtist("未知"))
        assertNull(LrcLibApi.usableArtist("未知歌手"))
        assertNull(LrcLibApi.usableArtist("<unknown>"))
        assertEquals("周杰伦", LrcLibApi.usableArtist(" 周杰伦 "))
    }

    // ---------------------------------------------------------------- 限流

    @Test
    fun `429 的 Retry-After 按秒解析，异常值退回兜底`() {
        assertEquals(30_000L, LrcLibApi.retryAfterMs("30", 0L))
        assertEquals(1_000L, LrcLibApi.retryAfterMs(" 1 ", 0L))
        // 给不出秒数（HTTP 日期格式 / 空 / 0 / 负数）时宁可多等，也不去撞
        assertEquals(LrcLibApi.RETRY_AFTER_FALLBACK_MS, LrcLibApi.retryAfterMs(null, 0L))
        assertEquals(LrcLibApi.RETRY_AFTER_FALLBACK_MS, LrcLibApi.retryAfterMs("", 0L))
        assertEquals(LrcLibApi.RETRY_AFTER_FALLBACK_MS, LrcLibApi.retryAfterMs("0", 0L))
        assertEquals(LrcLibApi.RETRY_AFTER_FALLBACK_MS, LrcLibApi.retryAfterMs("-5", 0L))
        assertEquals(LrcLibApi.RETRY_AFTER_FALLBACK_MS, LrcLibApi.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", 0L))
        // 离谱的值封顶在一天，避免被对面一纸 header 永久锁死
        assertEquals(86_400_000L, LrcLibApi.retryAfterMs("999999999", 0L))
    }

    // ---------------------------------------------------------------- 与既有链路的一致性

    @Test
    fun `LRCLIB 的候选走的是同一套匹配规则`() {
        val records = LrcLibApi.parseSearch(searchBody)
        // 本地是 270 秒的那一版：歌名歌手都对、时长几乎一致 → 选中
        assertEquals(17788L, LyricMatch.pickBest(records, "晴天", "周杰伦", 269_500L)?.id)
        // 本地只有 249 秒的版本时，249 那条更贴近
        assertEquals(23053743L, LyricMatch.pickBest(records, "晴天", "周杰伦", 249_000L)?.id)
        // 换了歌名就对不上，宁可不要
        assertNull(LyricMatch.pickBest(records, "夜曲", "周杰伦", 269_500L))
    }

    @Test
    fun `两家提供方共用同一个打分入口`() {
        val record = LrcLibApi.parseSearch(searchBody)[0]
        assertEquals(LyricMatch.score(record, "晴天", "周杰伦", 270_000L), NetEaseApi.score(record, "晴天", "周杰伦", 270_000L))
        assertEquals(NetEaseApi.MIN_SCORE, LyricMatch.MIN_SCORE)
    }

    @Test
    fun `联网来源的标注不再写死某一家`() {
        // 播放页把来源名做成 `${source.label} · ${container}`，所以 label 里不能再出现厂家名
        assertFalse(LyricsSource.ONLINE_PICKED.label.contains("网易云"))
        assertFalse(LyricsSource.ONLINE_AUTO.label.contains("网易云"))
        assertEquals("LRCLIB", LyricProvider.LRCLIB.label)
        assertEquals("网易云", LyricProvider.NETEASE.label)
    }
}
