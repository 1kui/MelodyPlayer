package com.melody.player

import com.melody.player.core.LrcParser
import com.melody.player.core.online.JsonValue
import com.melody.player.core.online.MiniJson
import com.melody.player.core.online.NetEaseApi
import com.melody.player.core.online.OnlineSong
import com.melody.player.core.online.array
import com.melody.player.core.online.field
import com.melody.player.core.online.long
import com.melody.player.core.online.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 精简 JSON 解析器。
 *
 * 它是联网歌词链路的入口：解析器一旦把响应读错，后面所有匹配都建立在错数据上，
 * 而这种错误在真机上是「歌词对不上」这种很难复现的现象。所以这里覆盖得细一些。
 */
class MiniJsonTest {

    @Test
    fun `解析嵌套对象与数组`() {
        val root = MiniJson.parse(
            """
            {"result":{"songs":[{"id":1900172235,"name":"フォニイ",
            "artists":[{"name":"可不"},{"name":"ツミキ"}],"duration":191000}],"songCount":10},"code":200}
            """.trimIndent()
        )
        assertNotNull(root)
        assertEquals(200L, root.long("code"))
        val songs = root.field("result").array("songs")
        assertEquals(1, songs.size)
        assertEquals(1900172235L, songs[0].long("id"))
        assertEquals("フォニイ", songs[0].str("name"))
        assertEquals(191000L, songs[0].long("duration"))
        val artists = songs[0].array("artists")
        assertEquals(listOf("可不", "ツミキ"), artists.map { it.str("name") })
    }

    @Test
    fun `转义与 unicode 转义`() {
        val root = MiniJson.parse("""{"a":"第一行\n第二行","b":"\u4e2d\u6587","c":"引号\"里\"的"}""")
        assertEquals("第一行\n第二行", root.str("a"))
        assertEquals("中文", root.str("b"))
        assertEquals("引号\"里\"的", root.str("c"))
    }

    @Test
    fun `空容器与 null 都能解析`() {
        val root = MiniJson.parse("""{"a":[],"b":{},"c":null,"d":false}""")
        assertEquals(0, root.array("a").size)
        assertNull(root.field("b")!!.field("x"))
        assertEquals(JsonValue.Null, root.field("c"))
        assertEquals(JsonValue.Bool(false), root.field("d"))
    }

    @Test
    fun `数字支持负数与小数`() {
        val root = MiniJson.parse("""{"a":-460,"b":1.5,"c":12,"d":1e3}""")
        assertEquals(-460L, root.long("a"))
        assertEquals(12L, root.long("c"))
        assertEquals(1000L, root.long("d"))
    }

    @Test
    fun `坏输入返回 null 而不是抛异常`() {
        assertNull(MiniJson.parse("{不是 json"))
        assertNull(MiniJson.parse("""{"a":1,}"""))
        assertNull(MiniJson.parse("{\"a\":\"未闭合}"))
        assertNull(MiniJson.parse(""))
    }

    @Test
    fun `字段缺失时安全返回空值`() {
        val root = MiniJson.parse("""{"a":1}""")
        assertNull(root.str("nope"))
        assertNull(root.long("nope"))
        assertTrue(root.array("nope").isEmpty())
        assertNull(root.field("a")!!.str("inner"))
    }
}

/**
 * 网易云响应解析与匹配打分。
 *
 * 这里最要紧的是「宁可没有，也不要给错」：一首歌的歌词配错了，比没有歌词更糟。
 */
class NetEaseApiTest {

    private val searchBody = """
        {"result":{"songs":[
          {"id":1900172235,"name":"フォニイ",
           "artists":[{"id":1,"name":"可不"},{"id":2,"name":"ツミキ"}],
           "album":{"id":9,"name":"フォニイ"},"duration":191000},
          {"id":1864969357,"name":"フォニイ(偽物/phony)（翻自 可不）",
           "artists":[{"id":3,"name":"Yvan叶子"}],
           "album":{},"duration":190500},
          {"id":999,"name":"夜曲","artists":[{"id":4,"name":"周杰伦"}],
           "album":{"name":"十一月的萧邦"},"duration":227000}
        ],"hasMore":true,"songCount":10},"code":200}
    """.trimIndent()

