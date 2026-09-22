package com.melody.player.core.tags

/**
 * ID3v2.2 / v2.3 / v2.4 解析器。
 *
 * 只做两件事：取歌词，取标题/歌手/专辑这类基础标签。
 * 支持的歌词帧：
 *  - USLT / ULT —— 无时间戳歌词（绝大多数 MP3 用这个）
 *  - SYLT / SLT —— 带时间戳歌词（时间戳单位为毫秒时才可用）
 *  - TXXX，描述符为 LYRICS / UNSYNCEDLYRICS 等（iTunes、部分国产播放器会这么写）
 */
object Id3v2Parser {

    private const val MAX_TAG_BYTES = 12 * 1024 * 1024

    /** 会被当作「文本歌词」的 TXXX 描述符（大写比较）。 */
    private val TEXT_FRAME_DESCRIPTORS = setOf(
        "LYRICS", "LYRIC", "LYRICS:ENG", "UNSYNCEDLYRICS", "UNSYNCHRONISEDLYRICS", "SYNCEDLYRICS", "LRC"
    )

    fun parse(src: ByteSource): EmbeddedLyrics? {
        val head = src.readPrefix(10)
        if (head.size < 10) return null
        if (!TagBytes.startsWith(head, 0, "ID3")) return null

        val major = TagBytes.int8(head, 3)
        val flags = TagBytes.int8(head, 5)
        val declaredSize = TagBytes.syncSafe(head, 6).toLong()
        // v2.4 起 footer 不计入 size；超长标签做上限保护，避免恶意文件把内存吃光
        val tagSize = minOf(declaredSize, MAX_TAG_BYTES.toLong()).toInt()
        if (tagSize <= 0) return null

        var body = src.readAt(10L, tagSize)
        if (body.isEmpty()) return null
        if (flags and 0x80 != 0) body = TextCodec.deUnsynchronise(body)

        val holder = Holder()
        var pos = skipExtendedHeader(body, major, flags)
        if (pos < 0) return null

        val idLen = if (major == 2) 3 else 4
        val sizeLen = if (major == 2) 3 else 4
        val flagsLen = if (major == 2) 0 else 2
        val headerLen = idLen + sizeLen + flagsLen

        while (pos + headerLen <= body.size) {
            val frameId = TagBytes.ascii(body, pos, idLen)
            if (frameId.isEmpty() || frameId[0] == '\u0000') break
            if (!frameId.all { it.isLetterOrDigit() }) break

            val frameSize = when {
                major == 2 -> TagBytes.uint24be(body, pos + idLen)
                major == 3 -> TagBytes.int32beAsInt(body, pos + idLen)
                else -> TagBytes.syncSafe(body, pos + idLen)
            }
            val dataStart = pos + headerLen
            if (frameSize <= 0 || dataStart + frameSize > body.size) break
            val dataEnd = dataStart + frameSize

            val frameFlags = if (major == 2) 0 else TagBytes.uint16be(body, pos + idLen + sizeLen)
            var payloadStart = dataStart
            // v2.4 可选的数据长度指示器
            if (major >= 4 && (frameFlags and 0x0001) != 0) payloadStart += 4
            if (payloadStart < dataEnd) {
                var payload = body.copyOfRange(payloadStart, dataEnd)
                // v2.4 支持逐帧 unsynchronisation
                if (major >= 4 && (frameFlags and 0x0002) != 0) {
                    payload = TextCodec.deUnsynchronise(payload)
                }
                runCatching { readFrame(major, frameId, payload, holder) }
            }
            pos = dataEnd
        }

        val container = "ID3v2.$major"
        val synced = holder.synced.ifEmpty {
            // 没有 SYLT 时，若文本歌词本身就是 LRC 格式，交给上层解析；这里只给出纯文本
            emptyList()
        }
        val text = holder.text
        return EmbeddedLyrics.of(
            container = container,
            text = text,
            synced = synced,
            title = holder.title,
            artist = holder.artist,
            album = holder.album
        )
    }

