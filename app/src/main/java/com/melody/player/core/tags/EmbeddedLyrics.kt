package com.melody.player.core.tags

/** 带时间戳的一行歌词（来自 SYLT / LRC）。 */
data class SyncedLyricLine(val timeMs: Long, val text: String)

/**
 * 从音频文件里提取出来的结果。
 *
 * 歌词有三种携带方式，优先级从高到低：
 *  1. [synced] —— SYLT 帧或 LRC 文本解析出的带时间戳歌词（能精确同步）
 *  2. [text]   —— USLT / Vorbis LYRICS 的纯文本歌词（需要按总时长均匀对齐）
 * 两者可能同时存在，界面自行决定用哪个。
 */
data class EmbeddedLyrics(
    val text: String?,
    val synced: List<SyncedLyricLine>,
    /** 来源描述，例如 "ID3v2.3 USLT"、"FLAC Vorbis Comment"，用于在界面上透明展示。 */
    val container: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null
) {
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && synced.isEmpty()

    companion object {
        /** 统一的构建入口：只有真的拿到歌词才返回非空对象。 */
        fun of(
            container: String,
            text: String? = null,
            synced: List<SyncedLyricLine> = emptyList(),
            title: String? = null,
            artist: String? = null,
            album: String? = null
        ): EmbeddedLyrics? {
            val trimmed = text?.trim()?.takeIf { it.isNotEmpty() }
            val lines = synced.filter { it.text.isNotBlank() || it.timeMs > 0 }
            if (trimmed == null && lines.isEmpty()) return null
            return EmbeddedLyrics(
                text = trimmed,
                synced = lines,
                container = container,
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                artist = artist?.trim()?.takeIf { it.isNotEmpty() },
                album = album?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    }
}
