package com.melody.player

import com.melody.player.core.Playlist
import com.melody.player.core.Playlists
import com.melody.player.core.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义歌单的增删改排。
 *
 * 歌单只存 [Song.key]，所以这里最要紧的两条性质是：
 * ① 顺序是**用户排的**，不能被曲库的排序方式重排；
 * ② key 在改名 / 重新扫描后不变，所以改了歌名也不能让歌单里的歌"变成另一首"。
 */
class PlaylistsTest {

    private fun song(id: Long, title: String, artist: String? = null) = Song(
        id = id,
        uri = "content://media/audio/$id",
        title = title,
        artist = artist,
        album = null,
        durationMs = 180_000L,
        sizeBytes = 1024,
        dateAddedSec = 0L
    )

    private val a = song(1, "晴天", "周杰伦")
    private val b = song(2, "夜曲", "周杰伦")
    private val c = song(3, "夜空中最亮的星")
    private val library = listOf(a, b, c)

    private fun playlistOf(vararg keys: String) =
        Playlist(id = "pl:1", name = "通勤", songKeys = keys.toList(), createdAtSec = 100L)

    // ------------------------------------------------------------------ 建 / 改名 / 删

    @Test
    fun `new playlist goes to the front so it is immediately visible`() {
        val first = Playlists.create(emptyList(), "通勤", "pl:1", 100L)
        val second = Playlists.create(first, "睡前", "pl:2", 200L)
        assertEquals(listOf("pl:2", "pl:1"), second.map { it.id })
        assertEquals(200L, second.first().createdAtSec)
    }

    @Test
    fun `duplicate name gets a numeric suffix instead of overwriting`() {
        val made = Playlists.create(emptyList(), "通勤", "pl:1", 100L)
        val again = Playlists.create(made, "通勤", "pl:2", 200L)
        // 新建的在最前，所以撞名的那个排在 [0]、被撞的排在 [1]
        assertEquals(listOf("通勤 (2)", "通勤"), again.map { it.name })
        // 名字被改过的同时 id 也要保持唯一，否则第二个歌单会覆盖第一个
        assertNotEquals(again[0].id, again[1].id)
    }

    @Test
    fun `blank name falls back to a readable default`() {
        val made = Playlists.create(emptyList(), "   ", "pl:1", 100L)
        assertEquals("新建歌单", made.single().name)
    }

    @Test
    fun `same id in the same second still produces two distinct playlists`() {
        val one = Playlists.create(emptyList(), "A", "pl:1", 100L)
        val two = Playlists.create(one, "B", "pl:1", 100L)
        assertEquals(2, two.size)
        assertEquals("pl:1-2", two.first().id)
    }

    @Test
    fun `normalize collapses inner spaces and truncates`() {
        assertEquals("通勤 路上", Playlists.normalizeName("  通勤   路上  "))
        assertEquals(Playlists.MAX_NAME_LENGTH, Playlists.normalizeName("长".repeat(50)).length)
    }

    @Test
    fun `rename rejects blank and conflicting names instead of silently doing nothing`() {
        val made = Playlists.create(
            Playlists.create(emptyList(), "通勤", "pl:1", 100L), "睡前", "pl:2", 200L
        )
        assertEquals(made, Playlists.rename(made, "pl:1", "   "))
        assertEquals(made, Playlists.rename(made, "pl:1", "睡前"))
        // made 的顺序是 [睡前, 通勤]（新建的在前），改名只换名字、不动位置
        assertEquals(listOf("睡前", "改过了"), Playlists.rename(made, "pl:1", "改过了").map { it.name })
    }

    @Test
    fun `renaming to its own current name is allowed`() {
        val made = Playlists.create(emptyList(), "通勤", "pl:1", 100L)
        assertEquals(1, Playlists.rename(made, "pl:1", "通勤").size)
    }

    @Test
    fun `delete only removes the target playlist`() {
        val made = Playlists.create(
            Playlists.create(emptyList(), "通勤", "pl:1", 100L), "睡前", "pl:2", 200L
        )
        assertEquals(listOf("pl:2"), Playlists.delete(made, "pl:1").map { it.id })
    }

    // ------------------------------------------------------------------ 加歌 / 移歌

    @Test
    fun `adding keeps insertion order and appends at the end`() {
        val pl = Playlists.addSongs(playlistOf(a.key), listOf(b.key, c.key))
        assertEquals(listOf(a.key, b.key, c.key), pl.songKeys)
    }

    @Test
    fun `adding a song already in the playlist does not move it to the end`() {
        // 用户从曲库批量勾选时很容易把已有的那几首也选上；让它们跳到队尾
        // 会把"再加两首"变成"重排整个歌单"
        val pl = playlistOf(a.key, b.key)
        val after = Playlists.addSongs(pl, listOf(a.key, c.key))
        assertEquals(listOf(a.key, b.key, c.key), after.songKeys)
    }

