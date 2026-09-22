package com.melody.player

import com.melody.player.core.ArchivedEntry
import com.melody.player.core.ArchivedSongs
import com.melody.player.core.HiddenSongEntry
import com.melody.player.core.HiddenSongs
import com.melody.player.core.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档（复制到 App 专属目录）与曲库的对照逻辑。
 *
 * 这里守住的是那条把用户吓到的边界：**归档绝不等于隐藏**。归档之后曲库里
 * 该看到的是「App 库副本」，原曲只是让位；副本一旦不在，原曲必须回来 ——
 * 绝不允许出现「归档一次，整张曲库空了」。
 */
class ArchivedSongsTest {

    private fun device(id: Long, title: String) = Song(
        id = id,
        uri = "content://media/audio/$id",
        title = title,
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = 180_000L,
        sizeBytes = 4096,
        dateAddedSec = 0L
    )

    /** 归档副本：id = -1（不是媒体库曲目）+ managedName，key 因此是 "lib:<文件名>"。 */
    private fun copy(name: String, title: String = "晴天") = Song(
        id = -1L,
        uri = "file:///storage/emulated/0/Android/data/com.melody.player/files/library/$name",
        title = title,
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = 180_000L,
        sizeBytes = 4096,
        dateAddedSec = 0L,
        filePath = "/storage/emulated/0/Android/data/com.melody.player/files/library/$name",
        managedName = name
    )

    private fun entry(name: String, sourceKey: String?) = ArchivedEntry(
        name = name,
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = 180_000L,
        sizeBytes = 4096,
        archivedAtSec = 0L,
        sourceKey = sourceKey
    )

    @Test
    fun `副本在时来源让位`() {
        val entries = listOf(entry("晴天.mp3", "ms:1"))
        val copies = listOf(copy("晴天.mp3"))
        assertEquals(setOf("ms:1"), ArchivedSongs.supersededSourceKeys(entries, copies))
    }

    @Test
    fun `副本不在时来源不让位`() {
        // 记录还在、文件被删（或目录这次读不到）：原曲必须回到列表，而不是跟着消失
        val entries = listOf(entry("晴天.mp3", "ms:1"))
        assertEquals(emptySet<String>(), ArchivedSongs.supersededSourceKeys(entries, emptyList()))
    }

    @Test
    fun `只有真正加载出来的副本才算数`() {
        val entries = listOf(entry("a.mp3", "ms:1"), entry("b.mp3", "ms:2"), entry("c.mp3", "saf:9"))
        val copies = listOf(copy("a.mp3"), copy("c.mp3"))
        assertEquals(setOf("ms:1", "saf:9"), ArchivedSongs.supersededSourceKeys(entries, copies))
    }

    @Test
    fun `外部放入的文件没有来源 key 也不会带出空键`() {
        val entries = listOf(entry("拷进来的.mp3", null))
        val copies = listOf(copy("拷进来的.mp3"))
        assertEquals(emptySet<String>(), ArchivedSongs.supersededSourceKeys(entries, copies))
    }

    @Test
    fun `空输入不会崩`() {
        assertEquals(emptySet<String>(), ArchivedSongs.supersededSourceKeys(emptyList(), emptyList()))
        assertEquals(emptySet<String>(), ArchivedSongs.supersededSourceKeys(emptyList(), listOf(copy("a.mp3"))))
        assertEquals(emptySet<String>(), ArchivedSongs.supersededSourceKeys(listOf(entry("a.mp3", "ms:1")), emptyList()))
    }

    /**
     * 回归：归档整库之后，曲库不该是空的。
     *
     * 旧版把来源曲目写进「已隐藏」，副本又因为记录被清掉而加载不出来，
     * 结果两个列表抵消、曲库显示「曲目都被隐藏了」。这里把三条管线串起来跑一遍。
     */
    @Test
    fun `归档整库后列表是副本而不是空`() {
        val library = listOf(device(1, "晴天"), device(2, "夜曲"))
        val entries = listOf(entry("晴天.mp3", "ms:1"), entry("夜曲.mp3", "ms:2"))
        val copies = listOf(copy("晴天.mp3", "晴天"), copy("夜曲.mp3", "夜曲"))

        val superseded = ArchivedSongs.supersededSourceKeys(entries, copies)
        val all = library + copies
        val visible = HiddenSongs.visible(all, emptyList()).filterNot { it.key in superseded }

        assertEquals(listOf("晴天", "夜曲"), visible.map { it.title })
        assertTrue(visible.all { it.archived })
        assertFalse(visible.isEmpty())
    }

    /** 副本丢了：原曲自动回到列表，一样不能是空。 */
    @Test
    fun `副本丢失时原曲回到列表`() {
        val library = listOf(device(1, "晴天"), device(2, "夜曲"))
        val entries = listOf(entry("晴天.mp3", "ms:1"), entry("夜曲.mp3", "ms:2"))

        val superseded = ArchivedSongs.supersededSourceKeys(entries, emptyList())
        val visible = HiddenSongs.visible(library + emptyList(), emptyList()).filterNot { it.key in superseded }

        assertEquals(listOf("晴天", "夜曲"), visible.map { it.title })
    }

    @Test
    fun `迁移摘掉归档来源但保留用户手动隐藏的曲目`() {
        val entries = listOf(entry("晴天.mp3", "ms:1"), entry("夜曲.mp3", "ms:2"))
        val hidden = listOf(
            HiddenSongEntry.of(device(1, "晴天")),   // 旧版归档时自动塞进去的
            HiddenSongEntry.of(device(2, "夜曲")),   // 同上
            HiddenSongEntry.of(device(7, "录音 001")) // 用户自己隐藏的
        )

        val after = ArchivedSongs.dropSupersededHidden(hidden, entries)

        assertEquals(listOf("录音 001"), after.map { it.title })
    }

    @Test
    fun `没有归档记录时迁移不动隐藏列表`() {
        val hidden = listOf(HiddenSongEntry.of(device(1, "晴天")))
        assertEquals(hidden, ArchivedSongs.dropSupersededHidden(hidden, emptyList()))
    }

    /** 迁移 + 过滤合起来：用户升级后既看不到假的「已隐藏」，曲库也不空。 */
    @Test
    fun `升级后隐藏列表干净且曲库完整`() {
        val library = listOf(device(1, "晴天"), device(2, "夜曲"), device(7, "录音 001"))
        val entries = listOf(entry("晴天.mp3", "ms:1"), entry("夜曲.mp3", "ms:2"))
        val copies = listOf(copy("晴天.mp3", "晴天"), copy("夜曲.mp3", "夜曲"))
        val polluted = listOf(
            HiddenSongEntry.of(device(1, "晴天")),
            HiddenSongEntry.of(device(2, "夜曲")),
            HiddenSongEntry.of(device(7, "录音 001"))
        )

        val hidden = ArchivedSongs.dropSupersededHidden(polluted, entries)
        val superseded = ArchivedSongs.supersededSourceKeys(entries, copies)
        val visible = HiddenSongs.visible(library + copies, hidden).filterNot { it.key in superseded }

        assertEquals(listOf("晴天", "夜曲"), visible.map { it.title })
        assertEquals(1, hidden.size)
    }
}