    private val lyricBody = """
        {"sgc":false,"sfy":false,
         "lrc":{"version":15,"lyric":"[00:00.617]这个世上没有比假花更美的花\n[00:05.748]因为它完全是由虚假所构成"},
         "tlyric":{"version":1,"lyric":"[00:00.617]No flower is more beautiful than a fake one\n[00:05.748]Because it is made entirely of lies"},
         "code":200}
    """.trimIndent()

    private fun firstSong(): OnlineSong = NetEaseApi.parseSearch(searchBody).first()

    // ---------------------------------------------------------------- 搜索解析

    @Test
    fun `解析搜索结果`() {
        val songs = NetEaseApi.parseSearch(searchBody)
        assertEquals(3, songs.size)
        assertEquals(1900172235L, songs[0].id)
        assertEquals("フォニイ", songs[0].title)
        assertEquals(listOf("可不", "ツミキ"), songs[0].artists)
        assertEquals("フォニイ", songs[0].album)
        assertEquals(191000L, songs[0].durationMs)
        // album 为空对象时不能变成 "null" 字符串
        assertNull(songs[1].album)
    }

    @Test
    fun `code 不是 200 时返回空列表`() {
        assertEquals(emptyList<OnlineSong>(), NetEaseApi.parseSearch("""{"code":-460,"msg":"Cheating"}"""))
        assertEquals(emptyList<OnlineSong>(), NetEaseApi.parseSearch("<html>502</html>"))
    }

    @Test
    fun `没有 id 或没有歌名的条目直接丢掉`() {
        val body = """{"result":{"songs":[{"name":"孤儿"},{"id":5},{"id":6,"name":"好的"}]},"code":200}"""
        val songs = NetEaseApi.parseSearch(body)
        assertEquals(1, songs.size)
        assertEquals("好的", songs[0].title)
    }

    @Test
    fun `兼容用 singer 字段返回歌手的旧写法`() {
        val body = """{"result":{"songs":[{"id":1,"name":"晴天","singer":[{"name":"周杰伦"}]}]},"code":200}"""
        assertEquals(listOf("周杰伦"), NetEaseApi.parseSearch(body)[0].artists)
    }

    // ---------------------------------------------------------------- 歌词解析

    @Test
    fun `原文与翻译按时间轴交错合并`() {
        val lyric = NetEaseApi.parseLyric(lyricBody, firstSong())
        assertNotNull(lyric)
        assertTrue(lyric!!.hasTranslation)
        val lines = LrcParser.parse(lyric.lrcText).lines
        assertEquals(4, lines.size)
        // 同一时刻：原文在前、译文在后，交给 Lyrics.mergeEqualTimestamps 合成一行双语
        assertEquals("这个世上没有比假花更美的花", lines[0].text)
        assertEquals("No flower is more beautiful than a fake one", lines[1].text)
        assertEquals(lines[0].timeMs, lines[1].timeMs)
        assertEquals("因为它完全是由虚假所构成", lines[2].text)
        assertEquals("Because it is made entirely of lies", lines[3].text)
    }

    @Test
    fun `没有翻译时原文一字不改地保留`() {
        val original = "[ti:测试]\n[offset:+200]\n[00:01.00]第一句\n[00:02.00]第二句"
        val body = """{"lrc":{"lyric":${quote(original)}},"code":200}"""
        val lyric = NetEaseApi.parseLyric(body, firstSong())
        assertNotNull(lyric)
        assertEquals(false, lyric!!.hasTranslation)
        // 头部元信息必须原样留着，否则写回音频文件时会丢 offset
        assertEquals(original, lyric.lrcText)
        assertEquals(200L, LrcParser.parse(lyric.lrcText).offsetMs)
    }

    @Test
    fun `无歌词的几种返回都当作没取到`() {
        val song = firstSong()
        assertNull(NetEaseApi.parseLyric("""{"nolyric":true,"code":200}""", song))
        assertNull(NetEaseApi.parseLyric("""{"uncollected":true,"code":200}""", song))
        assertNull(NetEaseApi.parseLyric("""{"lrc":{"lyric":""},"code":200}""", song))
        assertNull(NetEaseApi.parseLyric("""{"lrc":{"lyric":"   "},"code":200}""", song))
        // 只有时间轴元信息、一句实际歌词都没有，同样视为没有
        assertNull(NetEaseApi.parseLyric("""{"lrc":{"lyric":"[ti:只有标题]"},"code":200}""", song))
        assertNull(NetEaseApi.parseLyric("not json", song))
    }

