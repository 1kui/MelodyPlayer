package com.melody.player.core.tags

/**
 * WAV / RIFF 容器解析：块结构为 id(4) + size(4 小端) + 数据（不足偶数字节补 1 字节）。
 * 部分写入工具会把整段 ID3v2 塞进 "id3 " 块，因此这里直接复用 Id3v2Parser。
 */
object RiffParser {

    private const val MAX_CHUNKS = 128

    fun parse(src: ByteSource): EmbeddedLyrics? {
        val head = src.readPrefix(12)
        if (head.size < 12) return null
        if (!TagBytes.startsWith(head, 0, "RIFF") || !TagBytes.startsWith(head, 8, "WAVE")) return null

        var offset = 12L
        var chunks = 0
        while (chunks < MAX_CHUNKS && offset + 8 <= src.size) {
            val hdr = src.readAt(offset, 8)
            if (hdr.size < 8) return null
            val id = TagBytes.ascii(hdr, 0, 4)
            val size = TagBytes.uint32le(hdr, 4)
            if (size < 0) return null
            offset += 8

            if (id == "id3 " || id == "ID3 ") {
                val payload = src.readAt(offset, minOf(size, 8L * 1024 * 1024).toInt())
                if (payload.isEmpty()) return null
                return Id3v2Parser.parse(ByteArraySource(payload))
            }

            offset += size + (size and 1L)
            chunks++
        }
        return null
    }
}
