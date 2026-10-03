package com.melody.player

import com.melody.player.core.PlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放速度的挡位与标注。
 *
 * 判算错了不崩，只是"按了没反应"或者"永远回不到原速"—— 正是要靠断言钉住的一类。
 */
class PlaybackSpeedTest {

    @Test
    fun `步进与上下限`() {
        assertEquals(1.05f, PlaybackSpeed.step(1.0f, 1), 0.0001f)
        assertEquals(0.95f, PlaybackSpeed.step(1.0f, -1), 0.0001f)
        assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.step(PlaybackSpeed.MIN, -1), 0.0001f)
        assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.step(PlaybackSpeed.MAX, 1), 0.0001f)
        // direction = 0 是"不动"，不是"往下减"
        assertEquals(1.0f, PlaybackSpeed.step(1.0f, 0), 0.0001f)
    }

    @Test
    fun `跨过原速时吸附到正好一倍`() {
        // 从 1.0 一路按减号是 1.0 → 0.95 → 0.9…，用户很难"刚好回到原速"；
        // 吸附之后跨过 1.0 的那一挡直接落到 1.0
        assertEquals(1.0f, PlaybackSpeed.step(0.95f, 1), 0.0001f)
        assertEquals(1.0f, PlaybackSpeed.step(1.05f, -1), 0.0001f)
        // 已经站在 1.0 上时，往两边都还是 1.0 附近的那一挡（不会来回吸住）
        assertEquals(1.05f, PlaybackSpeed.step(1.0f, 1), 0.0001f)
    }

    @Test
    fun `标注固定两位小数`() {
        assertEquals("1.00×", PlaybackSpeed.label(1f))
        assertEquals("1.25×", PlaybackSpeed.label(1.25f))
        assertEquals("0.75×", PlaybackSpeed.label(0.75f))
        assertEquals("1.50×", PlaybackSpeed.label(1.5f))
    }

    @Test
    fun `原速判定带容差`() {
        assertTrue(PlaybackSpeed.isDefault(1f))
        assertTrue(PlaybackSpeed.isDefault(1.0005f))
        assertFalse(PlaybackSpeed.isDefault(1.05f))
        assertFalse(PlaybackSpeed.isDefault(0.95f))
    }

    @Test
    fun `预设都在上下限内且含原速`() {
        assertTrue(PlaybackSpeed.PRESETS.all { it >= PlaybackSpeed.MIN && it <= PlaybackSpeed.MAX })
        assertTrue(PlaybackSpeed.PRESETS.any { PlaybackSpeed.isDefault(it) })
    }
}
