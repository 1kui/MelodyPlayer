package com.melody.player

import com.melody.player.core.LrcParser
import com.melody.player.core.tags.AudioTagReader
import com.melody.player.core.tags.ByteArraySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌歌词提取的端到端测试。
 *
 * 每个用例都在内存里造出一个符合规范的容器字节，覆盖到真实的字节解析路径
 * （syncsafe 长度、UTF-16 BOM、LE 长度、moov 在尾部、meta 的 FullBox 前缀等），
 * 比 mock 掉解析器有意义得多。
 */
class AudioTagReaderTest {

    private fun read(bytes: ByteArray) = AudioTagReader.extract(ByteArraySource(bytes))

    private val longChineseLyrics = buildString {
        appendLine("[ti:测试歌曲]")
        appendLine("[ar:测试歌手]")
        repeat(24) { index ->
            // 每秒一句，覆盖 00:00 ~ 00:23
            appendLine("[%02d:%02d.00]这是第 %d 句中文歌词，用来把标签撑到 128 字节以上".format(index / 60, index % 60, index))
        }
    }

    @Test
    fun `extracts USLT from ID3v2 3 with latin1`() {
        val result = read(TagFixtures.id3v2Uslt("Hello melody"))
        assertNotNull(result)
        assertEquals("Hello melody", result!!.text)
        assertEquals("ID3v2.3", result.container)
    }

    @Test
    fun `extracts USLT from ID3v2 4 with utf8 and long payload`() {
        // payload 远超 127 字节，能验证 v2.4 的 syncsafe 帧长度解析
        val result = read(TagFixtures.id3v24UsltUtf8(longChineseLyrics))
        assertNotNull(result)
        assertTrue(result!!.text!!.contains("这是第 0 句中文歌词"))
        assertTrue(result.text!!.contains("这是第 23 句中文歌词"))
        assertEquals("ID3v2.4", result.container)
    }

    @Test
    fun `extracts USLT with utf16 bom`() {
        val result = read(TagFixtures.id3v2UsltUtf16("中文歌词测试"))
        assertNotNull(result)
        assertEquals("中文歌词测试", result!!.text)
    }

    @Test
    fun `extracts title artist album together with lyrics`() {
        val result = read(
            TagFixtures.id3v2WithTagsAndLyrics(
                title = "夜曲",
                artist = "周杰伦",
                album = "十一月的萧邦",
                lyrics = "[00:01.00]一群嗜血的蚂蚁"
            )
        )
        assertNotNull(result)
        assertEquals("夜曲", result!!.title)
        assertEquals("周杰伦", result.artist)
        assertEquals("十一月的萧邦", result.album)
        assertEquals("[00:01.00]一群嗜血的蚂蚁", result.text)
    }

