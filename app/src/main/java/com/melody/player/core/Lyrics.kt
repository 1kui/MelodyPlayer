package com.melody.player.core

/** 一行歌词（带时间戳）。 */
data class LyricLine(val timeMs: Long, val text: String)

/** 歌词来源，用于在界面上如实标注「同步精度」而非假装精确。 */
enum class LyricsSource(val label: String) {
    EMBEDDED_SYNCED("内嵌时间戳歌词"),
    EMBEDDED_TIMED("内嵌 LRC 文本"),
    EMBEDDED_PLAIN("内嵌纯文本"),
    SIDECAR_LRC("同名 .lrc 文件"),
    IMPORTED_LRC("手动导入"),
    /** 用户主动点了「联网获取歌词」并选定了某一首。哪一家联网来源见 [Lyrics.container]。 */
    ONLINE_PICKED("在线获取"),
    /** 本地完全没有歌词时自动联网匹配到的，优先级最低。 */
    ONLINE_AUTO("在线匹配"),
    NONE("暂无歌词")
}

/**
 * 用户明确指定的那份歌词是怎么来的。
 *
 * 从文件导入和联网获取都写进 App 私有副本的同一个槽位（都是「用户选定的」，
 * 优先级最高、要覆盖内嵌歌词），但界面上的来源标注必须分得清。
 */
enum class LyricOrigin(val key: String) {
    FILE("file"),
    ONLINE("online"),

    /** 归档到 App 库时顺手存下的那份快照（同一份歌词同时写进了音频文件的标签）。 */
    ARCHIVED("archived");

    companion object {
        fun fromKey(key: String?): LyricOrigin? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 一首歌的歌词模型。
 *
 * [estimated] 为 true 表示时间戳是按总时长均匀估算出来的（原歌词没有时间信息），
 * 界面应当如实提示，避免用户以为是精确同步。
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val estimated: Boolean,
    val source: LyricsSource,
    val container: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val rawText: String? = null
) {
    /**
     * 展示用的歌词项：把时间戳完全相同的多行合并成一条（双语歌词的典型写法），
     * 这样中英文会一起高亮，而不会只亮最后一行。
     */
    val items: List<LyricLine> by lazy { mergeEqualTimestamps(lines) }

    /**
     * 返回 [positionMs] 时刻应当高亮的行下标；还没到第一行时返回 -1。
     * 用二分查找，避免滚动时每帧线性扫描整份歌词。
     */
    fun indexAt(positionMs: Long): Int {
        val list = items
        if (list.isEmpty()) return -1
        if (positionMs < list[0].timeMs) return -1
        var lo = 0
        var hi = list.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    val isUsable: Boolean get() = items.isNotEmpty()

    /**
     * 导出成 LRC 文本，用于写回音频文件的内嵌标签。
     *
     * 优先返回 [rawText]：歌词本来就是 LRC 时零损失（连 `[ti:]`、`[offset:]` 这些
     * 头部元信息都留着）；没有原文（例如来自 SYLT 帧）才按行拼时间戳。
     */
    fun toLrcText(): String? {
        rawText?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (items.isEmpty()) return null
        return buildString {
            items.forEach { append('[').append(TimeFormat.lrcStamp(it.timeMs)).append(']').append(it.text).append('\n') }
        }.trimEnd()
    }

    companion object {
        fun mergeEqualTimestamps(lines: List<LyricLine>): List<LyricLine> {
            if (lines.isEmpty()) return emptyList()
            val out = ArrayList<LyricLine>(lines.size)
            for (line in lines) {
                val last = out.lastOrNull()
                if (last != null && last.timeMs == line.timeMs) {
                    val merged = if (last.text.isBlank()) line.text else "${last.text}\n${line.text}"
                    out[out.size - 1] = last.copy(text = merged)
                } else {
                    out.add(line)
                }
            }
            return out
        }

        val NONE = Lyrics(
            lines = emptyList(),
            synced = false,
            estimated = false,
            source = LyricsSource.NONE
        )
    }
}

/**
 * 把没有时间戳的纯文本歌词按总时长均匀对齐。
 *
 * 这不是「真同步」，是没有更好信息时的折中：至少能让用户跟着滚动，
 * 因此结果会被标记为 [Lyrics.estimated]。
 */
object LyricsAligner {

    fun estimate(textLines: List<String>, durationMs: Long): List<LyricLine> {
        val useful = textLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (useful.isEmpty()) return emptyList()
        if (durationMs <= 0L) {
            return useful.mapIndexed { i, t -> LyricLine(i * 1000L, t) }
        }
        val n = useful.size
        return useful.mapIndexed { i, t ->
            // 把每行落在其「均分区间」的中点，首行不会离开头太远
            val time = durationMs * (2L * i + 1L) / (2L * n)
            LyricLine(time, t)
        }
    }
}
