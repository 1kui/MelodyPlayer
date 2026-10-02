package com.melody.player

import com.melody.player.core.tags.ByteArraySource
import com.melody.player.core.tags.EmbeddedArtworkParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌封面解析的端到端测试。
 *
 * 这套解析器此前**根本不存在**（内嵌歌词的解析器只认歌词帧，见 AudioTagReaderTest），
 * 而界面上「删掉这张，退回内嵌封面或占位图案」一直在承诺这个能力 ——
 * 所以这些用例同时也是那条文案的成立条件。
 *
 * 重点覆盖几个真会踩的边界：
 *  - APIC 载荷里描述符的终止符宽度随 encoding 变（UTF-16 是**两字节** \0\0）
 *  - v2.2 的 PIC：帧 id 3 字符、长度 3 字节、MIME 只有 "JPG" 这种 3 字符
 *  - 多张封面时要挑正面（type=3），不能抓到第一张就返回
 *  - v2.4 的帧长度是 syncsafe，图片字节通常 > 127，用大端写必然解析错位
 */
class EmbeddedArtworkParserTest {

    private fun parse(bytes: ByteArray) = EmbeddedArtworkParser.parse(ByteArraySource(bytes))

    /** 造一段可辨认的图片字节，避免断言时只比长度。 */
    private fun imageBytes(seed: Int, size: Int = 64): ByteArray =
        ByteArray(size) { ((it + seed) % 251).toByte() }

    @Test
    fun `extracts APIC front cover from ID3v2 3`() {
        val image = imageBytes(1)
        val result = parse(TagFixtures.id3v2Apic(image))
        assertNotNull(result)
        assertEquals("ID3v2.3 APIC", result!!.container)
        assertArrayEquals(image, result.bytes)
    }

    @Test
    fun `extracts APIC with utf16 descriptor terminator`() {
        // UTF-16 的字符串终止符是两个 0 字节，按单字节 0 截断会把半个图片字节吃进描述符
        val image = imageBytes(2)
        val payload = TagFixtures.apicPayload(
            mime = "image/jpeg",
            image = image,
            descriptor = "封面",
            encoding = 1
        )
        val id3 = Bytes().ascii("ID3").u8(3).u8(0).u8(0).syncSafe(
            id3Frame("APIC", payload).size
        ).raw(id3Frame("APIC", payload)).toByteArray()
        val result = parse(id3)
        assertNotNull(result)
        assertArrayEquals(image, result!!.bytes)
    }

    @Test
    fun `extracts PIC from ID3v2 2 with three char mime`() {
        val image = imageBytes(3)
        val result = parse(TagFixtures.id3v22Pic(image))
        assertNotNull(result)
        assertEquals("ID3v2.2 PIC", result!!.container)
        assertArrayEquals(image, result.bytes)
    }

    @Test
    fun `extracts large APIC from ID3v2 4 with syncsafe frame size`() {
        // 512 字节 > 127：帧长度必须按 syncsafe 写，否则读成乱码长度直接 break
        val image = imageBytes(4, size = 512)
        val payload = TagFixtures.apicPayload("image/png", image)
        val frame = id3Frame24("APIC", payload)
        val id3 = Bytes().ascii("ID3").u8(4).u8(0).u8(0).syncSafe(frame.size).raw(frame).toByteArray()
        val result = parse(id3)
        assertNotNull(result)
        assertEquals("ID3v2.4 APIC", result!!.container)
        assertArrayEquals(image, result.bytes)
    }

    @Test
    fun `prefers front cover over back cover`() {
        val back = imageBytes(5)
        val front = imageBytes(6)
        val result = parse(TagFixtures.id3v2ApicBackThenFront(back, front))
        assertNotNull(result)
        // 必须选正面那张；抓到第一张（背面）就返回是最容易犯的错
        assertArrayEquals(front, result!!.bytes)
    }

    @Test
    fun `falls back to a non front cover when no front exists`() {
        //老文件只写 type=0 的情况：有图总比退回占位图强
        val only = imageBytes(7)
        val result = parse(TagFixtures.id3v2ApicOnlyOther(only))
        assertNotNull(result)
        assertArrayEquals(only, result!!.bytes)
    }

    @Test
    fun `extracts FLAC picture block`() {
        val image = imageBytes(8, size = 300)
        val result = parse(TagFixtures.flacWithPicture(image))
        assertNotNull(result)
        assertEquals("FLAC PICTURE", result!!.container)
        assertArrayEquals(image, result.bytes)
    }

    @Test
    fun `returns null when there is no artwork at all`() {
        assertNull(parse(TagFixtures.id3v2WithoutLyrics()))
        assertNull(parse(TagFixtures.flac(mapOf("LYRICS" to "只有歌词没有图"))))
    }

    @Test
    fun `returns null for unsupported containers instead of guessing`() {
        // MP4 的 covr / Ogg 的注释封面这版不支持：如实返回 null，
        // 好过给一个偶尔解析错、偶尔把音频数据当前景图的半吊子结果
        assertNull(parse(TagFixtures.mp4("歌词")))
        assertNull(parse(TagFixtures.oggVorbis(mapOf("LYRICS" to "词"))))
    }

    @Test
    fun `returns null instead of throwing on truncated or garbage input`() {
        assertNull(parse(ByteArray(0)))
        assertNull(parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte())))
        // 声明了一个远超实际内容的 APIC 长度
        val bogus = Bytes().ascii("ID3").u8(3).u8(0).u8(0).syncSafe(64)
            .raw(Bytes().ascii("APIC").u32be(9999L).u16be(0).raw(ByteArray(4)).toByteArray())
            .toByteArray()
        assertNull(parse(bogus))
        // FLAC 声明了 PICTURE 但块内容被截断
        val brokenFlac = Bytes().ascii("fLaC").u8(0x00).u24be(34).raw(ByteArray(34))
            .u8(0x80.toInt() or 6).u24be(4000).raw(ByteArray(8)).toByteArray()
        assertNull(parse(brokenFlac))
    }

    @Test
    fun `ignores APIC whose mime is not an image`() {
        val payload = TagFixtures.apicPayload("text/plain", imageBytes(9))
        val id3 = Bytes().ascii("ID3").u8(3).u8(0).u8(0).syncSafe(
            id3Frame("APIC", payload).size
        ).raw(id3Frame("APIC", payload)).toByteArray()
        assertNull(parse(id3))
    }

    @Test
    fun `equality compares image bytes by content`() {
        // 不显式重写 equals 的话 data class 对数组按引用比较，
        // 这条断言会以"看起来毫无道理"的方式失败
        val a = com.melody.player.core.tags.EmbeddedArtwork(imageBytes(1), "X")
        val b = com.melody.player.core.tags.EmbeddedArtwork(imageBytes(1), "X")
        assertEquals(a, b)
        assertTrue(a.hashCode() == b.hashCode())
    }
}