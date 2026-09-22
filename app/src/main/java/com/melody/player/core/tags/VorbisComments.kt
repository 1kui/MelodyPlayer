package com.melody.player.core.tags

/**
 * Vorbis Comment 解析（FLAC 的 VORBIS_COMMENT 元数据块、Ogg Vorbis 注释头、OpusTags 共用同一结构）。
 *
 * 结构：u32le 厂商串长度 + 厂商串 + u32le 条目数 + N × (u32le 长度 + "KEY=VALUE")
 */
internal object VorbisComments {

    private const val MAX_ENTRIES = 4096

    /** 会被当作歌词的键名（大写比较，兼容各家不规范写法）。 */
    private val LYRICS_KEYS = setOf(
        "LYRICS",
        "UNSYNCEDLYRICS",
        "UNSYNCHRONISEDLYRICS",
        "SYNCEDLYRICS",
        "LYRIC",
        "LRC"
    )

    private val TITLE_KEYS = setOf("TITLE")
    private val ARTIST_KEYS = setOf("ARTIST", "ALBUMARTIST", "PERFORMER")
    private val ALBUM_KEYS = setOf("ALBUM")

    fun parse(data: ByteArray, start: Int, end: Int): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        var pos = start
        if (pos + 4 > end) return result

        val vendorLen = TagBytes.uint32le(data, pos).toInt()
        if (vendorLen < 0 || pos + 4 + vendorLen > end) return result
        pos += 4 + vendorLen
        if (pos + 4 > end) return result

        val count = TagBytes.uint32le(data, pos)
        pos += 4
        if (count < 0) return result

        var i = 0L
        while (i < count && i < MAX_ENTRIES && pos + 4 <= end) {
            val len = TagBytes.uint32le(data, pos).toInt()
            pos += 4
            if (len < 0 || pos + len > end) break
            val entry = String(data, pos, len, Charsets.UTF_8)
            pos += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                result.add(entry.substring(0, eq).trim().uppercase() to entry.substring(eq + 1))
            }
            i++
        }
        return result
    }

    fun toLyrics(comments: List<Pair<String, String>>, container: String): EmbeddedLyrics? {
        val lyrics = comments.firstOrNull { it.first in LYRICS_KEYS }?.second
        val title = comments.firstOrNull { it.first in TITLE_KEYS }?.second
        val artist = comments.firstOrNull { it.first in ARTIST_KEYS }?.second
        val album = comments.firstOrNull { it.first in ALBUM_KEYS }?.second
        return EmbeddedLyrics.of(
            container = container,
            text = lyrics,
            title = title,
            artist = artist,
            album = album
        )
    }
}
