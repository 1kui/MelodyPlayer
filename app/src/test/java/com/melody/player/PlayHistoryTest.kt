package com.melody.player

import com.melody.player.core.PlayHistory
import com.melody.player.core.PlayRecord
import com.melody.player.core.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放历史：一张表、两个榜单。
 *
 * 出错的后果都是"看起来在用、其实数据是脏的"：重复播放堆出一堆同样的记录、
 * 一路快切把整张曲库刷成"常听"、或者曲目文件删了之后榜单上还留着一堆点不动的条目。
 */
class PlayHistoryTest {

    private fun song(id: Long, title: String, artist: String? = null, durationMs: Long = 0L) =
        Song(
            id = id,
            uri = "content://media/audio/$id",
            title = title,
            artist = artist,
            album = null,
            durationMs = durationMs,
            sizeBytes = 0L,
            dateAddedSec = 0L
        )

    private fun record(key: String, at: Long, count: Int = 0) =
        PlayRecord(key = key, title = key, artist = null, lastPlayedAtSec = at, playCount = count)

    // ------------------------------------------------------------ markPlayed

    @Test
    fun `播放即记：置顶，次数不变`() {
        var records = PlayHistory.markPlayed(emptyList(), song(1, "晴天", "周杰伦"), 100L)
        assertEquals(1, records.size)
        assertEquals("ms:1", records.single().key)
        assertEquals("晴天", records.single().title)
        assertEquals("周杰伦", records.single().artist)
        assertEquals(100L, records.single().lastPlayedAtSec)
        // 刚点开还没听满 30 秒，"常听"不该已经记上一笔
        assertEquals(0, records.single().playCount)

        records = PlayHistory.markPlayed(records, song(2, "七里香"), 200L)
        assertEquals(listOf("ms:2", "ms:1"), records.map { it.key })
    }

    @Test
    fun `再次播放不会抹掉已经攒下的次数`() {
        var records = PlayHistory.markListened(
            PlayHistory.markPlayed(emptyList(), song(1, "晴天"), 100L),
            "ms:1"
        )
        assertEquals(1, records.single().playCount)
        // 第二天又听了一次：时间更新、次数保留
        records = PlayHistory.markPlayed(records, song(1, "晴天"), 9_000L)
        assertEquals(1, records.size)
        assertEquals(9_000L, records.single().lastPlayedAtSec)
        assertEquals(1, records.single().playCount)
    }

    @Test
    fun `超过上限时丢掉最老的`() {
        var records = emptyList<PlayRecord>()
        repeat(PlayHistory.MAX_RECORDS + 5) { i ->
            records = PlayHistory.markPlayed(records, song(i.toLong(), "歌$i"), i.toLong())
        }
        assertEquals(PlayHistory.MAX_RECORDS, records.size)
        assertEquals("ms:${PlayHistory.MAX_RECORDS + 4}", records.first().key)
    }

    // ----------------------------------------------------------- markListened

    @Test
    fun `没记过的 key 不会因为计数凭空出现一条`() {
        assertTrue(PlayHistory.markListened(emptyList<PlayRecord>(), "ms:1").isEmpty())
        val existing = listOf(record("ms:1", 10L))
        // 只改命中的那条，别的原样
        val updated = PlayHistory.markListened(existing + record("ms:2", 20L), "ms:1")
        assertEquals(listOf(1, 0), updated.map { it.playCount })
    }

    @Test
    fun `常听门槛：满 30 秒算，差一毫秒不算，时长未知算`() {
        assertTrue(PlayHistory.countsAsListen(30_000L))
        assertFalse(PlayHistory.countsAsListen(29_999L))
        // 时长未知（<= 0）时不因此被排除：宁可算上，也别让整批歌都进不了榜单
        assertTrue(PlayHistory.countsAsListen(0L))
        assertTrue(PlayHistory.countsAsListen(-1L))
    }

    // --------------------------------------------------------------- 两个榜单

    @Test
    fun `最近播放按时间倒序`() {
        val records = listOf(record("a", 100L), record("b", 900L), record("c", 500L))
        assertEquals(listOf("b", "c", "a"), PlayHistory.recent(records, 10).map { it.key })
    }

    @Test
    fun `最常听按次数排，同次数按最近时间`() {
        val records = listOf(
            record("a", 100L, 3),
            record("b", 500L, 3),
            record("c", 900L, 1),
            record("d", 950L, 0)
        )
        // 次数为 0 的（只划过没听满）不进这个榜
        assertEquals(listOf("b", "a", "c"), PlayHistory.mostPlayed(records, 10).map { it.key })
    }

    @Test
    fun `取前 n 条`() {
        val records = listOf(record("a", 3L), record("b", 2L), record("c", 1L))
        assertEquals(listOf("a", "b"), PlayHistory.recent(records, 2).map { it.key })
    }

    // ---------------------------------------------------------------- resolve

    @Test
    fun `解析回曲目时丢掉曲库里已经没了的`() {
        val records = listOf(record("ms:1", 200L), record("ms:9", 100L))
        val songs = listOf(song(1, "晴天"))
        assertEquals(listOf("ms:1"), PlayHistory.resolve(records, songs).map { it.key })
    }

    @Test
    fun `解析保持记录本身的顺序`() {
        val records = listOf(record("ms:2", 200L), record("ms:1", 100L))
        val songs = listOf(song(1, "晴天"), song(2, "七里香"))
        assertEquals(listOf("ms:2", "ms:1"), PlayHistory.resolve(records, songs).map { it.key })
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(PlayHistory.resolve(emptyList(), listOf(song(1, "晴天"))).isEmpty())
        assertTrue(PlayHistory.resolve(listOf(record("ms:1", 1L)), emptyList()).isEmpty())
        assertTrue(PlayHistory.recent(emptyList(), 5).isEmpty())
        assertTrue(PlayHistory.mostPlayed(emptyList(), 5).isEmpty())
    }
}
