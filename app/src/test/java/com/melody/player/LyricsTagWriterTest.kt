package com.melody.player

import com.melody.player.core.ArchivedEntry
import com.melody.player.core.ArchivedSongs
import com.melody.player.core.LyricLine
import com.melody.player.core.Lyrics
import com.melody.player.core.LyricsAligner
import com.melody.player.core.LyricsSource
import com.melody.player.core.TimeFormat
import com.melody.player.core.tags.AudioTagReader
import com.melody.player.core.tags.ByteArraySource
import com.melody.player.core.tags.LyricsTagWriter
import com.melody.player.data.LyricsStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「歌词写回音频文件」的端到端校验。
 *
 * 这些用例的价值在于**闭环**：不是检查我写出去的字节长得像不像标签，而是把写出来的
 * 文件重新喂给本工程的解析器（[AudioTagReader]），确认真的能读出歌词。写入与解析是
 * 两套独立代码，只有这样才不会出现「自己写的自己认、播放器不认」。
 */
class LyricsTagWriterTest {

    private val newLyrics = "[00:05.00]新歌词第一行\n[00:12.50]新歌词第二行"
    private val oldLyrics = "[00:01.00]旧歌词应当被替换"

    /** 伪造音频数据：可预测，一旦拷贝错位立刻能看出来。 */
    private fun audio(size: Int, seed: Int = 0) = ByteArray(size) { ((it * 7 + seed) % 251).toByte() }

    /** MPEG1 Layer3 帧同步字开头，后面是伪造的音频负载。 */
    private fun untaggedMp3(size: Int = 3000): ByteArray {
        val bytes = audio(size, seed = 3)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xFB.toByte()
        return bytes
    }

    private fun utf16Payload(value: String): ByteArray =
        Bytes().u8(1).u8(0xFF).u8(0xFE).raw(value.toByteArray(Charsets.UTF_16LE)).toByteArray()

    /** 手工拼一个 v2.3 标签：TIT2 + 可选额外帧 + 可选的旧 USLT。 */
    private fun id3v23(title: String?, extra: ByteArray? = null, lyrics: String? = null): ByteArray {
        val frames = Bytes()
        title?.let { frames.raw(id3Frame("TIT2", utf16Payload(it))) }
        extra?.let { frames.raw(it) }
        lyrics?.let {
            val payload = Bytes().u8(1).ascii("eng").u16be(0).u8(0xFF).u8(0xFE)
                .raw(it.toByteArray(Charsets.UTF_16LE)).toByteArray()
            frames.raw(id3Frame("USLT", payload))
        }
        val body = frames.toByteArray()
        return Bytes().ascii("ID3").u8(3).u8(0).u8(0).syncSafe(body.size).raw(body).toByteArray()
    }

    /** 按重写方案把文件重新拼出来，尽量贴近真实写入路径。 */
    private fun apply(original: ByteArray, rewrite: LyricsTagWriter.Rewrite): ByteArray =
        rewrite.newHead + original.copyOfRange(rewrite.skipBytes.toInt(), original.size)

    private fun readLyrics(file: ByteArray) = AudioTagReader.extract(ByteArraySource(file))