    private fun skipExtendedHeader(body: ByteArray, major: Int, flags: Int): Int {
        if (flags and 0x40 == 0) return 0
        if (body.size < 4) return -1
        return if (major >= 4) {
            // v2.4：扩展头长度含自身
            TagBytes.syncSafe(body, 0)
        } else {
            // v2.3：长度字段不含自身，需再加上 4
            4 + TagBytes.int32beAsInt(body, 0)
        }.coerceIn(0, body.size)
    }

    private fun readFrame(major: Int, frameId: String, payload: ByteArray, holder: Holder) {
        if (payload.isEmpty()) return
        when (frameId) {
            "USLT", "ULT" -> readUslt(payload, holder)
            "SYLT", "SLT" -> readSylt(payload, holder)
            "TXXX", "TXX" -> readTxxx(payload, holder)
            "TIT2", "TT2" -> holder.title = holder.title ?: readTextFrame(payload)
            "TPE1", "TP1" -> holder.artist = holder.artist ?: readTextFrame(payload)
            "TALB", "TAL" -> holder.album = holder.album ?: readTextFrame(payload)
            "COMM", "COM" -> Unit // 注释里偶尔也有歌词，但误报率高，不取
        }
    }

    private fun readUslt(payload: ByteArray, holder: Holder) {
        if (holder.text != null) return
        val encoding = TagBytes.int8(payload, 0)
        // [0]=encoding [1..3]=language [4..]=内容描述符\0歌词正文
        val (_, afterDescriptor) = TextCodec.readTerminated(payload, encoding, 4)
        val text = TextCodec.decode(payload, encoding, afterDescriptor, payload.size - afterDescriptor)
        if (text.isNotBlank()) holder.text = text
    }

    private fun readSylt(payload: ByteArray, holder: Holder) {
        if (holder.synced.isNotEmpty()) return
        val encoding = TagBytes.int8(payload, 0)
        // [0]=encoding [1..3]=language [4]=时间戳格式 [5]=内容类型 [6..]=描述符\0内容
        val timestampFormat = TagBytes.int8(payload, 4)
        val (_, afterDescriptor) = TextCodec.readTerminated(payload, encoding, 6)
        if (timestampFormat != TIMESTAMP_MS) return // MPEG 帧号格式无法换算成毫秒，放弃

        val lines = ArrayList<SyncedLyricLine>()
        var pos = afterDescriptor
        while (pos < payload.size) {
            val (text, next) = TextCodec.readTerminated(payload, encoding, pos)
            if (next + 4 > payload.size) break
            val time = TagBytes.int32be(payload, next)
            if (time < 0) break
            lines.add(SyncedLyricLine(time, text.trim()))
            pos = next + 4
        }
        if (lines.isNotEmpty()) {
            holder.synced = lines.sortedBy { it.timeMs }
            // SYLT 一般也会配一个 USLT，这里不再重复取文本
            holder.text = holder.text ?: lines.joinToString("\n") { it.text }
        }
    }

    private fun readTxxx(payload: ByteArray, holder: Holder) {
        val encoding = TagBytes.int8(payload, 0)
        val (descriptor, afterDescriptor) = TextCodec.readTerminated(payload, encoding, 1)
        val key = descriptor.trim().uppercase()
        if (key !in TEXT_FRAME_DESCRIPTORS) return
        val value = TextCodec.decode(payload, encoding, afterDescriptor, payload.size - afterDescriptor)
        if (value.isNotBlank() && holder.text == null) holder.text = value
    }

    private fun readTextFrame(payload: ByteArray): String? {
        val encoding = TagBytes.int8(payload, 0)
        val text = TextCodec.decode(payload, encoding, 1, payload.size - 1)
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private const val TIMESTAMP_MS = 2

    private class Holder {
        var text: String? = null
        var synced: List<SyncedLyricLine> = emptyList()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
    }
}