    @Test
    fun `adding nothing or only duplicates leaves the playlist untouched`() {
        val pl = playlistOf(a.key)
        assertSame(pl, Playlists.addSongs(pl, emptyList()))
        assertSame(pl, Playlists.addSongs(pl, listOf(a.key)))
        assertSame(pl, Playlists.addSongs(pl, listOf("", "  ")))
    }

    @Test
    fun `duplicates inside the incoming list do not create duplicate rows`() {
        // 批量选择会一次传进一串 key，里面有重复很常见；不去重的话歌单里会出现
        // 同一个 key 好几行 —— 界面上是同一首歌重复几遍，队列也会重复播
        val pl = playlistOf(a.key)
        val after = Playlists.addSongs(pl, listOf(b.key, b.key, c.key, b.key))
        assertEquals(listOf(a.key, b.key, c.key), after.songKeys)
        // 再加一次同样的列表，歌单不该有任何变化
        assertSame(after, Playlists.addSongs(after, listOf(b.key, b.key, c.key)))
    }

    @Test
    fun `removing a song only drops that key`() {
        val pl = playlistOf(a.key, b.key, c.key)
        assertEquals(listOf(a.key, c.key), Playlists.removeSong(pl, b.key).songKeys)
        assertEquals(pl.songKeys, Playlists.removeSong(pl, "ms:999").songKeys)
    }

    // ------------------------------------------------------------------ 排序

    @Test
    fun `move up and down swap neighbours`() {
        val pl = playlistOf(a.key, b.key, c.key)
        // 一次只挪一位，也就是和相邻的那首换位；把 c 挪到队首需要连着上移两次
        assertEquals(listOf(b.key, a.key, c.key), Playlists.moveDown(pl, 0).songKeys)
        assertEquals(listOf(a.key, c.key, b.key), Playlists.moveUp(pl, 2).songKeys)
    }

    @Test
    fun `moving past either end is a no-op`() {
        val pl = playlistOf(a.key, b.key, c.key)
        assertSame(pl, Playlists.moveUp(pl, 0))
        assertSame(pl, Playlists.moveDown(pl, 2))
        assertSame(pl, Playlists.moveUp(pl, -1))
        assertSame(pl, Playlists.moveDown(pl, 99))
        assertEquals(listOf(a.key, b.key, c.key), pl.songKeys)
    }

    @Test
    fun `order survives because the playlist stores keys not indices`() {
        // 反复上移同一首，它应该稳定地走到目标位置，而不是在别的歌之间来回跳
        var pl = playlistOf(a.key, b.key, c.key, "ms:9")
        pl = Playlists.moveUp(pl, 3)
        pl = Playlists.moveUp(pl, 2)
        pl = Playlists.moveUp(pl, 1)
        assertEquals(listOf("ms:9", a.key, b.key, c.key), pl.songKeys)
    }

    // ------------------------------------------------------------------ 解析成曲目

    @Test
    fun `resolve returns songs in playlist order not library order`() {
        val pl = playlistOf(c.key, a.key)
        assertEquals(listOf(c, a), Playlists.resolve(pl, library))
    }

    @Test
    fun `resolve drops keys whose song is gone instead of leaving unplayable rows`() {
        val pl = playlistOf(a.key, "ms:999", b.key)
        assertEquals(listOf(a, b), Playlists.resolve(pl, library))
        assertEquals(listOf("ms:999"), Playlists.orphans(pl, library))
    }

    @Test
    fun `a renamed song still resolves to the same playlist slot`() {
        // 这是"只存 key 不存快照"的理由：改了歌名/歌手不能让它变成另一首
        val renamed = a.copy(title = "晴天 (Live)", artist = "周杰伦")
        val resolved = Playlists.resolve(playlistOf(a.key, b.key), listOf(renamed, b))
        assertEquals(listOf("晴天 (Live)", "夜曲"), resolved.map { it.title })
    }

    @Test
    fun `empty playlist or empty library resolves to nothing without throwing`() {
        assertTrue(Playlists.resolve(playlistOf(), library).isEmpty())
        assertTrue(Playlists.resolve(playlistOf(a.key), emptyList()).isEmpty())
        assertEquals(listOf(a.key), Playlists.orphans(playlistOf(a.key), emptyList()))
    }

    @Test
    fun `unlisted counts songs no playlist references`() {
        val pl = Playlists.addSongs(playlistOf(a.key), listOf(b.key))
        assertEquals(1, Playlists.unlistedCount(listOf(pl), library))
        assertEquals(0, Playlists.unlistedCount(listOf(pl), listOf(a, b)))
        assertEquals(3, Playlists.unlistedCount(emptyList(), library))
    }

    @Test
    fun `a song listed in several playlists is only unlisted once`() {
        val p1 = playlistOf(a.key)
        val p2 = Playlist("pl:2", "睡前", listOf(a.key, b.key))
        assertEquals(1, Playlists.unlistedCount(listOf(p1, p2), library))
    }
}