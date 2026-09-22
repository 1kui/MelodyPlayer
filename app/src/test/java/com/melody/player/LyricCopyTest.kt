package com.melody.player

import com.melody.player.core.LyricCopyFile
import com.melody.player.core.LyricCopyIndex
import com.melody.player.core.LyricCopyKeys
import com.melody.player.core.LyricCopyMeta
import com.melody.player.core.LyricOrigin
import com.melody.player.data.LyricsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词副本管理的数据层。
 *
 * 这里最要紧的一条是**磁盘为准**：索引只是"这是谁"的补充说明，丢了最多让名字难看点，
 * 绝不能让磁盘上还在的副本从列表里消失 —— 归档那次事故就是栽在「把读不到当成不存在」上。
 */
class LyricCopyTest {

    // ---------------------------------------------------------------- 索引编解码

    @Test
    fun `索引编解码闭环`() {
        val metas = listOf(
            LyricCopyMeta("ms:123", "晴天 · 网易云 · 晴天 - 周杰伦", LyricOrigin.ONLINE, 1_700_000_000L),
            LyricCopyMeta("lib:坏\\名字\t.mp3", "带\\转义\t与\n换行", LyricOrigin.ARCHIVED, 0L),
            LyricCopyMeta("net:ms:9", "自动匹配", null, 5L)
        )
        assertEquals(metas, LyricCopyIndex.decode(LyricCopyIndex.encode(metas)))
    }

    @Test
    fun `空索引编解码都安全`() {
        assertEquals(emptyList<LyricCopyMeta>(), LyricCopyIndex.decode(null))
        assertEquals(emptyList<LyricCopyMeta>(), LyricCopyIndex.decode(""))
        assertEquals(emptyList<LyricCopyMeta>(), LyricCopyIndex.decode(LyricCopyIndex.encode(emptyList())))
    }

    @Test
    fun `联网来源跟着索引一起存回来`() {
        val metas = listOf(
            LyricCopyMeta("ms:1", "晴天 · LRCLIB · 晴天 - 周杰伦", LyricOrigin.ONLINE, 1L, "LRCLIB"),
            LyricCopyMeta("net:ms:1", "晴天 · 自动匹配", LyricOrigin.ONLINE, 2L, "网易云"),
            LyricCopyMeta("ms:3", "本地导入", LyricOrigin.FILE, 3L, null)
        )
        assertEquals(listOf("LRCLIB", "网易云", null), LyricCopyIndex.decode(LyricCopyIndex.encode(metas)).map { it.container })
    }

    @Test
    fun `升级前写下的四列索引照样读得回来`() {
        // 第 5 列（联网来源）是后加的：老索引没有它，不能因此让整条记录作废
        val raw = "# melody lyrics index v1\nms:123\t晴天 · 网易云 · 晴天 - 周杰伦\tfile\t1700000000\n"
        val decoded = LyricCopyIndex.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("ms:123", decoded[0].key)
        assertEquals(LyricOrigin.FILE, decoded[0].origin)
        assertNull(decoded[0].container)
    }

    @Test
    fun `副本列表把联网来源带出来`() {
        val files = listOf(LyricCopyFile("ms_1-1a2b3c4d.lrc", 120L))
        val metas = listOf(LyricCopyMeta("net:ms:1", "自动匹配", LyricOrigin.ONLINE, 9L, "LRCLIB"))
        val list = LyricCopyIndex.merge(files, metas, mapOf("ms_1-1a2b3c4d.lrc" to "net:ms:1"))
        assertEquals(listOf("LRCLIB"), list.map { it.container })
    }

    @Test
    fun `索引解码跳过残缺行，不整份报废`() {
        val raw = buildString {
            append("# melody lyrics index v1\n")
            append("少字段行\n")
            append("ms:1\t歌\tfile\tNaN\n")          // 时间戳坏 → 归 0，其余照收
            append("\t没key\tfile\t5\n")             // 没 key → 跳过
            append("ms:2\t\t\t7\n")                  // 没名字 → 用 key 兜底
        }
        val decoded = LyricCopyIndex.decode(raw)
        assertEquals(listOf("ms:1", "ms:2"), decoded.map { it.key })
        assertEquals(0L, decoded[0].savedAtSec)
        assertEquals(LyricOrigin.FILE, decoded[0].origin)
        assertEquals("ms:2", decoded[1].label)
        assertNull(decoded[1].origin)
    }

    // ---------------------------------------------------------------- 合并

    @Test
    fun `索引是空的，磁盘上的副本也一个都不能少`() {
        val files = listOf(
            LyricCopyFile("ms_1-1a2b3c4d.lrc", 120L),
            LyricCopyFile("ms_2-5e6f7a8b.lrc", 340L)
        )
        val fileNameToKey = mapOf("ms_1-1a2b3c4d.lrc" to "ms:1", "ms_2-5e6f7a8b.lrc" to "ms:2")
        val list = LyricCopyIndex.merge(files, emptyList(), fileNameToKey)
        assertEquals(2, list.size)
        assertEquals(setOf("ms:1", "ms:2"), list.mapNotNull { it.key }.toSet())
        assertEquals(460L, list.sumOf { it.sizeBytes })
    }

