package com.melody.player

import com.melody.player.core.Song
import com.melody.player.core.SongEdit
import com.melody.player.core.SongEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SongEditsTest {

    private fun song(
        id: Long = 1L,
        title: String = "旧歌名",
        artist: String? = "旧歌手",
        album: String? = "旧专辑"
    ) = Song(
        id = id,
        uri = "content://ms/$id",
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000L,
        sizeBytes = 1L,
        dateAddedSec = 0L
    )

    @Test
    fun `编辑只改显示字段 key 不变`() {
        val original = song()
        val edit = SongEdit.of(original, "新歌名", "新歌手", "新专辑")

        val edited = edit.appliedTo(original)

        assertEquals(original.key, edited.key)
        assertEquals("新歌名", edited.title)
        assertEquals("新歌手", edited.artist)
        assertEquals("新专辑", edited.album)
    }

    @Test
    fun `歌手专辑留空按无标签处理`() {
        val original = song()
        val edit = SongEdit.of(original, "新歌名", "  ", "")

        val edited = edit.appliedTo(original)

        assertEquals("新歌名", edited.title)
        assertEquals(null, edited.artist)
        assertEquals(null, edited.album)
        assertEquals("未知歌手", edited.artistOrUnknown)
        assertEquals("未知专辑", edited.albumOrUnknown)
    }

    @Test
    fun `恢复用记录里存的原值`() {
        val original = song(artist = null, album = null)
        val edit = SongEdit.of(original, "新歌名", "填错的歌手", "填错的专辑")

        val restored = edit.restored(edit.appliedTo(original))

        assertEquals(original.title, restored.title)
        assertEquals(original.artist, restored.artist)
        assertEquals(original.album, restored.album)
        assertEquals(original, restored)
    }

    @Test
    fun `apply 套用到整张曲库 无记录的条目保持同一引用`() {
        val a = song(id = 1L, title = "A")
        val b = song(id = 2L, title = "B")
        val edit = SongEdit.of(a, "A2", "歌手", "专辑")

        val out = SongEdits.apply(listOf(a, b), mapOf(a.key to edit))

        assertEquals("A2", out[0].title)
        assertSame(b, out[1])
    }

    @Test
    fun `apply 空记录直接返回原列表`() {
        val songs = listOf(song())
        assertSame(songs, SongEdits.apply(songs, emptyMap()))
    }

    @Test
    fun `without 摘掉指定记录 其他保留`() {
        val a = song(id = 1L)
        val b = song(id = 2L)
        val edits = mapOf(
            a.key to SongEdit.of(a, "A2", "", ""),
            b.key to SongEdit.of(b, "B2", "", "")
        )

        val rest = SongEdits.without(edits, a.key)

        assertEquals(setOf(b.key), rest.keys)
    }

    @Test
    fun `搜索与排序吃编辑后的新值`() {
        val a = song(id = 1L, title = "旧标题A")
        val edit = SongEdit.of(a, "最佳损友", "陈奕迅", "What's Going On")
        val edited = edit.appliedTo(a)

        val hit = com.melody.player.core.SongQuery.filter(listOf(edited), "损友")
        assertEquals(listOf(edited), hit)
    }
}
