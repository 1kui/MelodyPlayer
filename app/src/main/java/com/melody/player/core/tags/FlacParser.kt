package com.melody.player.core.tags

/**
 * FLAC 解析：跳过 "fLaC" 魔数后是一串元数据块，
 * 结构为 1 字节（最高位=是否为最后一块，低 7 位=块类型）+ 3 字节大端长度。
 * 块类型 4 即 VORBIS_COMMENT，歌词通常放在 LYRICS 键里。
 */
object FlacParser {

    private const val BLOCK_VORBIS_COMMENT = 4
    private const val MAX_BLOCK_BYTES = 16 * 1024 * 1024
    private const val MAX_BLOCKS = 64

    fun parse(src: ByteSource): EmbeddedLyrics? {
        val magic = src.readPrefix(4)
        if (magic.size < 4 || !TagBytes.startsWith(magic, 0, "fLaC")) return null

        var offset = 4L
        var blocks = 0
        while (blocks < MAX_BLOCKS && offset + 4 <= src.size) {
            val header = src.readAt(offset, 4)
            if (header.size < 4) return null
            val isLast = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = TagBytes.uint24be(header, 1)
            if (length < 0) return null
            offset += 4

            if (type == BLOCK_VORBIS_COMMENT) {
                val size = minOf(length, MAX_BLOCK_BYTES)
                val block = src.readAt(offset, size)
                val comments = VorbisComments.parse(block, 0, block.size)
                return VorbisComments.toLyrics(comments, "FLAC Vorbis Comment")
            }

            if (isLast) return null
            offset += length
            blocks++
        }
        return null
    }
}