    private fun rewrite(original: ByteArray, lyrics: String = newLyrics): LyricsTagWriter.Rewrite {
        val result = LyricsTagWriter.rewrite(original, lyrics)
        assertNotNull("应当能写入这个容器", result)
        return result!!
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // ------------------------------------------------------------------ MP3

    @Test
    fun `写进已有的 v2_3 标签并替换掉旧歌词`() {
        val original = id3v23(title = "原来这首歌", lyrics = oldLyrics) + audio(4000)

        val plan = rewrite(original)
        assertTrue(plan.embedded)
        assertEquals("MP3 · ID3v2.3 USLT", plan.container)

        // 音频数据一个字节都不能错位
        val rebuilt = apply(original, plan)
        assertArrayEquals(audio(4000), rebuilt.copyOfRange(plan.newHead.size, rebuilt.size))

        val embedded = readLyrics(rebuilt)
        assertNotNull(embedded)
        assertTrue("新歌词应当能读回来", embedded!!.text!!.contains("新歌词第二行"))
        assertFalse("旧歌词必须被替换掉", embedded.text!!.contains("旧歌词"))
        assertEquals("原来这首歌", embedded.title)
    }

    @Test
    fun `保留封面等无关帧`() {
        val cover = id3Frame("APIC", audio(600, seed = 11))
        val original = id3v23(title = "带封面", extra = cover, lyrics = oldLyrics) + audio(1000)

        val plan = rewrite(original)
        // 重建标签最容易丢的就是封面，这里必须原样保留
        assertTrue("封面帧应当被保留", indexOf(plan.newHead, cover) >= 0)

        val rebuilt = apply(original, plan)
        assertEquals("带封面", readLyrics(rebuilt)!!.title)
    }

    @Test
    fun `没有标签的 MP3 会新建 v2_3 标签且不动音频`() {
        val original = untaggedMp3()

        val plan = rewrite(original)
        assertEquals(0L, plan.skipBytes)
        assertTrue(plan.newHead.copyOfRange(0, 3).contentEquals("ID3".toByteArray(Charsets.ISO_8859_1)))
        // 音频从第 0 字节开始，必须完整保留
        val rebuilt = plan.newHead + original
        assertArrayEquals(original, rebuilt.copyOfRange(plan.newHead.size, rebuilt.size))

        val embedded = readLyrics(rebuilt)!!
        assertTrue(embedded.text!!.contains("新歌词第一行"))
    }

    @Test
    fun `新建标签时会补上标题歌手专辑`() {
        val original = untaggedMp3()
        val plan = LyricsTagWriter.rewrite(
            original,
            newLyrics,
            LyricsTagWriter.Meta(title = "无标签的歌", artist = "歌手", album = "专辑")
        )!!

        val embedded = readLyrics(plan.newHead + original)!!
        assertEquals("无标签的歌", embedded.title)
        assertEquals("歌手", embedded.artist)
        assertEquals("专辑", embedded.album)
    }

    @Test
    fun `v2_4 标签用 syncsafe 长度重写`() {
        val body = Bytes()
            .raw(id3Frame24("TIT2", Bytes().u8(3).raw("v24 标题".toByteArray(Charsets.UTF_8)).toByteArray()))
            .raw(
                id3Frame24(
                    "USLT",
                    Bytes().u8(3).ascii("eng").u8(0).raw(oldLyrics.toByteArray(Charsets.UTF_8)).toByteArray()
                )
            )
            .toByteArray()
        val original = Bytes().ascii("ID3").u8(4).u8(0).u8(0).syncSafe(body.size).raw(body).toByteArray() +
            audio(2500)

        val plan = rewrite(original)
        assertEquals("MP3 · ID3v2.4 USLT", plan.container)

        val embedded = readLyrics(apply(original, plan))!!
        assertTrue(embedded.text!!.contains("新歌词第二行"))
        assertEquals("v24 标题", embedded.title)
    }

    @Test
    fun `超长歌词不会被截断`() {
        val long = buildString {
            repeat(400) { append("[%02d:%02d.00]".format(it / 60, it % 60)).append("第 $it 行歌词内容\n") }
        }.trimEnd()
        val original = id3v23(title = "长歌词") + audio(800)

        val embedded = readLyrics(apply(original, rewrite(original, long)))!!
        assertEquals(long, embedded.text)
    }

    @Test
    fun `整标签 unsynchronisation 的文件选择不写而不是写坏`() {
        val body = id3Frame("TIT2", utf16Payload("x"))
        val original = Bytes().ascii("ID3").u8(3).u8(0).u8(0x80).syncSafe(body.size).raw(body).toByteArray() +
            audio(100)

        assertNull(LyricsTagWriter.rewrite(original, newLyrics))
    }

    // ------------------------------------------------------------------ FLAC

    /** 手工拼 FLAC：STREAMINFO + PICTURE + 可选的 VORBIS_COMMENT（结尾块）。 */
    private fun flacWith(title: String, oldLyric: String?): ByteArray {
        val b = Bytes().ascii("fLaC")
        b.u8(0x00).u24be(34).raw(audio(34, seed = 1)) // STREAMINFO
        b.u8(0x06).u24be(48).raw(audio(48, seed = 2)) // PICTURE（封面）
        val entries = buildList {
            add("TITLE=$title")
            add("ARTIST=测试歌手")
            oldLyric?.let { add("LYRICS=$it") }
        }
        val payload = Bytes().u32le(7L).ascii("melody!").u32le(entries.size.toLong()).apply {
            entries.forEach { entry ->
                val bytes = entry.toByteArray(Charsets.UTF_8)
                u32le(bytes.size.toLong()).raw(bytes)
            }
        }.toByteArray()
        b.u8(0x80 or 4).u24be(payload.size).raw(payload)
        return b.toByteArray()
    }

    @Test
    fun `FLAC 写入 LYRICS 并保留封面块`() {
        val original = flacWith(title = "无损曲目", oldLyric = oldLyrics) + audio(5000, seed = 9)

        val plan = rewrite(original)
        assertEquals("FLAC · Vorbis Comment", plan.container)

        val rebuilt = apply(original, plan)
        assertArrayEquals(
            original.copyOfRange(plan.skipBytes.toInt(), original.size),
            rebuilt.copyOfRange(plan.newHead.size, rebuilt.size)
        )
        // 封面块原样还在，STREAMINFO 仍是第一块
        assertTrue(indexOf(plan.newHead, audio(48, seed = 2)) >= 0)
        assertEquals(0, plan.newHead[4].toInt() and 0x7F)

        val embedded = readLyrics(rebuilt)!!
        assertEquals("无损曲目", embedded.title)
        assertTrue(embedded.text!!.contains("新歌词第二行"))
        assertFalse(embedded.text!!.contains("旧歌词"))
    }

    @Test
    fun `FLAC 原本没有注释块时能新建一个`() {
        val b = Bytes().ascii("fLaC")
        b.u8(0x80).u24be(34).raw(audio(34, seed = 1)) // STREAMINFO 同时是结尾块
        val original = b.toByteArray() + audio(600)

        val embedded = readLyrics(apply(original, rewrite(original)))!!
        assertTrue(embedded.text!!.contains("新歌词第一行"))
    }

    // ------------------------------------------------------------------ 不支持的容器

    @Test
    fun `不适合原地重写的容器返回 null`() {
        // M4A：前 4 字节是原子长度
        val m4a = Bytes().u32be(32L).ascii("ftypM4A ").raw(audio(64)).toByteArray()
        assertNull(LyricsTagWriter.rewrite(m4a, newLyrics))

        // WAV
        val wav = Bytes().ascii("RIFF").u32le(64).ascii("WAVE").raw(audio(64)).toByteArray()
        assertNull(LyricsTagWriter.rewrite(wav, newLyrics))

        // 空歌词、空文件
        assertNull(LyricsTagWriter.rewrite(m4a, "   "))
        assertNull(LyricsTagWriter.rewrite(ByteArray(0), newLyrics))
    }
}

/** 歌词导出、归档记录、歌词副本命名的纯逻辑单测。 */
class LyricsExportAndArchiveTest {

