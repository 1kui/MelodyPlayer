package com.melody.player.core

/**
 * 睡眠定时器的可选时长。
 *
 * 只有五挡、不给"自定义分钟数"：这个功能的使用场景是躺着准备睡，
 * 不该让人为了填一个数字去调键盘。
 */
enum class SleepOption(val minutes: Int, val label: String) {
    M15(15, "15 分钟"),
    M30(30, "30 分钟"),
    M45(45, "45 分钟"),
    M60(60, "1 小时"),
    M90(90, "1.5 小时");

    val millis: Long get() = minutes * 60_000L
}

/**
 * 睡眠定时的时间算术。
 *
 * 判算本身只有加减法，但**边界必须钉住**：`remaining` 不能返回负数
 * （界面上会变成 `-0:01`），`expired` 在"正好等于 deadline"时就必须算过期
 * （差一毫秒不判过期 = 定时器永远差一帧不触发）。
 *
 * [deadlineFrom] 只做算术，不碰时钟：deadline 由**调用方**算出并同时交给
 * 界面与播放服务，两边以同一个数为准，否则显示与到点会各走各的。
 */
object SleepTimer {
    fun deadlineFrom(nowMs: Long, option: SleepOption): Long = nowMs + option.millis

    /** 剩余毫秒；已经到点返回 0，绝不返回负数。 */
    fun remaining(deadlineMs: Long, nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0L)

    fun expired(deadlineMs: Long, nowMs: Long): Boolean = nowMs >= deadlineMs

    /** 倒计时文案：`29:59` / `1:29:00`；不足一分钟时给一句人话。 */
    fun remainingLabel(remainingMs: Long): String {
        if (remainingMs < 60_000L) return "不到 1 分钟"
        val totalSeconds = remainingMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
