package com.melody.player

import com.melody.player.core.Albums
import com.melody.player.core.Artists
import com.melody.player.core.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专辑 / 歌手维度的聚合。
 *
 * 聚合键错一点，用户看到的就是两张本该是一张的专辑、或者一首歌从专辑里凭空消失 ——
 * 而这类错误在界面上完全不像"出错"，只像"我的歌本来就这样"。
 */
class AlbumGroupingTest {

    private fun song(
        id: Long,
        title: String,
        artist: String? = null,
        album: String? = null,
        durationMs: Long = 0L
    ) = Song(
        id = id,
        uri = "content://media/audio/$id",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        sizeBytes = 0L,
        dateAddedSec = 0L
    )

    // ----------------------------------------------------------------- 专辑

    @Test
    fun `按专辑加歌手归并`() {
        val groups = Albums.group(
            listOf(
                song(1, "晴天", "周杰伦", "叶惠美"),
                song(2, "以父之名", "周杰伦", "叶惠美"),
                song(3, "晴天", "别人", "叶惠美")
            )
        )
        // 同名专辑、不同歌手不能并成一张：并了之后点进去作者都换了人
        assertEquals(2, groups.size)
        val zhou = groups.first { it.artist == "周杰伦" }
        assertEquals(2, zhou.count)
        assertEquals("叶惠美", zhou.album)
        assertEquals("叶惠美\u0000周杰伦", zhou.key)
        assertEquals(1, groups.first { it.artist == "别人" }.count)
    }

    @Test
    fun `空白专辑与歌手归一成未知，未知专辑沉到最后`() {
        val groups = Albums.group(
            listOf(
                song(1, "A", "周杰伦", "叶惠美"),
                song(2, "B", null, null),
                song(3, "C", "周杰伦", "  ")   // 空白也算没有
            )
        )
        // 归一的是"空白 → 未知"，不是"把未知的都并成一张"：
        // 第 2、3 首专辑都叫「未知专辑」，但歌手不同，key 仍是两条。
        // 并成一张的话，点进去就是一堆互不相干的作者 —— 正是 AlbumGroup 要避免的。
        assertEquals(3, groups.size)
        // 有专辑名的排前面
        assertEquals("叶惠美", groups[0].album)
        assertEquals("周杰伦", groups[0].artist)
        // 两张未知专辑：带歌手的在前，连歌手都没有的垫底
        assertEquals("未知专辑\u0000周杰伦", groups[1].key)
        assertEquals("未知专辑", groups[2].album)
        assertEquals("未知歌手", groups[2].artist)
        assertEquals("未知歌手", groups.last().artist)
    }

    @Test
    fun `专辑内曲目保持传入顺序`() {
        // 组内不再排一次：曲库已经按用户选的排序排好了，这里再排就是两套顺序打架
        val groups = Albums.group(
            listOf(
                song(3, "C", "周", "专"),
                song(1, "A", "周", "专"),
                song(2, "B", "周", "专")
            )
        )
        assertEquals(listOf("C", "A", "B"), groups.single().songs.map { it.title })
    }

    @Test
    fun `专辑的合计与封面种子取自组内曲目`() {
        val groups = Albums.group(
            listOf(
                song(1, "A", "周", "专", durationMs = 60_000L),
                song(2, "B", "周", "专", durationMs = 90_000L)
            )
        )
        val group = groups.single()
        assertEquals(2, group.count)
        assertEquals(150_000L, group.durationMs)
        assertEquals(group.songs.first().artworkSeed, group.coverSeed)
    }

    // ----------------------------------------------------------------- 歌手

    @Test
    fun `按歌手归并并计数`() {
        val groups = Artists.group(
            listOf(
                song(1, "A", "周杰伦", "叶惠美"),
                song(2, "B", "周杰伦", "七里香"),
                song(3, "C", "陈奕迅", null)
            )
        )
        assertEquals(2, groups.size)
        assertEquals(2, groups.first { it.artist == "周杰伦" }.count)
        assertEquals(1, groups.first { it.artist == "陈奕迅" }.count)
        assertEquals("周杰伦", groups.first { it.artist == "周杰伦" }.key)
    }

    @Test
    fun `未知歌手沉到最后`() {
        val groups = Artists.group(listOf(song(1, "A", null), song(2, "B", "陈奕迅")))
        assertEquals("未知歌手", groups.last().artist)
        assertEquals("陈奕迅", groups.first().artist)
    }

    @Test
    fun `空列表返回空`() {
        assertTrue(Albums.group(emptyList()).isEmpty())
        assertTrue(Artists.group(emptyList()).isEmpty())
    }

    @Test
    fun `一首都没丢`() {
        val songs = listOf(
            song(1, "A", "周", "专一"),
            song(2, "B", "周", "专二"),
            song(3, "C", "陈", null),
            song(4, "D", null, null)
        )
        val flat = Albums.group(songs).flatMap { it.songs }
        assertEquals(songs.size, flat.size)
        assertEquals(songs.map { it.key }.toSet(), flat.map { it.key }.toSet())
        assertEquals(songs.size, Artists.group(songs).flatMap { it.songs }.size)
    }
}
