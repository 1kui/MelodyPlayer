package com.melody.player.core

/**
 * LRC 歌词文本解析。
 *
 * 支持：
 *  - `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss:xx]`（第三段用冒号是常见笔误）
 *  - 一行多个时间标签：`[00:12.00][01:20.00]副歌`
 *  - 元信息标签：`[ti:]` `[ar:]` `[al:]` `[by:]` `[offset:±ms]` `[length:]` 等
 *  - 纯文本（完全没有时间标签）—— 交给上层按总时长均匀对齐
 *
 * offset 采用社区通行约定：`[offset:+500]` 表示歌词提前 0.5 秒出现，
 * 因此最终时间 = 标签时间 - offset。
 */
object LrcParser {

    private val TIMESTAMP = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META = Regex("""^\[([A-Za-z#]+):(.*)]""")

    data class Parsed(
        val lines: List<LyricLine>,
        val untimedText: List<String>,
        val title: String?,
        val artist: String?,
        val album: String?,
        val by: String?,
        val offsetMs: Long
    ) {
        val hasTimestamps: Boolean get() = lines.isNotEmpty()
    }

    fun parse(raw: String): Parsed {
        val timed = ArrayList<LyricLine>()
        val untimed = ArrayList<String>()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var by: String? = null
        var offsetMs = 0L
        var sawAnyTimestamp = false

        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')

        for (rawLine in normalized.split('\n')) {
            val line = rawLine.trimEnd()
            if (line.isEmpty()) {
                // 段落之间的空行保留，用于控制歌词节奏
                untimed.add("")
                continue
            }

            var cursor = 0
            val stamps = ArrayList<Long>()
            var consumedMeta = false

            while (true) {
                val rest = line.substring(cursor)
                val ts = TIMESTAMP.find(rest)
                if (ts != null) {
                    stamps.add(toMillis(ts.groupValues[1], ts.groupValues[2], ts.groupValues[3]))
                    cursor += ts.value.length
                    consumedMeta = true
                    continue
                }
                val meta = META.find(rest)
                if (meta != null) {
                    val key = meta.groupValues[1].lowercase()
                    val value = meta.groupValues[2].trim()
                    when (key) {
                        "ti" -> title = title ?: value.ifEmpty { null }
                        "ar" -> artist = artist ?: value.ifEmpty { null }
                        "al" -> album = album ?: value.ifEmpty { null }
                        "by" -> by = by ?: value.ifEmpty { null }
                        "offset" -> offsetMs = value.toLongOrNull() ?: offsetMs
                    }
                    cursor += meta.value.length
                    consumedMeta = true
                    continue
                }
                break
            }

            val text = line.substring(cursor).trim()

            if (stamps.isEmpty()) {
                // 既不是时间标签也不是元信息 → 纯文本行
                if (!consumedMeta) untimed.add(text)
            } else {
                sawAnyTimestamp = true
                for (s in stamps) timed.add(LyricLine(s, text))
            }
        }

        val adjusted = timed
            .map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }
            .sortedBy { it.timeMs }

        return Parsed(
            lines = adjusted,
            untimedText = if (sawAnyTimestamp) emptyList() else untimed.filter { it.isNotBlank() },
            title = title,
            artist = artist,
            album = album,
            by = by,
            offsetMs = offsetMs
        )
    }

    private fun toMillis(minutes: String, seconds: String, fraction: String?): Long {
        val m = minutes.toLongOrNull() ?: 0L
        val s = seconds.toLongOrNull() ?: 0L
        val frac = when {
            fraction.isNullOrEmpty() -> 0L
            fraction.length == 1 -> fraction.toLong() * 100
            fraction.length == 2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return m * 60_000L + s * 1_000L + frac
    }
}