    @Test
    fun `JSON 里的换行转义要还原成真正的换行`() {
        val lyric = NetEaseApi.parseLyric(lyricBody, firstSong())
        assertTrue(lyric!!.lrcText.contains("\n"))
        assertTrue(!lyric.lrcText.contains("\\n"))
    }

    // ---------------------------------------------------------------- 匹配

    @Test
    fun `歌名歌手时长都对上时挑中最准的一条`() {
        val songs = NetEaseApi.parseSearch(searchBody)
        val best = NetEaseApi.pickBest(songs, "フォニイ", "可不", 191_000L)
        assertEquals(1900172235L, best?.id)
    }

    @Test
    fun `翻唱版本分数不够时宁可返回空`() {
        val cover = NetEaseApi.parseSearch(searchBody)[1]
        // 单独看：歌名只算「以目标开头」，歌手对不上，于是总分低于阈值
        assertTrue(NetEaseApi.score(cover, "フォニイ", "可不", 191_000L) < NetEaseApi.MIN_SCORE)
        assertNull(NetEaseApi.pickBest(listOf(cover), "フォニイ", "可不", 191_000L))
    }

    @Test
    fun `完全不同的歌直接落选`() {
        val other = NetEaseApi.parseSearch(searchBody)[2]
        assertNull(NetEaseApi.pickBest(listOf(other), "フォニイ", "可不", 191_000L))
    }

    @Test
    fun `歌手对不上要扣分`() {
        val song = firstSong()
        val withArtist = NetEaseApi.score(song, "フォニイ", "可不", 191_000L)
        val wrongArtist = NetEaseApi.score(song, "フォニイ", "完全不认识的歌手", 191_000L)
        assertTrue(wrongArtist < withArtist)
    }

    @Test
    fun `时长差得离谱要扣分`() {
        val song = firstSong()
        val same = NetEaseApi.score(song, "フォニイ", "可不", 191_000L)
        val wayOff = NetEaseApi.score(song, "フォニイ", "可不", 420_000L)
        assertTrue(wayOff < same)
    }

    @Test
    fun `本地完全没有元数据时不会误判崩溃`() {
        val songs = NetEaseApi.parseSearch(searchBody)
        // 歌手与时长未知：只靠歌名精确命中也能选中
        assertEquals(1900172235L, NetEaseApi.pickBest(songs, "フォニイ", null, 0L)?.id)
    }

    // ---------------------------------------------------------------- 关键词

    @Test
    fun `搜索关键词剥掉版本说明再拼歌手`() {
        assertEquals("晴天 周杰伦", NetEaseApi.searchKeyword("晴天", "周杰伦"))
        assertEquals(
            "Love Story Taylor Swift",
            NetEaseApi.searchKeyword("Love Story (Taylor's Version)", "Taylor Swift")
        )
        assertEquals("Lemon 米津玄師", NetEaseApi.searchKeyword("Lemon【320K】", "米津玄師"))
        assertEquals("Hello", NetEaseApi.searchKeyword("Hello feat. Adele", null))
    }

    @Test
    fun `歌手字段是未知占位时不拼进关键词`() {
        assertEquals("晴天", NetEaseApi.searchKeyword("晴天", "<unknown>"))
        assertEquals("晴天", NetEaseApi.searchKeyword("晴天", "未知歌手"))
        assertEquals("晴天", NetEaseApi.searchKeyword("晴天", "   "))
    }

    @Test
    fun `歌名整体在括号里时清洗结果为空 由调用方退回原始歌名`() {
        // 清洗会把括号内容整个去掉，此时关键词为空，searchOnline 会用原始歌名兜一次
        assertEquals("", NetEaseApi.cleanTitle("（完整版）"))
        assertEquals("", NetEaseApi.searchKeyword("（完整版）", null))
    }

    @Test
    fun `归一化忽略大小写与标点`() {
        assertEquals(NetEaseApi.normalize("Love Story"), NetEaseApi.normalize("love story!"))
        assertEquals(NetEaseApi.normalize("フォニイ"), NetEaseApi.normalize("フォニイ "))
    }

    private fun quote(raw: String): String =
        '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"'
}
