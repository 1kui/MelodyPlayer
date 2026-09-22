package com.melody.player.core.tags

import java.io.ByteArrayOutputStream

/**
 * Ogg 容器解析（Vorbis 与 Opus 共用注释头结构）。
 *
 * 需要按 Ogg 分页协议把 packet 重新拼起来：每个页有 27 字节页头 + 段表，
 * 段表中值为 255 表示该 packet 还要延续到下一段，小于 255 才是 packet 结束。
 * 注释头是第 2 个 packet：
 *  - Vorbis：以 0x03 + "vorbis" 开头，注释结构从第 7 字节开始
 *  - Opus  ：以 "OpusTags" 开头，注释结构从第 8 字节开始
 */
object OggParser {

    private const val MAX_PAGES = 96
    private const val MAX_PACKET_BYTES = 8 * 1024 * 1024

    fun parse(src: ByteSource): EmbeddedLyrics? {
        var offset = 0L
        var pages = 0
        val packet = ByteArrayOutputStream()
        var pendingTitles: String? = null
        var pendingArtists: String? = null
        var pendingAlbums: String? = null

        while (pages < MAX_PAGES && offset + 27 <= src.size) {
            val header = src.readAt(offset, 27)
            if (header.size < 27 || !TagBytes.startsWith(header, 0, "OggS")) break
            val segCount = TagBytes.int8(header, 26)
            val segTable = src.readAt(offset + 27, segCount)
            if (segTable.size < segCount) break

            var payloadLen = 0
            for (i in 0 until segCount) payloadLen += TagBytes.int8(segTable, i)
            val payload = src.readAt(offset + 27 + segCount, payloadLen)

            var p = 0
            for (i in 0 until segCount) {
                val lacing = TagBytes.int8(segTable, i)
                if (p + lacing <= payload.size) packet.write(payload, p, lacing)
                p += lacing
                if (lacing < 255) {
                    val full = packet.toByteArray()
                    packet.reset()
                    val parsed = interpret(full)
                    if (parsed != null) {
                        if (parsed.isEmpty) {
                            // 注释头已解析但里面没有歌词，仍然保留基础标签
                            pendingTitles = pendingTitles ?: parsed.title
                            pendingArtists = pendingArtists ?: parsed.artist
                            pendingAlbums = pendingAlbums ?: parsed.album
                        } else {
                            return parsed
                        }
                    }
                }
                if (packet.size() > MAX_PACKET_BYTES) packet.reset()
            }

            offset += 27 + segCount + payloadLen
            pages++
        }

        return EmbeddedLyrics.of(
            container = "Ogg Vorbis Comment",
            title = pendingTitles,
            artist = pendingArtists,
            album = pendingAlbums
        )
    }

    /** 返回 null 表示这个 packet 不是注释头；返回空对象表示是注释头但没有歌词。 */
    private fun interpret(packet: ByteArray): EmbeddedLyrics? {
        if (packet.size < 8) return null
        val isVorbisComment = TagBytes.int8(packet, 0) == 0x03 && TagBytes.startsWith(packet, 1, "vorbis")
        val isOpusTags = TagBytes.startsWith(packet, 0, "OpusTags")
        if (!isVorbisComment && !isOpusTags) return null

        val start = if (isOpusTags) 8 else 7
        val comments = VorbisComments.parse(packet, start, packet.size)
        val container = if (isOpusTags) "OpusTags Comment" else "Ogg Vorbis Comment"
        return VorbisComments.toLyrics(comments, container) ?: EmbeddedLyrics(
            text = null, synced = emptyList(), container = container,
            title = comments.firstOrNull { it.first == "TITLE" }?.second,
            artist = comments.firstOrNull { it.first == "ARTIST" }?.second,
            album = comments.firstOrNull { it.first == "ALBUM" }?.second
        )
    }
}
