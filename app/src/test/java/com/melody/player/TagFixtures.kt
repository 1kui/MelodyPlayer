package com.melody.player

import java.io.ByteArrayOutputStream

/**
 * 测试用的字节构造器。
 *
 * 单测不依赖任何真实音频文件：直接在内存里造出符合规范的 ID3v2 / FLAC / Ogg / MP4 / RIFF
 * 容器字节，这样每种编码分支（latin1、UTF-16 BOM、UTF-8）和每个容器分支都能被精确覆盖，
 * 而且测试跑得飞快、可重复。
 */
internal class Bytes {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int): Bytes = apply { out.write(value and 0xFF) }

    fun u16be(value: Int): Bytes = apply {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun u24be(value: Int): Bytes = apply {
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun u32be(value: Long): Bytes = apply {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    fun u32le(value: Long): Bytes = apply {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }

    /** ID3v2 的 syncsafe 长度：每字节只用低 7 位。 */
    fun syncSafe(value: Int): Bytes = apply {
        out.write((value ushr 21) and 0x7F)
        out.write((value ushr 14) and 0x7F)
        out.write((value ushr 7) and 0x7F)
        out.write(value and 0x7F)
    }

    fun ascii(text: String): Bytes = apply { out.write(text.toByteArray(Charsets.ISO_8859_1)) }

    fun raw(bytes: ByteArray): Bytes = apply { out.write(bytes) }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * 便捷函数：把一段内容包成 MP4 原子（size 在前、type 在后）。
 *
 * 注意 atom 的长度字段是**含 8 字节头**的总长度，不是 payload 长度 ——
 * 少加这 8 字节会让解析器把上一个原子的尾部当成下一个原子的头，直接跑飞。
 */
internal fun framed(idAscii: String, payload: ByteArray, bigEndian: Boolean = true): ByteArray {
    val total = payload.size + 8L
    val b = Bytes()
    if (bigEndian) b.u32be(total) else b.u32le(total)
    b.ascii(idAscii).raw(payload)
    return b.toByteArray()
}

/**
 * ID3v2.3 帧头：id(4) + size(4 大端) + flags(2)。
 *
 * 顺序与 MP4 原子**相反**（MP4 是 size 在前），一开始把两者写成同一个 helper
 * 会让所有 ID3 用例喂进去畸形数据 —— 解析器返回 null 反而是正确行为。
 */
internal fun id3Frame(idAscii: String, payload: ByteArray): ByteArray {
    val b = Bytes()
    b.ascii(idAscii)
    b.u32be(payload.size.toLong())
    b.u16be(0) // flags：无压缩 / 无加密 / 无分组
    b.raw(payload)
    return b.toByteArray()
}

/**
 * ID3v2.4 帧头：结构同 v2.3，但帧长度是 syncsafe 的（每字节只用 7 位），
 * 用普通大端写会在 payload ≥ 128 字节时解析错位 —— 歌词一长就必然踩到。
 */
internal fun id3Frame24(idAscii: String, payload: ByteArray): ByteArray {
    val b = Bytes()
    b.ascii(idAscii)
    b.syncSafe(payload.size)
    b.u16be(0)
    b.raw(payload)
    return b.toByteArray()
}

internal object TagFixtures {

    private const val ENC_LATIN1 = 0
    private const val ENC_UTF16_BOM = 1
    private const val ENC_UTF8 = 3

    /** v2.3 的 USLT 帧需要语言码 + 描述符，这里统一用 eng + 空描述符。 */
    private fun usltPayload(encoding: Int, lyrics: String): ByteArray {
        val b = Bytes()
        b.u8(encoding)
        b.ascii("eng")
        when (encoding) {
            ENC_LATIN1 -> {
                b.u8(0)
                b.raw(lyrics.toByteArray(Charsets.ISO_8859_1))
            }

            ENC_UTF16_BOM -> {
                b.u16be(0)
                b.u8(0xFF).u8(0xFE)
                b.raw(lyrics.toByteArray(Charsets.UTF_16LE))
            }

            else -> {
                b.u8(0)
                b.raw(lyrics.toByteArray(Charsets.UTF_8))
            }
        }
        return b.toByteArray()
    }

    /** 带 USLT 歌词帧的 ID3v2.3 标签（最常见的形态）。 */
    fun id3v2Uslt(latin1Lyrics: String): ByteArray {
        val frame = id3Frame("USLT", usltPayload(ENC_LATIN1, latin1Lyrics))
        return id3Tag(major = 3, frames = frame)
    }

    /** 带 UTF-8 USLT 的 ID3v2.4 标签（中文歌词的常见形态）。 */
    fun id3v24UsltUtf8(lyrics: String): ByteArray {
        val frame = id3Frame24("USLT", usltPayload(ENC_UTF8, lyrics))
        return id3Tag(major = 4, frames = frame)
    }

    /** 带 UTF-16(带 BOM) USLT 的标签，验证解码分支。 */
    fun id3v2UsltUtf16(lyrics: String): ByteArray {
        val frame = id3Frame("USLT", usltPayload(ENC_UTF16_BOM, lyrics))
        return id3Tag(major = 3, frames = frame)
    }

    /** 同时带 TIT2/TPE1/TALB 与 USLT，验证标签与歌词能一起取到。 */
    fun id3v2WithTagsAndLyrics(title: String, artist: String, album: String, lyrics: String): ByteArray {
        val b = Bytes()
        for ((id, value) in listOf("TIT2" to title, "TPE1" to artist, "TALB" to album)) {
            val payload = Bytes().u8(ENC_UTF8).raw(value.toByteArray(Charsets.UTF_8)).toByteArray()
            b.raw(id3Frame24(id, payload))
        }
        b.raw(id3Frame24("USLT", usltPayload(ENC_UTF8, lyrics)))
        return id3Tag(major = 4, frames = b.toByteArray())
    }

    /** 带 SYLT（毫秒时间戳）的 ID3v2.4 标签。 */
    fun id3v24Sylt(lines: List<Pair<Long, String>>): ByteArray {
        val b = Bytes()
        b.u8(ENC_UTF8)
        b.ascii("eng")
        b.u8(2) // 时间戳格式：2 = 毫秒
        b.u8(1) // 内容类型：1 = 歌词
        b.u8(0) // 空描述符
        lines.forEach { (time, text) ->
            b.raw(text.toByteArray(Charsets.UTF_8))
            b.u8(0)
            b.u32be(time)
        }
        return id3Tag(major = 4, frames = id3Frame24("SYLT", b.toByteArray()))
    }

    /** 用 TXXX 承载歌词（部分播放器这么写）。 */
    fun id3v24Txxx(descriptor: String, value: String): ByteArray {
        val b = Bytes()
        b.u8(ENC_UTF8)
        b.raw(descriptor.toByteArray(Charsets.UTF_8))
        b.u8(0)
        b.raw(value.toByteArray(Charsets.UTF_8))
        return id3Tag(major = 4, frames = id3Frame24("TXXX", b.toByteArray()))
    }

    /** 只有标签、没有歌词的 ID3 标签。 */
    fun id3v2WithoutLyrics(): ByteArray {
        val payload = Bytes().u8(ENC_UTF8).raw("Only Title".toByteArray(Charsets.UTF_8)).toByteArray()
        return id3Tag(major = 4, frames = id3Frame24("TIT2", payload))
    }

    private fun id3Tag(major: Int, frames: ByteArray): ByteArray {
        val b = Bytes()
        b.ascii("ID3")
        b.u8(major)
        b.u8(0)
        b.u8(0) // flags：无 unsynchronisation / 无扩展头
        b.syncSafe(frames.size)
        b.raw(frames)
        return b.toByteArray()
    }

    /** FLAC：先一个 STREAMINFO 块（占位），再一个 VORBIS_COMMENT 结束块。 */
    fun flac(comments: Map<String, String>): ByteArray {
        val b = Bytes()
        b.ascii("fLaC")

        // STREAMINFO 占位块（类型 0，长度 34）
        b.u8(0x00)
        b.u24be(34)
        b.raw(ByteArray(34))

        val payload = vorbisCommentPayload(comments)
        b.u8(0x80.toInt() or 4) // 最后一颗块 + 类型 4 = VORBIS_COMMENT
        b.u24be(payload.size)
        b.raw(payload)
        return b.toByteArray()
    }

    /** Ogg Vorbis：第一页放识别头，第二页放注释头。 */
    fun oggVorbis(comments: Map<String, String>): ByteArray {
        val ident = Bytes().u8(0x01).ascii("vorbis").raw(ByteArray(23)).toByteArray()
        val comment = Bytes().u8(0x03).ascii("vorbis").raw(vorbisCommentPayload(comments)).toByteArray()
        return oggPage(serial = 1, seq = 0, headerType = 0x02, packet = ident) +
            oggPage(serial = 1, seq = 1, headerType = 0x00, packet = comment)
    }

    /** Ogg Opus：注释头以 OpusTags 开头。 */
    fun opusTags(comments: Map<String, String>): ByteArray {
        val head = "OpusHead".toByteArray(Charsets.ISO_8859_1) + ByteArray(11)
        val tags = "OpusTags".toByteArray(Charsets.ISO_8859_1) + vorbisCommentPayload(comments)
        return oggPage(serial = 2, seq = 0, headerType = 0x02, packet = head) +
            oggPage(serial = 2, seq = 1, headerType = 0x00, packet = tags)
    }

    private fun vorbisCommentPayload(comments: Map<String, String>): ByteArray {
        val vendor = "melody-test".toByteArray(Charsets.UTF_8)
        val b = Bytes()
        b.u32le(vendor.size.toLong())
        b.raw(vendor)
        b.u32le(comments.size.toLong())
        comments.forEach { (key, value) ->
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            b.u32le(entry.size.toLong())
            b.raw(entry)
        }
        return b.toByteArray()
    }

    private fun oggPage(serial: Int, seq: Int, headerType: Int, packet: ByteArray): ByteArray {
        val segments = ArrayList<Int>()
        var remaining = packet.size
        while (remaining >= 255) {
            segments.add(255)
            remaining -= 255
        }
        segments.add(remaining)

        val b = Bytes()
        b.ascii("OggS")
        b.u8(0) // 版本
        b.u8(headerType)
        b.raw(ByteArray(8)) // granule position
        b.u32le(serial.toLong())
        b.u32le(seq.toLong())
        b.u32le(0) // CRC：解析器不校验，填 0
        b.u8(segments.size)
        segments.forEach { b.u8(it) }
        b.raw(packet)
        return b.toByteArray()
    }

    /** MP4 / M4A：moov → udta → meta → ilst → ©lyr → data。 */
    fun mp4(lyrics: String): ByteArray {
        val dataAtom = framed(
            "data",
            Bytes().u32be(1L).u32be(0L).raw(lyrics.toByteArray(Charsets.UTF_8)).toByteArray()
        )
        val lyricsAtom = framed("\u00A9lyr", dataAtom)
        val ilst = framed("ilst", lyricsAtom)
        // meta 是 FullBox，前面还有 4 字节 version/flags
        val meta = framed("meta", Bytes().u32be(0L).raw(ilst).toByteArray())
        val udta = framed("udta", meta)
        val moov = framed("moov", udta)
        val ftyp = framed("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + Bytes().u32be(0x200L).ascii("M4A isom").toByteArray())
        return ftyp + moov
    }

    /** MP4：moov 放在文件尾部（非 faststart 的常见布局）。 */
    fun mp4WithTrailingMoov(lyrics: String): ByteArray {
        val full = mp4(lyrics)
        val ftypSize = 8 + "M4A ".length + 4 + "M4A isom".length
        val ftyp = full.copyOfRange(0, ftypSize)
        val moov = full.copyOfRange(ftypSize, full.size)
        val mdat = framed("mdat", ByteArray(512))
        return ftyp + mdat + moov
    }

    /** WAV：把整段 ID3v2 塞进 "id3 " 块。 */
    fun wavWithId3Chunk(id3: ByteArray): ByteArray {
        val b = Bytes()
        val body = Bytes()
        body.ascii("WAVE")
        body.ascii("id3 ")
        body.u32le(id3.size.toLong())
        body.raw(id3)
        body.ascii("data")
        body.u32le(4)
        body.raw(ByteArray(4))
        val bodyBytes = body.toByteArray()

        b.ascii("RIFF")
        b.u32le(bodyBytes.size.toLong())
        b.raw(bodyBytes)
        return b.toByteArray()
    }
}
