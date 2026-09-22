package com.melody.player

import com.melody.player.core.CoverEntry
import com.melody.player.data.CoverStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面缓存的文件名规则。
 *
 * [CoverStore] 本身要 Context 才能实例化（JVM 单测里 `context.filesDir` 是空壳），
 * 但命名是个纯函数 —— 而它恰好是这块**唯一会静默出错**的地方：
 * 名字撞了就是"给这首歌唱另一张专辑的封面"，界面上一切正常，只有用户看得出来。
 */
class CoverStoreTest {

    @Test
    fun `同一个 key 每次算出来的文件名都一样`() {
        // 不稳定的话，重启一次 App 就再也找不到上次缓存的图，表现为"封面老是重新下载"
        val key = "ms:12345"
        assertEquals(CoverStore.fileNameFor(key), CoverStore.fileNameFor(key))
    }

    @Test
    fun `不同歌曲不会落到同一个文件`() {
        // 这一条针对的是 [com.melody.player.data.LyricsStore] 那种"清洗 + hashCode"的命名：
        // 清洗会把 `:`、`/`、中文全都变成下划线，于是 `ms:1` 与 `ms/1` 的可读前缀完全相同，
        // 只剩 hashCode 兜着；这里用 SHA-1，必须区分得开
        val a = CoverStore.fileNameFor("ms:1")
        val b = CoverStore.fileNameFor("ms/1")
        val c = CoverStore.fileNameFor("ms_1")
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, c)

        // 一批真实形态的 key 两两不重名
        val keys = listOf(
            "ms:1", "ms:2", "ms:10", "saf:-123456", "lib:晴天.mp3", "lib:晴天 (2).mp3",
            "lib:周杰倫 - 葉惠美.flac", "lib:😀.mp3", "lib:../逃逸.mp3"
        )
        val names = keys.map { CoverStore.fileNameFor(it) }
        assertEquals(keys.size, names.toSet().size)
    }

    @Test
    fun `文件名里不会出现会落盘失败的字符`() {
        // 冒号、斜杠、反斜杠在 Android 上要么建不了文件、要么直接落到别的目录去
        val nasty = listOf("ms:1", "lib:a/b\\c.mp3", "lib:..\\..\\x.mp3", "lib:?*\"<>|.mp3")
        nasty.forEach { key ->
            val name = CoverStore.fileNameFor(key)
            assertFalse("$key -> $name 里不该有路径分隔符", name.contains('/'))
            assertFalse("$key -> $name 里不该有反斜杠", name.contains('\\'))
            assertFalse("$key -> $name 里不该有冒号", name.contains(':'))
            // 只允许当前目录下的直接子文件名，杜绝 ../ 之类的路径穿越
            assertEquals(name, java.io.File(name).name)
            assertTrue(name.endsWith(".jpg"))
        }
    }

    @Test
    fun `超长歌名不会撑出文件系统的长度上限`() {
        // Android 上单段文件名超过 255 字节就会创建失败。中文一个字三字节，
        // 不截断的话一首长名字的歌就直接存不下封面了
        val long = "lib:" + "这是一个非常非常长的歌名".repeat(30) + ".flac"
        val name = CoverStore.fileNameFor(long)
        assertTrue("$name 太长了", name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun `认得出标签里没有可用文字的歌名`() {
        // 全是会被清洗掉的字符时不能给出一个空前缀的文件名（会变成 ".jpg" 这种隐藏文件）
        val name = CoverStore.fileNameFor("::::")
        assertTrue(name.endsWith(".jpg"))
        assertFalse(name.startsWith("."))
        assertTrue(name.length > 8)
    }

    @Test
    fun `负结果与命中结果在记录里能区分开`() {
        // fileName 为空 = 查过但没匹配上。这个区分决定了界面是"再试一次"还是"就是没有封面"，
        // 而两者都不显示图片 —— 混在一起的话，一首搜不到的歌会被无限重搜
        val miss = CoverEntry(trackId = 0L, fileName = "", sourceUrl = null, fetchedAtSec = 1L)
        val hit = CoverEntry(
            trackId = 1193701400L,
            fileName = "lib_perfect-1a2b3c4d5e6f7081.jpg",
            sourceUrl = "https://is1-ssl.mzstatic.com/x/1000x1000bb.jpg",
            fetchedAtSec = 2L
        )
        assertFalse(miss.matched)
        assertTrue(hit.matched)
        assertNotEquals(miss.matched, hit.matched)
    }
}
