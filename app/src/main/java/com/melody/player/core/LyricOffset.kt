package com.melody.player.core

/**
 * 歌词时间轴偏移（用户手动校正）。
 *
 * 正偏移 = 歌词整体**延后**出现；负 = **提前**。只影响 App 内的显示与高亮，
 * 绝不写回音频文件标签 —— 别的播放器读到的必须是原始时间轴，
 * 不能被本 App 的显示校正污染。
 *
 * 关键在于它是"查表时平移"，而不是"重排时间轴"：播放位置本身不动，
 * 只把送进歌词索引的位置挪一下。这样改偏移不会重建时间轴、不会让歌词缓存失效，
 * 播放中调一下立刻生效。
 */
object LyricOffset {
    /** 一次步进的毫秒数。半秒是"人耳能听出来"的最小档。 */
    const val STEP_MS = 500L

    /** 上下限 ±30 秒，覆盖常见的前奏/间奏差异；再大就不是"校正"而是"换一份歌词"了。 */
    const val MAX_MS = 30_000L

    /** 步进一挡并钳到上下限。`direction` 为 +1 延后、-1 提前。 */
    fun step(currentMs: Long, direction: Int): Long =
        (currentMs + direction * STEP_MS).coerceIn(-MAX_MS, MAX_MS)

    /**
     * 高亮判定用的「校正后查询位置」= 播放位置 − 偏移。
     *
     * 偏移为正（歌词延后）时查更早的行 → 当前该高亮的那行"来得更晚"，
     * 观感就是歌词整体往后挪了。
     *
     * 刻意**不**钳到 0：钳 0 会把开头几行塌到同一个时刻，被合并成一行，
     * 用户看到的是"第一行歌词连成了奇怪的一长条"，而不是"歌词整体提前"。
     */
    fun apply(positionMs: Long, offsetMs: Long): Long = positionMs - offsetMs

    /** 界面上的当前偏移标注：`未校正` / `延后 0.5 秒` / `提前 1.5 秒`。 */
    fun label(offsetMs: Long): String = when {
        offsetMs == 0L -> "未校正"
        offsetMs > 0L -> "延后 ${offsetSecondsText(offsetMs)}"
        else -> "提前 ${offsetSecondsText(-offsetMs)}"
    }

    /** 把毫秒写成不带多余小数点的秒数：500 → `0.5`、1000 → `1`、1500 → `1.5`。 */
    private fun offsetSecondsText(offsetMs: Long): String {
        val whole = offsetMs / 1000L
        val tenth = (offsetMs % 1000L) / 100L
        return if (tenth == 0L) "$whole 秒" else "$whole.$tenth 秒"
    }
}
