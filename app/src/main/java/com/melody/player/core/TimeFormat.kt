package com.melody.player.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间文案格式化。抽成纯逻辑类，单测可以覆盖负数、未知时长、跨小时这些边界。
 */
object TimeFormat {

    const val UNKNOWN = "--:--"

    /** 播放进度常用格式：`3:45`；超过一小时为 `1:03:45`。负数或未知返回 [UNKNOWN]。 */
    fun clock(ms: Long): String {
        if (ms < 0) return UNKNOWN
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** 曲库统计用的中文时长：`3 分 45 秒` / `1 小时 2 分`。 */
    fun durationText(ms: Long): String {
        if (ms <= 0) return "0 秒"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
            hours > 0 -> "$hours 小时"
            minutes > 0 && seconds > 0 -> "$minutes 分 $seconds 秒"
            minutes > 0 -> "$minutes 分"
            else -> "$seconds 秒"
        }
    }

    /**
     * LRC 时间戳：`[03:07.42]`。
     *
     * 写回文件内嵌歌词时用它；统一两位小数，避免出现 `[0:5]` 这种只有纯播放器自己认的写法。
     */
    fun lrcStamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val totalSeconds = safe / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val hundredths = (safe % 1000L) / 10L
        return "%02d:%02d.%02d".format(minutes, seconds, hundredths)
    }

    /** 快进/快退提示用的相对偏移：`+10 秒` / `-15 秒`。 */
    fun delta(ms: Long): String {
        val seconds = ms / 1000L
        return if (seconds >= 0) "+$seconds 秒" else "$seconds 秒"
    }

    /**
     * 「什么时候的事」：`今天保存` / `昨天保存` / `3 天前保存` / `2026-09-12`。
     *
     * 一个月以内说相对时间（用户想的是"我前几天弄的"），更早的给具体日期
     * （"37 天前"要心算，日期不用）。
     *
     * [nowSec] 由调用方交进来，默认才取当前时间：依赖"现在"的函数一旦把"现在"
     * 写死在里面，测试就只能跟着时钟跑 —— 今天是"3 天前"、明天变成"4 天前"，
     * 边界（0 秒 / 刚好一天 / 刚好 30 天）根本钉不住。
     */
    fun ago(epochSec: Long, nowSec: Long = System.currentTimeMillis() / 1000L): String {
        if (epochSec <= 0L) return "保存时间未知"
        val days = (nowSec - epochSec) / 86_400L
        return when {
            days <= 0L -> "今天保存"
            days == 1L -> "昨天保存"
            days < 30L -> "$days 天前保存"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(epochSec * 1000L))
        }
    }
}
