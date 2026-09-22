package com.melody.player.core.tags

/**
 * 按容器格式分发到具体解析器。
 *
 * 嗅探顺序重要：FLAC 文件偶尔会在最前面挂一个非标准的 ID3v2 头（老工具转码留下），
 * 所以先判 ID3，再判各容器魔数。
 */
object AudioTagReader {

    fun extract(src: ByteSource): EmbeddedLyrics? = runCatching {
        val head = src.readPrefix(16)
        if (head.size < 4) return@runCatching null

        when {
            head.size >= 10 && TagBytes.startsWith(head, 0, "ID3") -> Id3v2Parser.parse(src)
            TagBytes.startsWith(head, 0, "fLaC") -> FlacParser.parse(src)
            TagBytes.startsWith(head, 0, "OggS") -> OggParser.parse(src)
            head.size >= 12 && TagBytes.startsWith(head, 4, "ftyp") -> Mp4Parser.parse(src)
            head.size >= 12 && TagBytes.startsWith(head, 0, "RIFF") && TagBytes.startsWith(head, 8, "WAVE") ->
                RiffParser.parse(src)
            else -> null
        }
    }.getOrNull()

    /** 该格式是否被支持（用于界面提示「此格式不支持内嵌歌词」）。 */
    fun isSupported(src: ByteSource): Boolean = runCatching {
        val head = src.readPrefix(16)
        TagBytes.startsWith(head, 0, "ID3") ||
            TagBytes.startsWith(head, 0, "fLaC") ||
            TagBytes.startsWith(head, 0, "OggS") ||
            (head.size >= 12 && TagBytes.startsWith(head, 4, "ftyp")) ||
            (head.size >= 12 && TagBytes.startsWith(head, 0, "RIFF") && TagBytes.startsWith(head, 8, "WAVE"))
    }.getOrDefault(false)
}