    @Test
    fun `磁盘上没有的文件不会出现在列表里`() {
        val metas = listOf(
            LyricCopyMeta("ms:1", "在的", null, 1L),
            LyricCopyMeta("ms:2", "文件已被删", null, 2L)
        )
        val list = LyricCopyIndex.merge(
            files = listOf(LyricCopyFile("ms_1-1a2b3c4d.lrc", 1L)),
            metas = metas,
            fileNameToKey = mapOf(
                "ms_1-1a2b3c4d.lrc" to "ms:1",
                "ms_2-5e6f7a8b.lrc" to "ms:2"
            )
        )
        assertEquals(listOf("ms:1"), list.map { it.key })
    }

    @Test
    fun `索引里的名字优先于兜底，兜底优先于文件名反推`() {
        val files = listOf(LyricCopyFile("ms_1-1a2b3c4d.lrc", 1L))
        val map = mapOf("ms_1-1a2b3c4d.lrc" to "ms:1")

        val indexed = LyricCopyIndex.merge(
            files,
            listOf(LyricCopyMeta("ms:1", "索引名", LyricOrigin.FILE, 1L)),
            map,
            labelFallback = { "兜底名" }
        )
        assertEquals("索引名", indexed.single().label)

        val byFallback = LyricCopyIndex.merge(files, emptyList(), map, labelFallback = { "兜底名" })
        assertEquals("兜底名", byFallback.single().label)

        val orphan = LyricCopyIndex.merge(files, emptyList(), emptyMap())
        assertEquals("ms 1", orphan.single().label)
        assertNull(orphan.single().key)
        assertEquals(0L, orphan.single().savedAtSec)
    }

    @Test
    fun `按保存时间从新到旧`() {
        val files = listOf(
            LyricCopyFile("x.lrc", 1L),
            LyricCopyFile("y.lrc", 1L),
            LyricCopyFile("z.lrc", 1L)
        )
        val map = mapOf("x.lrc" to "ms:1", "y.lrc" to "ms:2", "z.lrc" to "ms:3")
        val metas = listOf(
            LyricCopyMeta("ms:1", "旧", null, 100L),
            LyricCopyMeta("ms:2", "新", null, 300L),
            LyricCopyMeta("ms:3", "中", null, 200L)
        )
        assertEquals(listOf("ms:2", "ms:3", "ms:1"), LyricCopyIndex.merge(files, metas, map).map { it.key })
    }

    @Test
    fun `索引里指向已消失文件的记录会被剔除，其它记录原样保留`() {
        val metas = listOf(
            LyricCopyMeta("ms:1", "留", null, 1L),
            LyricCopyMeta("ms:2", "走", null, 1L)
        )
        assertEquals(listOf("ms:1"), LyricCopyIndex.prune(metas, setOf("ms:1")).map { it.key })
        // 一个都不在时全丢；全在时一条不动
        assertEquals(emptyList<String>(), LyricCopyIndex.prune(metas, emptySet()).map { it.key })
        assertEquals(metas, LyricCopyIndex.prune(metas, setOf("ms:1", "ms:2")))
    }

    // ---------------------------------------------------------------- 槽位规则

    @Test
    fun `自动匹配的副本能认出它属于哪首歌`() {
        val entry = LyricCopyIndex.merge(
            files = listOf(LyricCopyFile("n.lrc", 1L)),
            metas = emptyList(),
            fileNameToKey = mapOf("n.lrc" to "net:ms:9")
        ).single()
        assertTrue(entry.autoMatched)
        assertEquals("ms:9", entry.songKey)
    }

    @Test
    fun `用户选定的副本不算自动匹配`() {
        val entry = LyricCopyIndex.merge(
            files = listOf(LyricCopyFile("u.lrc", 1L)),
            metas = emptyList(),
            fileNameToKey = mapOf("u.lrc" to "ms:9")
        ).single()
        assertFalse(entry.autoMatched)
        assertEquals("ms:9", entry.songKey)
        assertEquals("ms:9", LyricCopyKeys.baseKeyOf("ms:9"))
        assertFalse(LyricCopyKeys.isAuto("ms:9"))
    }

    @Test
    fun `自动匹配槽位生成的文件名不会和同一首歌的选定副本撞车`() {
        val picked = LyricsStore.fileNameFor("ms:12345")
        val auto = LyricsStore.fileNameFor(LyricCopyKeys.AUTO_MATCH_PREFIX + "ms:12345")
        assertTrue(picked != auto)
    }

    // ---------------------------------------------------------------- 文件名兜底

    @Test
    fun `文件名反推的兜底名去掉哈希尾巴与扩展名`() {
        assertEquals("ms 123", LyricCopyIndex.labelFromFileName("ms_123-1a2b3c4d.lrc"))
        assertEquals("lib mp3", LyricCopyIndex.labelFromFileName("lib_mp3-0a0b0c0d.lrc"))
        assertEquals("plain", LyricCopyIndex.labelFromFileName("plain.lrc"))
        // 没有扩展名也不能炸
        assertEquals("noext", LyricCopyIndex.labelFromFileName("noext"))
    }
}
