package com.melody.player

import com.melody.player.core.LrcParser
import com.melody.player.core.online.NetEaseApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实抓取的接口响应**回归解析与匹配。
 *
 * 上面那组 OnlineLyricsTest 用的是照着文档手写的样例；这一组是 2026-09-19 实际请求
 * `music.163.com` 拿到的原始响应（只截短了歌词体，字段结构一字未改）。两者的价值不同：
 * 手写样例验证「逻辑对不对」，真实响应验证「接口实际长这样」——
 * 后者才能挡住「字段名其实叫别的」这类只在真机上才暴露的问题。
 *
 * 夹具放 test/resources 而不是内联成字符串：换成新抓的响应只要覆盖文件即可。
 */
class NetEaseRealResponseTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("net/$name")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("缺少测试夹具 net/$name")

    private fun searchResult() = NetEaseApi.parseSearch(fixture("search_lemon.json"))

    @Test
    fun `真实搜索响应能解析出歌名歌手时长专辑`() {
        val songs = searchResult()
        assertTrue("真实响应应当解析出多条候选", songs.size >= 3)

        val first = songs[0]
        assertEquals("Lemon", first.title)
        assertEquals(listOf("米津玄師"), first.artists)
        assertEquals(256_000L, first.durationMs)
        assertEquals("Lemon", first.album)
        assertEquals(536622304L, first.id)
    }

    @Test
    fun `真实响应里嵌套很深的字段不会干扰解析`() {
        val songs = searchResult()
        // 真实响应里 album.artist.name 是空字符串，且 songs 里还有 fee/rtype/ftype 等数字字段，
        // 这些都不能让 album 变成空串或者把 duration 读错
        songs.forEach { song ->
            assertTrue(song.title.isNotBlank())
            assertTrue(song.durationMs > 0L)
            assertTrue(song.album == null || song.album.isNotBlank())
        }
    }

    @Test
    fun `按本地标签能在真实候选里选中原唱而不是改编版`() {
        val songs = searchResult()
        val best = NetEaseApi.pickBest(songs, "Lemon", "米津玄師", 256_000L)
        assertNotNull(best)
        assertEquals("Lemon", best!!.title)
        assertEquals(listOf("米津玄師"), best.artists)
        // 时长要落在原唱附近，而不是那条 86 秒的「改大调」改编版
        assertTrue(best.durationMs in 254_000L..257_000L)
        assertNotEquals(1344727797L, best.id)
    }

    @Test
    fun `真实歌词响应能合并出带翻译的双语歌词`() {
        val song = searchResult().first()
        val lyric = NetEaseApi.parseLyric(fixture("lyric_lemon.json"), song)
        assertNotNull(lyric)
        assertTrue("这条响应带官方翻译", lyric!!.hasTranslation)

        val lines = LrcParser.parse(lyric.lrcText).lines
        // 原文 6 行 + 其中 2 行（有译文对齐的）各追加一行译文
        assertEquals(8, lines.size)

        // 同一时刻原文在前、译文在后，显示出来才是「上原文下译文」
        assertEquals("夢ならばどれほどよかったでしょう", lines[4].text)
        assertEquals("如果这一切都是梦境该有多好", lines[5].text)
        assertEquals(lines[4].timeMs, lines[5].timeMs)

        assertEquals("未だにあなたのことを夢にみる", lines[6].text)
        assertEquals("至今仍能与你在梦中相遇", lines[7].text)
        assertEquals(lines[6].timeMs, lines[7].timeMs)
    }

    @Test
    fun `真实响应里的制作人署名行不会被当成时间标签丢掉`() {
        // 网易云的原文里前四行是「作词/作曲/编曲/制作人」，都带时间轴，
        // 必须原样保留 —— 丢了会让整份歌词的时间轴对不上
        val lyric = NetEaseApi.parseLyric(fixture("lyric_lemon.json"), searchResult().first())!!
        val texts = LrcParser.parse(lyric.lrcText).lines.map { it.text }
        assertTrue(texts.any { it.startsWith("作词") })
        assertTrue(texts.any { it.startsWith("制作人") })
    }
}
