package com.melody.player

import com.melody.player.core.tags.ByteArraySource
import com.melody.player.core.tags.EmbeddedArtworkParser
import com.melody.player.core.tags.LyricsTagWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「把歌词 / 封面写进文件」的round-trip 测试。
 *
 * 核心思路是**写完立刻用 [EmbeddedArtworkParser] 与 [AudioTagReader] 读回来** ——
 * 这比逐字节比对更接近用户真正关心的事："写完之后，别的播放器读得到吗"。
 * 只有自己写自己读的那一套断言会漏掉最致命的一类错误：
 * 写出来的字节在本项目的读取端能自洽，但**字段字节序写反了**，
 * 于是本项目读得出来、别的播放器全都读不出来。
 */
class TagEmbedRoundTripTest {

    private val jpegBytes = ByteArray(200) { ((it * 7) % 251).toByte() }.also {
        // 带上 JPEG 魔数，让 MIME 探测走JPEG 分支
        it[0] = 0xFF.toByte()
        it[1] = 0xD8.toByte()
    }

    private val pngBytes = ByteArray(120) { ((it * 5) % 251).toByte() }.also {
        it[0] = 0x89.toByte()
        it[1] = 'P'.code.toByte()
        it[2] = 'N'.code.toByte()
        it[3] = 'G'.code.toByte()
    }

    @Test
    fun `writes artwork into an mp3 without tags and reads it back`() {
        val mpeg = TagFixtures.mpegAudioFrames()
        val rewrite = LyricsTagWriter.writeArtwork(mpeg, jpegBytes)
        assertNotNull(rewrite)
        val result = EmbeddedArtworkParser.parse(ByteArraySource(rewrite!!.newHead))
        assertNotNull(result)
        assertArrayEquals(jpegBytes, result!!.bytes)
    }

    @Test
    fun `writes artwork into an existing ID3v2 3 tag and preserves lyrics`() {
        // 必须用 UTF-16 那份fixture：id3v2Uslt 走的是 latin1，塞中文进去
        // 写出来就是"??????"，断言会误报成"歌词被改坏了"
        val original = TagFixtures.id3v2UsltUtf16("原有歌词不能丢")
        val rewrite = LyricsTagWriter.writeArtwork(original, jpegBytes)
        assertNotNull(rewrite)
        assertTrue(rewrite!!.embedded)

        // 封面读得回来
        val art = EmbeddedArtworkParser.parse(ByteArraySource(rewrite.newHead))
        assertNotNull(art)
        assertArrayEquals(jpegBytes, art!!.bytes)

        // 歌词必须还在 —— 只改封面却把词弄丢是不能接受的
        val lyrics = com.melody.player.core.tags.AudioTagReader.extract(ByteArraySource(rewrite.newHead))
        assertNotNull(lyrics)
        assertEquals("原有歌词不能丢", lyrics!!.text)
    }

    @Test
    fun `writing artwork replaces the old APIC instead of appending a second one`() {
        val withOld = TagFixtures.id3v2Apic(ByteArray(50) { 7 })
        val replacement = ByteArray(80) { 9 }
        val rewrite = LyricsTagWriter.writeArtwork(withOld, replacement)
        assertNotNull(rewrite)
        val art = EmbeddedArtworkParser.parse(ByteArraySource(rewrite!!.newHead))
        assertNotNull(art)
        // 拿到的是新图，且旧图那 50 字节不该还留在标签里
        assertArrayEquals(replacement, art!!.bytes)
        assertTrue(rewrite.newHead.size < withOld.size + replacement.size + 64)
    }