    @Test
    fun `原始 LRC 文本原样导出`() {
        val raw = "[ti:标题]\n[00:01.00]第一行\n[00:02.00]第二行"
        val lyrics = Lyrics(
            lines = LyricsAligner.estimate(listOf("第一行", "第二行"), 10_000),
            synced = true,
            estimated = false,
            source = LyricsSource.IMPORTED_LRC,
            rawText = raw
        )
        assertEquals(raw, lyrics.toLrcText())
    }

    @Test
    fun `没有原文时按行拼时间戳`() {
        val lyrics = Lyrics(
            lines = listOf(LyricLine(1_000L, "第一行"), LyricLine(62_500L, "第二行")),
            synced = true,
            estimated = false,
            source = LyricsSource.EMBEDDED_SYNCED
        )
        assertEquals("[00:01.00]第一行\n[01:02.50]第二行", lyrics.toLrcText())
    }

    @Test
    fun `没有歌词时导出为空`() {
        assertNull(Lyrics.NONE.toLrcText())
    }

    @Test
    fun `LRC 时间戳始终两位小数且不为负`() {
        assertEquals("[00:00.00]", "[${TimeFormat.lrcStamp(0L)}]")
        assertEquals("[00:07.05]", "[${TimeFormat.lrcStamp(7_059L)}]")
        assertEquals("[00:07.06]", "[${TimeFormat.lrcStamp(7_060L)}]")
        assertEquals("负时间戳应当被夹到 0", "[00:00.00]", "[${TimeFormat.lrcStamp(-5L)}]")
    }

    @Test
    fun `归档文件名去重而不是覆盖`() {
        val taken = mutableSetOf("晴天.mp3")
        assertEquals("晴天.mp3", ArchivedSongs.uniqueName("晴天.mp3", emptySet()))
        assertEquals("晴天 (2).mp3", ArchivedSongs.uniqueName("晴天.mp3", taken))
        taken.add("晴天 (2).mp3")
        assertEquals("晴天 (3).mp3", ArchivedSongs.uniqueName("晴天.mp3", taken))
        // 没有扩展名时也不能把名字本身当成扩展名切掉
        assertEquals("纯音乐 (2)", ArchivedSongs.uniqueName("纯音乐", setOf("纯音乐")))
    }

    @Test
    fun `归档记录与磁盘文件对齐`() {
        val entries = listOf(entry("a.mp3", "ms:1"), entry("b.flac", "saf:99"), entry("c.mp3", null))
        assertEquals(setOf("ms:1", "saf:99"), ArchivedSongs.archivedSourceKeys(entries))
        val orphans = ArchivedSongs.orphans(entries, setOf("a.mp3", "c.mp3"))
        assertEquals(listOf("b.flac"), orphans.map { it.name })
    }

    private fun entry(name: String, source: String?) = ArchivedEntry(
        name = name,
        title = name,
        artist = null,
        album = null,
        durationMs = 1000L,
        sizeBytes = 1024L,
        archivedAtSec = 0L,
        sourceKey = source,
        lyricsEmbedded = false
    )

    @Test
    fun `歌词副本的文件名合法且不互相碰撞`() {
        val keys = listOf("ms:12345", "saf:-98765", "lib:晴天.mp3", "lib:晴天 (2).mp3", "ms:12346")
        val names = keys.map { LyricsStore.fileNameFor(it) }

        assertEquals(keys.size, names.toSet().size)
        names.forEach { name ->
            // 冒号、斜杠都会让 createNewFile 直接失败或落到别的目录
            assertTrue("不能出现路径分隔符或冒号：$name", name.none { it == '/' || it == '\\' || it == ':' })
            assertTrue(name.endsWith(".lrc"))
            assertTrue(name.length <= 80)
        }
        assertEquals(LyricsStore.fileNameFor("ms:12345"), LyricsStore.fileNameFor("ms:12345"))
    }
}
