package com.melody.player.core

import java.util.Locale
import kotlin.math.abs

/**
 * 播放速度（变速不变调）。
 *
 * 值是"倍数"，1.0 = 原速。上限刻意只到 1.5× —— 再快就只能听个响，
 * 而这个 App 里没有播客/有声书的章节概念，不需要 2× 那种为赶进度存在的挡位。
 *
 * [step] 会在跨过 1.0 时吸附到正好 1.0：从 1.0 起步一路按减号是
 * 1.0 → 0.95 → 0.9 → …，用户很难"刚好回到原速"；吸附之后一挡就到 1.0。
 */
object PlaybackSpeed {
    const val MIN = 0.75f
    const val MAX = 1.5f
    const val STEP = 0.05f

    /** 动作弹层里列出的预设。都是"听起来有区别"的档，不摆一串 0.05 的碎步。 */
    val PRESETS = listOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)

    /** 步进并钳位；跨过 1.0 时落到正好 1.0。`direction` 为 +1 加速、-1 减速。 */
    fun step(current: Float, direction: Int): Float {
        if (direction == 0) return normalize(current)
        val next = current + direction * STEP
        if (next <= MIN) return MIN
        if (next >= MAX) return MAX
        if ((current < 1f && next > 1f) || (current > 1f && next < 1f)) return 1f
        return normalize(next)
    }

    /**
     * 非整数速度在界面上必须写清倍数 —— 只画一个"音波"图标，用户不知道现在是 1.1 还是 1.5。
     * 固定用 [Locale.ROOT]：某些地区的小数点是逗号，`1,25×` 在中文界面里格格不入。
     */
    fun label(speed: Float): String = String.format(Locale.ROOT, "%.2f×", speed)

    /** 是否原速（浮点比较留容差，避免 0.999999 被当成"改过"而多标一个小徽章）。 */
    fun isDefault(speed: Float): Boolean = abs(speed - 1f) < 0.001f

    private fun normalize(value: Float): Float =
        Math.round(value.coerceIn(MIN, MAX) * 100f) / 100f
}
