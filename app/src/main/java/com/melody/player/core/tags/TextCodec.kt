package com.melody.player.core.tags

import java.nio.charset.Charset

/**
 * ID3v2 / Vorbis Comment 里文本字段的编码解码。
 *
 * ID3v2 用首字节声明编码：0=ISO-8859-1，1=UTF-16(带 BOM)，2=UTF-16BE，3=UTF-8。
 * 老播放器写的歌词里 latin1 与 UTF-16 混用非常常见，必须逐字节自己处理，
 * 不能交给 String(bytes, charset) 一把梭 —— BOM 与终止符位置都会踩坑。
 */
internal object TextCodec {

    const val ENC_LATIN1 = 0
    const val ENC_UTF16_BOM = 1
    const val ENC_UTF16_BE = 2
    const val ENC_UTF8 = 3

    fun charsetOf(encodingByte: Int): Charset = when (encodingByte) {
        ENC_LATIN1 -> Charsets.ISO_8859_1
        ENC_UTF16_BOM, ENC_UTF16_BE -> Charsets.UTF_16BE
        ENC_UTF8 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /** 解码一段字节；utf16 带 BOM 时按 BOM 决定字节序。 */
    fun decode(bytes: ByteArray, encodingByte: Int, offset: Int = 0, length: Int = bytes.size - offset): String {
        if (length <= 0 || offset >= bytes.size) return ""
        val end = minOf(bytes.size, offset + length)
        if (end <= offset) return ""

        return when (encodingByte) {
            ENC_LATIN1 -> String(bytes, offset, end - offset, Charsets.ISO_8859_1)
            ENC_UTF8 -> String(bytes, offset, end - offset, Charsets.UTF_8)
            ENC_UTF16_BE -> String(bytes, offset, end - offset, Charsets.UTF_16BE)
            ENC_UTF16_BOM -> decodeUtf16WithBom(bytes, offset, end)
            else -> String(bytes, offset, end - offset, Charsets.ISO_8859_1)
        }.cleanup()
    }

    private fun decodeUtf16WithBom(bytes: ByteArray, offset: Int, end: Int): String {
        var start = offset
        var charset: Charset = Charsets.UTF_16LE // 规范要求带 BOM，缺失时按小端兜底
        if (end - start >= 2) {
            val b0 = bytes[start].toInt() and 0xFF
            val b1 = bytes[start + 1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                charset = Charsets.UTF_16LE
                start += 2
            } else if (b0 == 0xFE && b1 == 0xFF) {
                charset = Charsets.UTF_16BE
                start += 2
            }
        }
        // 去掉奇数尾巴，避免解码出替换字符
        var len = end - start
        if (len % 2 != 0) len -= 1
        if (len <= 0) return ""
        return String(bytes, start, len, charset)
    }

    /** 在 [bytes] 中从 [from] 起找字符串终止符位置（utf16 需偶数字节对齐），找不到返回 -1。 */
    fun findTerminator(bytes: ByteArray, encodingByte: Int, from: Int, end: Int = bytes.size): Int {
        if (encodingByte == ENC_UTF16_BOM || encodingByte == ENC_UTF16_BE) {
            var i = from
            while (i + 1 < end) {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
                i += 2
            }
            return -1
        }
        var i = from
        while (i < end) {
            if (bytes[i].toInt() == 0) return i
            i++
        }
        return -1
    }

    /** 读取以终止符结尾的字符串，返回文本与「终止符之后」的下一个位置。 */
    fun readTerminated(bytes: ByteArray, encodingByte: Int, start: Int, end: Int = bytes.size): Pair<String, Int> {
        if (start >= end) return "" to end
        val term = findTerminator(bytes, encodingByte, start, end)
        return if (term < 0) {
            decode(bytes, encodingByte, start, end - start) to end
        } else {
            val step = if (encodingByte == ENC_UTF16_BOM || encodingByte == ENC_UTF16_BE) 2 else 1
            decode(bytes, encodingByte, start, term - start) to (term + step)
        }
    }

    /** 读取定长字段并清掉尾部填充的 \u0000 / 空白。 */
    fun decodeFixed(bytes: ByteArray, encodingByte: Int, offset: Int, length: Int): String =
        decode(bytes, encodingByte, offset, length)

    private fun String.cleanup(): String {
        var s = this
        // 去掉 BOM 与会出现在字段尾部的大量 \u0000
        s = s.removePrefix("\uFEFF")
        s = s.trimEnd('\u0000')
        return s
    }

    /** 反转 ID3v2 的 unsynchronisation：把所有 "FF 00" 还原成 "FF"。 */
    fun deUnsynchronise(data: ByteArray): ByteArray {
        var hasFf = false
        for (b in data) {
            if (b.toInt() and 0xFF == 0xFF) {
                hasFf = true
                break
            }
        }
        if (!hasFf) return data
        val out = ByteArray(data.size)
        var w = 0
        var i = 0
        while (i < data.size) {
            out[w++] = data[i]
            if (data[i].toInt() and 0xFF == 0xFF && i + 1 < data.size && data[i + 1].toInt() == 0) {
                i += 2 // 丢掉被插入的 0x00
            } else {
                i++
            }
        }
        return out.copyOf(w)
    }
}
