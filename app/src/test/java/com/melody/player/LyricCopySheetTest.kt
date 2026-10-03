package com.melody.player

import com.melody.player.core.LyricCopyEntry
import com.melody.player.core.LyricCopyGroup
import com.melody.player.core.LyricOrigin
import com.melody.player.ui.components.lyricCopyGroupSubtitle
import com.melody.player.ui.components.lyricCopySubtitle
import com.melody.player.ui.components.lyricCopiesSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「歌词副本」弹层里的那几句文案。
 *
 * 值得单测不是因为拼字符串难，而是这几句话各自在**顶一个具体的误解**：
 *  - 副标题里的"含 N 份自动匹配缓存"：不说的话，用户删掉缓存那份、
 *    下次播到又冒出来，会以为删除没生效；
 *  - 一条副本的"联网自动匹配 / 联网获取"：同一个来源、两个结果（一个是缓存、
 *    一个是手动选的），混着写用户就没法判断删哪一份；
 *  - 空状态那段：副本是"原文件没了之后的最后一份"，这件事只能在没副本时讲。
 */
class LyricCopySheetTest {

    private fun entry(
        fileName: String = "a.lrc",
        key: String? = "ms:1",
        label: String = fileName,
        origin: LyricOrigin? = LyricOrigin.FILE,
        sizeBytes: Long = 460L,
        savedAtSec: Long = 0L,
        container: String? = null
    ) = LyricCopyEntry(
        fileName = fileName,
        key = key,
        label = label,
        origin = origin,
        savedAtSec = savedAtSec,
        sizeBytes = sizeBytes,
        container = container
    )

    private fun group(vararg entries: LyricCopyEntry, songKey: String? = "ms:1", title: String = "晴天") =
        LyricCopyGroup(songKey = songKey, title = title, entries = entries.toList())

    // ---------------------------------------------------------------- 一份副本

    @Test
    fun `来源文案区分导入、联网选定、联网缓存与归档快照`() {
        assertEquals("导入的 .lrc", lyricCopySubtitle(entry()).substringBefore(" · "))
        assertEquals(
            "联网获取",
            lyricCopySubtitle(entry(origin = LyricOrigin.ONLINE, key = "ms:1")).substringBefore(" · ")
        )
        // 同一个来源、两种结果：缓存那份删了还会回来，不能和"选定"那份说成一样
        assertEquals(
            "联网自动匹配",
            lyricCopySubtitle(entry(origin = LyricOrigin.ONLINE, key = "net:ms:1")).substringBefore(" · ")
        )
        assertEquals(
            "归档时的快照",
            lyricCopySubtitle(entry(origin = LyricOrigin.ARCHIVED)).substringBefore(" · ")
        )
        assertEquals("来源未知", lyricCopySubtitle(entry(origin = null)).substringBefore(" · "))
    }

    @Test
    fun `联网来源要写清是哪一家`() {
        // 两家来源的曲库不同，管理副本时要能看出这份到底是哪来的
        val text = lyricCopySubtitle(entry(origin = LyricOrigin.ONLINE, container = "网易云"))
        assertTrue(text, text.contains("网易云"))
        // 本地副本没有"哪一家"，不许留个空槽
        assertTrue(!lyricCopySubtitle(entry()).contains("·  · "))
    }

    @Test
    fun `一份副本的副标题依次是来源、大小、时间`() {
        assertEquals("导入的 .lrc · 460 B · 保存时间未知", lyricCopySubtitle(entry()))
    }

    // ---------------------------------------------------------------- 一首歌

    @Test
    fun `组头说清几份、多大、最近什么时候存的`() {
        val subtitle = lyricCopyGroupSubtitle(
            group(entry(sizeBytes = 460L), entry(fileName = "b.lrc", key = "net:ms:1", sizeBytes = 1024L))
        )
        assertTrue(subtitle, subtitle.startsWith("2 份 · 1 KB · "))
    }

    // ---------------------------------------------------------------- 弹层副标题

    @Test
    fun `一首都没点名到副本时不画副标题`() {
        // 空状态里已经有一整段说明，再顶一行"共 0 份"是重复
        assertEquals("", lyricCopiesSummary(emptyList(), requestedCount = 1))
        assertEquals("", lyricCopiesSummary(listOf(group(songKey = null, title = "未关联的副本")), 2))
    }

    @Test
    fun `单首时只说份数，不说已选几首`() {
        val summary = lyricCopiesSummary(listOf(group(entry())), requestedCount = 1)
        assertEquals("共 1 份 · 460 B", summary)
    }

    @Test
    fun `多选时先交代作用范围`() {
        // 从多选进来的，用户要先确认"我选的那几首"对不对
        val summary = lyricCopiesSummary(listOf(group(entry())), requestedCount = 3)
        assertEquals("已选 3 首 · 共 1 份 · 460 B", summary)
    }

    @Test
    fun `有自动匹配缓存时单独说一句`() {
        val summary = lyricCopiesSummary(
            listOf(group(entry(sizeBytes = 460L), entry(fileName = "b.lrc", key = "net:ms:1", sizeBytes = 1024L))),
            requestedCount = 1
        )
        assertEquals("共 2 份 · 1 KB · 含 1 份自动匹配缓存", summary)
    }
}