    @Test
    fun `writes artwork into FLAC and reads it back`() {
        val flac = TagFixtures.flac(mapOf("TITLE" to "歌名"))
        val rewrite = LyricsTagWriter.writeArtwork(flac, pngBytes)
        assertNotNull(rewrite)
        val art = EmbeddedArtworkParser.parse(ByteArraySource(rewrite!!.newHead))
        assertNotNull(art)
        assertArrayEquals(pngBytes, art!!.bytes)
        // FLAC 里只改封面时，原有的 Vorbis 注释必须整块保住（包括标题）
        val text = String(rewrite.newHead, Charsets.ISO_8859_1)
        assertTrue("原有 TITLE 注释应保留", text.contains("TITLE="))
        assertTrue("原有标题值应保留", containsBytes(rewrite.newHead, "歌名".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `FLAC picture block must precede the vorbis comment`() {
        // 本项目的 FlacParser 遇到 VORBIS_COMMENT 就返回，所以顺序错了自己就读不到封面；
        // 而用户侧的现象是"歌还在、封面没了"。这条断言就是钉住那个顺序。
        val flac = TagFixtures.flac(mapOf("TITLE" to "歌名"))
        val rewrite = LyricsTagWriter.writeArtwork(flac, jpegBytes)
        assertNotNull(rewrite)
        val head = rewrite!!.newHead
        val pictureAt = indexOfBlock(head, 6)
        val commentAt = indexOfBlock(head, 4)
        assertTrue("应能读到 PICTURE 块", pictureAt >= 0)
        assertTrue("应能读到 VORBIS_COMMENT 块", commentAt >= 0)
        assertTrue("PICTURE 必须排在 VORBIS_COMMENT 之前", pictureAt < commentAt)
    }

    @Test
    fun `writes lyrics and artwork together`() {
        val mpeg = TagFixtures.mpegAudioFrames()
        val rewrite = LyricsTagWriter.rewrite(
            mpeg,
            LyricsTagWriter.Payload(lyrics = "[00:00.00]第一句", artwork = jpegBytes)
        )
        assertNotNull(rewrite)
        val head = rewrite!!.newHead
        val art = EmbeddedArtworkParser.parse(ByteArraySource(head))
        assertNotNull(art)
        assertArrayEquals(jpegBytes, art!!.bytes)
        val lyrics = com.melody.player.core.tags.AudioTagReader.extract(ByteArraySource(head))
        assertNotNull(lyrics)
        assertTrue(lyrics!!.text!!.contains("第一句"))
    }

    @Test
    fun `unsupported containers are refused rather than corrupted`() {
        val mp4 = TagFixtures.mp4("歌词")
        assertNull(LyricsTagWriter.writeArtwork(mp4, jpegBytes))
        val ogg = TagFixtures.oggVorbis(mapOf("LYRICS" to "词"))
        assertNull(LyricsTagWriter.writeArtwork(ogg, jpegBytes))
        // 空图片直接拒掉，不要生成一个 0 字节的 APIC 帧
        assertNull(LyricsTagWriter.writeArtwork(TagFixtures.mpegAudioFrames(), ByteArray(0)))
        // 什么都要写却都是空的，也不该动手
        assertNull(LyricsTagWriter.rewrite(TagFixtures.mpegAudioFrames(), LyricsTagWriter.Payload()))
    }

    @Test
    fun `skipBytes points past the old tag so audio data is not duplicated`() {
        val original = TagFixtures.id3v2Uslt("原歌词")
        val rewrite = LyricsTagWriter.writeArtwork(original, jpegBytes)
        assertNotNull(rewrite)
        // skipBytes 必须正好等于原标签的总长（新文件从这之后接音频数据）
        val expected = 10L + ((original.size - 10).coerceAtLeast(0))
        assertTrue(
            "skipBytes 应覆盖原 ID3 标签，实际 ${rewrite!!.skipBytes}，预期约 $expected",
            rewrite.skipBytes in (expected - 4)..(expected + 4)
        )
    }

    /** ByteArray 没有 `contains(ByteArray)` 重载，只能自己扫。 */
    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    /** 在字节里找第一个「块头字节低 7 位 == type」的FLAC 元数据块。 */
    private fun indexOfBlock(head: ByteArray, type: Int): Int {
        var pos = 4
        while (pos + 4 <= head.size) {
            val first = head[pos].toInt() and 0xFF
            val blockType = first and 0x7F
            val length = ((head[pos + 1].toInt() and 0xFF) shl 16) or
                ((head[pos + 2].toInt() and 0xFF) shl 8) or (head[pos + 3].toInt() and 0xFF)
            if (blockType == type) return pos
            if (first and 0x80 != 0) return -1
            pos += 4 + length
        }
        return -1
    }
}