package com.melody.player

import com.melody.player.core.HiddenSongEntry
import com.melody.player.core.HiddenSongs
import com.melody.player.core.Song
import com.melody.player.core.SongQuery
import com.melody.player.core.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「隐藏不想听的曲目」的过滤与增删。
 *
 * 重点在 key 的稳定性：隐藏靠 [Song.key]（MediaStore 的 id / SAF 的 URI 哈希）判断，
 * 曲目被改名或重新扫描后 key 不变，所以隐藏状态必须能跨扫描保持。
 */
class HiddenSongsTest {

    private fun song(
        id: Long,
        title: String,
        artist: String? = null,
        imported: Boolean = false
    ) = Song(
        // SAF 导入的曲目在真实代码里 id 恒为 -1（见 AudioLibrary.inspectSafUri），
        // 这里必须照做：否则 key 会退化成 "ms:<id>"，和媒体库曲目撞在一起
        id = if (imported) -1L else id,
        uri = if (imported) "content://saf/$id" else "content://media/audio/$id",
        title = title,
        artist = artist,
        album = null,
        durationMs = 180_000L,
        sizeBytes = 1024,
        dateAddedSec = 0L,
        imported = imported
    )

    private val a = song(1, "晴天", "周杰伦")
    private val b = song(2, "录音 001", null)
    private val c = song(3, "夜曲", "周杰伦")
    private val library = listOf(a, b, c)

    @Test
    fun `no hidden entries keeps the list untouched`() {
        assertEquals(library, HiddenSongs.visible(library, emptyList()))
    }

    @Test
    fun `hidden song disappears from the library`() {
        assertEquals(listOf(b, c), HiddenSongs.visible(library, listOf(HiddenSongEntry.of(a))))
    }

    @Test
    fun `hiding is by stable key not by title`() {
        // 模拟「重新扫描后标题变了但 id 没变」：同一首仍然保持隐藏
        val renamed = a.copy(title = "晴天 (Live)", artist = "周杰伦")
        assertEquals(listOf(b, c), HiddenSongs.visible(listOf(renamed, b, c), listOf(HiddenSongEntry.of(a))))
    }

    @Test
    fun `entries for songs that no longer exist are ignored`() {
        val ghost = HiddenSongEntry("ms:999", "已经不存在的歌", "谁")
        assertEquals(library, HiddenSongs.visible(library, listOf(ghost)))
    }

    @Test
    fun `imported and media store keys never collide`() {
        val saf = song(1, "导入的歌", imported = true) // 同一 id，但走 SAF → key 不同
        val hidden = listOf(HiddenSongEntry.of(saf))
        assertEquals(library, HiddenSongs.visible(library, hidden))
        assertTrue(HiddenSongs.visible(listOf(saf), hidden).isEmpty())
    }

    @Test
    fun `add puts the newest entry first and never duplicates`() {
        var entries = HiddenSongs.add(emptyList(), b)
        entries = HiddenSongs.add(entries, a)
        assertEquals(listOf(a.key, b.key), entries.map { it.key })

        // 再次隐藏同一首：不产生重复，并且提到最前
        entries = HiddenSongs.add(entries, b)
        assertEquals(listOf(b.key, a.key), entries.map { it.key })
    }

    @Test
    fun `add keeps a readable snapshot of title and artist`() {
        val entry = HiddenSongs.add(emptyList(), b).single()
        assertEquals("录音 001", entry.title)
        assertEquals("未知歌手", entry.artist) // 无歌手时用展示用的兜底文案
    }

    @Test
    fun `remove drops one entry and leaves the rest in order`() {
        val entries = HiddenSongs.add(HiddenSongs.add(emptyList(), a), c)
        assertEquals(listOf(c.key), HiddenSongs.remove(entries, a.key).map { it.key })
        assertEquals(entries, HiddenSongs.remove(entries, "ms:999"))
    }

    @Test
    fun `hidden songs also drop out of search results`() {
        val visible = HiddenSongs.visible(library, listOf(HiddenSongEntry.of(c)))
        assertTrue(SongQuery.apply(visible, "夜曲", SortMode.TITLE).isEmpty())
        assertEquals(2, SongQuery.apply(visible, "", SortMode.TITLE).size)
    }

    @Test
    fun `orphans reports entries whose song is gone from the device`() {
        val entries = listOf(HiddenSongEntry.of(a), HiddenSongEntry("ms:999", "幽灵", null))
        assertEquals(listOf("ms:999"), HiddenSongs.orphans(entries, library).map { it.key })
    }
}
