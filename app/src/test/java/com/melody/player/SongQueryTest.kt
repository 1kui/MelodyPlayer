package com.melody.player

import com.melody.player.core.Song
import com.melody.player.core.SongQuery
import com.melody.player.core.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongQueryTest {

    private fun song(
        id: Long,
        title: String,
        artist: String? = null,
        album: String? = null,
        durationMs: Long = 180_000L,
        dateAdded: Long = 0L,
        displayName: String? = null,
        imported: Boolean = false
    ) = Song(
        id = id,
        uri = "content://media/audio/$id",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        sizeBytes = 1024,
        dateAddedSec = dateAdded,
        displayName = displayName,
        imported = imported
    )

    private val library = listOf(
        song(1, "Blue Moon", "Frank", "Jazz Nights", 200_000, 100),
        song(2, "apple tree", "Fiona", "Folk", 150_000, 300),
        song(3, "夜曲", "周杰伦", "十一月的萧邦", 227_000, 200),
        song(4, "Unknown Track", null, null, 90_000, 400)
    )

    @Test
    fun `empty query returns everything`() {
        assertEquals(library, SongQuery.filter(library, ""))
        assertEquals(library, SongQuery.filter(library, "   "))
    }

    @Test
    fun `search is case insensitive`() {
        assertEquals(1, SongQuery.filter(library, "BLUE").size)
        assertEquals(1, SongQuery.filter(library, "blue").size)
        assertEquals(1, SongQuery.filter(library, "ApPlE").size)
    }

    @Test
    fun `search matches title artist album and file name`() {
        assertEquals(listOf(3L), SongQuery.filter(library, "周杰伦").map { it.id })
        assertEquals(listOf(1L), SongQuery.filter(library, "Jazz").map { it.id })
        assertEquals(listOf(1L), SongQuery.filter(library, "moon").map { it.id })
    }

    @Test
    fun `search matches display name for imported files`() {
        val withFile = library + song(9, "未知标题", null, null, displayName = "mystery-song.flac", imported = true)
        assertEquals(listOf(9L), SongQuery.filter(withFile, "mystery").map { it.id })
    }

    @Test
    fun `unknown artist and album get placeholders`() {
        val unknown = library.first { it.id == 4L }
        assertEquals("未知歌手", unknown.artistOrUnknown)
        assertEquals("未知专辑", unknown.albumOrUnknown)
        // MediaStore 偶尔真的会写 "<unknown>"，也要被识别成未知
        val weird = song(5, "x", "<unknown>", "<unknown>")
        assertEquals("未知歌手", weird.artistOrUnknown)
    }

    @Test
    fun `sort by title ignores case`() {
        val sorted = SongQuery.sortBy(library, SortMode.TITLE)
        assertEquals(listOf("apple tree", "Blue Moon", "Unknown Track", "夜曲"), sorted.map { it.title })
    }

    @Test
    fun `sort by artist groups per artist and breaks ties by title`() {
        val sorted = SongQuery.sortBy(library, SortMode.ARTIST)
        // 未知歌手排在有名字的歌手之后（占位文案按码位比较落在最后）
        assertEquals(
            listOf("Fiona", "Frank", "周杰伦", "未知歌手"),
            sorted.map { it.artistOrUnknown }
        )
    }

    @Test
    fun `sort by recent puts newest first`() {
        val sorted = SongQuery.sortBy(library, SortMode.RECENT)
        assertEquals(listOf(400L, 300L, 200L, 100L), sorted.map { it.dateAddedSec })
    }

    @Test
    fun `sort by duration is ascending`() {
        val sorted = SongQuery.sortBy(library, SortMode.DURATION)
        assertEquals(listOf(90_000L, 150_000L, 200_000L, 227_000L), sorted.map { it.durationMs })
    }

    @Test
    fun `apply combines filter and sort`() {
        val result = SongQuery.apply(library, "a", SortMode.TITLE)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.title.lowercase().contains("a") || it.artistOrUnknown.lowercase().contains("a") })
    }

    @Test
    fun `stable key differs between media store and saf sources`() {
        val fromStore = song(7, "A")
        val fromSaf = song(-1, "A", imported = true).copy(uri = "content://com.android.providers/document/1")
        assertEquals("ms:7", fromStore.key)
        assertTrue(fromSaf.key.startsWith("saf:"))
        assertTrue(fromStore.key != fromSaf.key)
    }

    @Test
    fun `artwork seed is stable and positive`() {
        val s = song(1, "Blue Moon", "Frank")
        assertEquals(s.artworkSeed, song(1, "Blue Moon", "Frank").artworkSeed)
        assertTrue(s.artworkSeed >= 0)
        assertTrue(s.artworkSeed != song(2, "夜曲", "周杰伦").artworkSeed)
    }
}
