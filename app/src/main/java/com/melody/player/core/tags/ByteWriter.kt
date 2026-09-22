package com.melody.player.core.tags

import java.io.ByteArrayOutputStream

/**
 * 大端/小端整数字节写入的小工具，与 [TagBytes] 成对。
 *
 * 写标签时必须自己控制字节序与长度字段的编码方式（ID3v2.4 的 syncsafe、
 * Vorbis Comment 的小端长度），直接拼 ByteArray 很容易在对齐上出错。
 */
internal class ByteWriter {

    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun u8(value: Int): ByteWriter = apply { out.write(value and 0xFF) }

    fun u16be(value: Int): ByteWriter = apply {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun u24be(value: Int): ByteWriter = apply {
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun u32be(value: Long): ByteWriter = apply {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    fun u32le(value: Long): ByteWriter = apply {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }

    /** ID3v2 的 syncsafe 长度：每字节只用低 7 位。 */
    fun syncSafe(value: Int): ByteWriter = apply {
        out.write((value ushr 21) and 0x7F)
        out.write((value ushr 14) and 0x7F)
        out.write((value ushr 7) and 0x7F)
        out.write(value and 0x7F)
    }

    fun ascii(text: String): ByteWriter = apply { out.write(text.toByteArray(Charsets.ISO_8859_1)) }

    fun utf8(text: String): ByteWriter = apply { out.write(text.toByteArray(Charsets.UTF_8)) }

    /** UTF-16LE 且自带 BOM，对应 ID3v2 的 encoding=1。 */
    fun utf16Bom(text: String): ByteWriter = apply {
        u8(0xFF)
        u8(0xFE)
        out.write(text.toByteArray(Charsets.UTF_16LE))
    }

    fun raw(bytes: ByteArray): ByteWriter = apply { out.write(bytes) }

    fun toByteArray(): ByteArray = out.toByteArray()
}