    @Test
    fun `extracts SYLT as synced lines`() {
        val result = read(
            TagFixtures.id3v24Sylt(
                listOf(
                    1_000L to "第一句",
                    5_500L to "第二句",
                    12_000L to "第三句"
                )
            )
        )
        assertNotNull(result)
        assertEquals(3, result!!.synced.size)
        assertEquals(1_000L, result.synced[0].timeMs)
        assertEquals("第二句", result.synced[1].text)
        // SYLT 是按顺序读取的，解析后应保持时间升序
        assertTrue(result.synced.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
    }

    @Test
    fun `extracts lyrics stored in TXXX descriptor`() {
        val result = read(TagFixtures.id3v24Txxx("LYRICS", "[00:02.00]TXXX 里的歌词"))
        assertNotNull(result)
        assertEquals("[00:02.00]TXXX 里的歌词", result!!.text)
    }

    @Test
    fun `ignores TXXX with unrelated descriptor`() {
        val result = read(TagFixtures.id3v24Txxx("MOOD", "happy"))
        assertNull(result)
    }

    @Test
    fun `returns null for tag without lyrics`() {
        assertNull(read(TagFixtures.id3v2WithoutLyrics()))
    }

    @Test
    fun `extracts FLAC vorbis comment lyrics`() {
        val result = read(
            TagFixtures.flac(
                mapOf(
                    "TITLE" to "FLAC 测试",
                    "ARTIST" to "测试歌手",
                    "LYRICS" to "[00:03.00]FLAC 里的歌词"
                )
            )
        )
        assertNotNull(result)
        assertEquals("[00:03.00]FLAC 里的歌词", result!!.text)
        assertEquals("FLAC 测试", result.title)
        assertEquals("FLAC Vorbis Comment", result.container)
    }

    @Test
    fun `accepts unsynchronised lyrics key spelling in FLAC`() {
        val result = read(TagFixtures.flac(mapOf("UNSYNCEDLYRICS" to "拼写变体也要认")))
        assertNotNull(result)
        assertEquals("拼写变体也要认", result!!.text)
    }

    @Test
    fun `returns null when FLAC has no lyrics key`() {
        assertNull(read(TagFixtures.flac(mapOf("TITLE" to "只有标题"))))
    }

    @Test
    fun `extracts ogg vorbis comment lyrics`() {
        val result = read(TagFixtures.oggVorbis(mapOf("TITLE" to "Ogg 测试", "LYRICS" to "[00:04.00]Ogg 里的歌词")))
        assertNotNull(result)
        assertEquals("[00:04.00]Ogg 里的歌词", result!!.text)
        assertEquals("Ogg 测试", result.title)
    }

    @Test
    fun `extracts opus tags lyrics`() {
        val result = read(TagFixtures.opusTags(mapOf("LYRICS" to "[00:05.00]Opus 里的歌词")))
        assertNotNull(result)
        assertEquals("[00:05.00]Opus 里的歌词", result!!.text)
        assertEquals("OpusTags Comment", result.container)
    }

    @Test
    fun `extracts MP4 lyrics atom`() {
        val result = read(TagFixtures.mp4("[00:06.00]MP4 里的歌词"))
        assertNotNull(result)
        assertEquals("[00:06.00]MP4 里的歌词", result!!.text)
        assertEquals("MP4 ©lyr", result.container)
    }

    @Test
    fun `extracts MP4 lyrics when moov is at end of file`() {
        // 非 faststart 的文件把 moov 放在 mdat 之后，只有支持随机读取才能拿到
        val result = read(TagFixtures.mp4WithTrailingMoov("[00:07.00]尾部 moov 的歌词"))
        assertNotNull(result)
        assertEquals("[00:07.00]尾部 moov 的歌词", result!!.text)
    }

    @Test
    fun `extracts WAV id3 chunk lyrics`() {
        val id3 = TagFixtures.id3v24UsltUtf8("[00:08.00]WAV 里的歌词")
        val result = read(TagFixtures.wavWithId3Chunk(id3))
        assertNotNull(result)
        assertEquals("[00:08.00]WAV 里的歌词", result!!.text)
    }

    @Test
    fun `returns null for unrecognised container`() {
        assertNull(read("not a real audio file at all, just text".toByteArray()))
        assertNull(read(ByteArray(0)))
        assertNull(read(ByteArray(3) { 0x7F }))
    }

    @Test
    fun `truncated ID3 header does not crash`() {
        val full = TagFixtures.id3v24UsltUtf8("歌词")
        // 只保留前 4 字节：不足一个完整文件名头，必须安全返回 null 而不是抛异常
        assertNull(read(full.copyOfRange(0, 4)))
    }

    @Test
    fun `reports supported container types`() {
        assertTrue(AudioTagReader.isSupported(ByteArraySource(TagFixtures.id3v2Uslt("x"))))
        assertTrue(AudioTagReader.isSupported(ByteArraySource(TagFixtures.flac(mapOf("A" to "B")))))
        assertTrue(AudioTagReader.isSupported(ByteArraySource(TagFixtures.oggVorbis(mapOf("A" to "B")))))
        assertTrue(AudioTagReader.isSupported(ByteArraySource(TagFixtures.mp4("x"))))
        assertTrue(!AudioTagReader.isSupported(ByteArraySource("plain text payload".toByteArray())))
    }

    @Test
    fun `extracted LRC text feeds straight into the LRC parser`() {
        // 真实播放路径就是「先抽内嵌文本，再当 LRC 解析」，这里把两步连起来验证
        val result = read(TagFixtures.id3v24UsltUtf8(longChineseLyrics))
        assertNotNull(result)
        val parsed = LrcParser.parse(result!!.text!!)
        assertTrue(parsed.hasTimestamps)
        assertEquals(24, parsed.lines.size)
        assertEquals("测试歌曲", parsed.title)
        assertEquals(0L, parsed.lines[0].timeMs)
        assertEquals(1_000L, parsed.lines[1].timeMs)
    }
}
