package com.melody.player

import com.melody.player.core.LyricCopyEntry
import com.melody.player.core.LyricCopyGroup
import com.melody.player.core.LyricCopyGroups
import com.melody.player.core.LyricOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 歌词副本「按歌曲归并」。
 *
 * 这块逻辑服务的是一个很具体的诉求：**我不想按份收拾歌词，我想按歌收拾**。
 * 出错的后果都不小 —— 分错组会让用户以为某首歌的歌词丢了；把认不回歌曲的孤儿
 * 副本丢掉更糟：磁盘上明明还在，界面上却看不到，只能在文件管理器里翻。
 */
class LyricCopyGroupsTest {

    private fun entry(
        fileName: String,
        key: String?,
        label: String = fileName,
        savedAtSec: Long = 0L,
        sizeBytes: Long = 0L,
        origin: LyricOrigin? = null
    ) = LyricCopyEntry(
        fileName = fileName,
        key = key,
        label = label,
        origin = origin,
        savedAtSec = savedAtSec,
        sizeBytes = sizeBytes
    )

    private val titles = mapOf("ms:1" to "晴天", "ms:2" to "七里香", "lib:晴天.mp3" to "晴天")

    // ------------------------------------------------------------ 归并的分组

    @Test
    fun `同一首歌的选定副本与自动匹配缓存归到同一组`() {
        val groups = LyricCopyGroups.group(
            listOf(
                entry("auto.lrc", "net:ms:1", "晴天 · 自动匹配", savedAtSec = 900L),
                entry("picked.lrc", "ms:1", "晴天 · 网易云", savedAtSec = 100L)
            ),
            titleOf = { titles[it] }
        )

        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals("ms:1", group.songKey)
        assertEquals("晴天", group.title)
        assertEquals(2, group.count)
        assertEquals(1, group.autoCount)
        // 选定那份是播放时真正生效的，必须排最前
        assertEquals("picked.lrc", group.entries.first().fileName)
        assertEquals("picked.lrc", group.selected?.fileName)
        assertEquals(900L, group.latestSavedAtSec)
    }

    @Test
    fun `只有自动匹配的一份时 selected 为空`() {
        val group = LyricCopyGroups.group(
            listOf(entry("auto.lrc", "net:ms:1", "晴天 · 自动匹配")),
            titleOf = { titles[it] }
        ).single()

        assertNull(group.selected)
        assertEquals(1, group.autoCount)
    }

    @Test
    fun `认不回歌曲的副本归到未关联一组并排在最后`() {
        val groups = LyricCopyGroups.group(
            listOf(
                entry("orphan-b.lrc", null, "b", savedAtSec = 5_000L),
                entry("known.lrc", "ms:1", "晴天", savedAtSec = 1L),
                entry("orphan-a.lrc", null, "a", savedAtSec = 4_000L)
            ),
            titleOf = { titles[it] }
        )

        assertEquals(2, groups.size)
        assertEquals("ms:1", groups.first().songKey)
        val orphan = groups.last()
        assertNull(orphan.songKey)
        assertEquals(LyricCopyGroups.ORPHAN_TITLE, orphan.title)
        // 孤儿一组也要有稳定的展开标识 —— 否则界面记不住它展开过
        assertEquals(LyricCopyGroup.ORPHAN_EXPAND_KEY, orphan.expandKey)
        // 同组内按时间从新到旧
        assertEquals(listOf("orphan-b.lrc", "orphan-a.lrc"), orphan.entries.map { it.fileName })
    }

    @Test
    fun `组之间按组内最近保存时间从新到旧`() {
        val groups = LyricCopyGroups.group(
            listOf(
                entry("a-old.lrc", "ms:1", "晴天", savedAtSec = 100L),
                entry("a-new.lrc", "ms:1", "晴天", savedAtSec = 800L),
                entry("b.lrc", "ms:2", "七里香", savedAtSec = 500L)
            ),
            titleOf = { titles[it] }
        )
        assertEquals(listOf("ms:1", "ms:2"), groups.map { it.songKey })
    }

    @Test
    fun `归档副本与它自己的曲目算两首歌，不会互相并进同一组`() {
        val groups = LyricCopyGroups.group(
            listOf(
                entry("lib.lrc", "lib:晴天.mp3", "晴天 · 归档快照", savedAtSec = 10L),
                entry("ms.lrc", "ms:1", "晴天 · 网易云", savedAtSec = 20L)
            ),
            titleOf = { titles[it] }
        )
        assertEquals(listOf("ms:1", "lib:晴天.mp3"), groups.map { it.songKey })
        assertEquals(listOf("晴天", "晴天"), groups.map { it.title })
    }

    // ------------------------------------------------------------ 组名

    @Test
    fun `组名优先用曲库里的歌名`() {
        val group = LyricCopyGroups.group(
            listOf(entry("x.lrc", "ms:1", "标签里的名字 · 网易云")),
            titleOf = { titles[it] }
        ).single()
        assertEquals("晴天", group.title)
    }

    @Test
    fun `曲库里查不到时退回标签里的歌名前缀`() {
        // 原文件被删、媒体库还没扫到、授权失效 —— 都会走到这条降级路径
        val group = LyricCopyGroups.group(
            listOf(entry("x.lrc", "ms:9", "告白气球 · 网易云 · 告白气球 - 周杰伦")),
            titleOf = { titles[it] }
        ).single()
        assertEquals("告白气球", group.title)
    }

    @Test
    fun `标签退化成空白时也不给空标题`() {
        // 标题为空在界面上就是一行空白，看着像渲染失败；宁可写「未知曲目」
        val blank = LyricCopyGroups.group(listOf(entry("x.lrc", "ms:9", "   ")), { null }).single()
        assertEquals("未知曲目", blank.title)

        // 曲库里没有、标签只有槽位名（形如 ms_9-1a2b3c4d 反推出来的）时，至少不是空白
        val raw = LyricCopyGroups.group(listOf(entry("y.lrc", "ms:9", "ms 9")), { null }).single()
        assertEquals("ms 9", raw.title)
    }

    // ------------------------------------------------------------ 合计与边界

    @Test
    fun `份数与字节数是组内合计`() {
        val group = LyricCopyGroups.group(
            listOf(
                entry("a.lrc", "ms:1", "晴天", sizeBytes = 120L),
                entry("b.lrc", "net:ms:1", "晴天 · 自动匹配", sizeBytes = 340L)
            ),
            titleOf = { titles[it] }
        ).single()
        assertEquals(2, group.count)
        assertEquals(460L, group.sizeBytes)
    }

    @Test
    fun `空输入返回空列表`() {
        assertEquals(emptyList<LyricCopyGroup>(), LyricCopyGroups.group(emptyList()) { null })
    }

    @Test
    fun `一份副本都没丢`() {
        val entries = listOf(
            entry("a.lrc", "ms:1", "晴天"),
            entry("b.lrc", "net:ms:1", "晴天 · 自动匹配"),
            entry("c.lrc", null, "c"),
            entry("d.lrc", "ms:2", "七里香")
        )
        val flat = LyricCopyGroups.group(entries) { titles[it] }.flatMap { it.entries }
        assertEquals(entries.size, flat.size)
        assertEquals(entries.map { it.fileName }.toSet(), flat.map { it.fileName }.toSet())
    }
}
