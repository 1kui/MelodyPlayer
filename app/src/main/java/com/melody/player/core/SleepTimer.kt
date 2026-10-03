package com.melody.player.core

/**
 * 睡眠定时器的**预设**时长。
 *
 * 五挡覆盖了绝大多数场景（躺下前顺手点一下），自定义时长另走
 * [SleepTimer.customMinutes]，不往这个枚举里塞"随便填一个数"。
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

    /** 自定义时长的下限（分钟）。0 是"立刻暂停"，不是"定时"，所以从 1 起。 */
    const val MIN_CUSTOM_MINUTES = 1

    /** 自定义时长的上限（分钟）：10 小时。再长就不叫睡眠定时了。 */
    const val MAX_CUSTOM_MINUTES = 600

    fun deadlineFrom(nowMs: Long, option: SleepOption): Long =
        deadlineFrom(nowMs, option.minutes)

    /** 自定义分钟数的 deadline。与预设共用同一条算式。 */
    fun deadlineFrom(nowMs: Long, minutes: Int): Long = nowMs + minutes * 60_000L

    /**
     * 把用户敲进输入框的文字解析成分钟数；**不合法返回 null**（而不是夹到边界）。
     *
     * 为什么不夹取：用户在输入框里敲 `0`，屏幕上的数字是 0，夹成 1 分钟意味着
     * 按确认的那一刻发生了一次看不见的修改 —— 定时到点会比他以为自己设的早 1 分钟。
     * 返回 null 则由界面把「确定」置灰并说明范围，用户看到的与发生的完全一致。
     *
     * 顺手剥掉非数字字符（`30分` / `30 分钟` 都能用），但"全是非数字"要判死，
     * 否则 `abc` 会被剥成空串再被当成 0。
     */
    fun customMinutes(raw: String): Int? {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val minutes = digits.toIntOrNull() ?: return null
        return if (minutes in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES) minutes else null
    }

    /**
     * 自定义时长的显示文案：`45 分钟` / `1 小时` / `1 小时 30 分钟`。
     *
     * 超过一小时就换算成"小时 + 分钟"，因为屏幕上的 `90 分钟` 要在脑子里除一次 60
     * 才知道是多久 —— 而用户正是在判断"睡之前够不够听完"。
     */
    fun customLabel(minutes: Int): String {
        if (minutes < 60) return "$minutes 分钟"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0) "$hours 小时" else "$hours 小时 $rest 分钟"
    }

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
