package com.melody.player

import com.melody.player.core.ArtworkShape
import com.melody.player.core.LyricTextSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 两个外观偏好的纯逻辑：歌词字号档位与封面形状。
 *
 * 这两处出错的方式都很隐蔽 —— 偏好里存了个不认识的值就读回默认 —— 不是崩溃，
 * 只是"用起来不对劲"，所以用单测钉住。
 */
class AppearanceOptionsTest {

    @Test
    fun `字号四挡从小到大且标准挡是基准`() {
        val scales = LyricTextSize.entries.map { it.scale }
        assertEquals(scales.sorted(), scales)
        assertEquals(1.0f, LyricTextSize.STANDARD.scale, 0.0001f)
        assertEquals(
            listOf("小", "标准", "大", "特大"),
            LyricTextSize.entries.map { it.label }
        )
    }

    @Test
    fun `字号偏好存的是枚举名`() {
        LyricTextSize.entries.forEach {
            assertEquals(it, LyricTextSize.fromKey(it.name))
        }
        // 没存过 / 存了个旧版本的值 / 存了个乱七八糟的值，都退回「标准」
        assertEquals(LyricTextSize.STANDARD, LyricTextSize.fromKey(null))
        assertEquals(LyricTextSize.STANDARD, LyricTextSize.fromKey(""))
        assertEquals(LyricTextSize.STANDARD, LyricTextSize.fromKey("HUGE"))
        assertEquals(LyricTextSize.STANDARD, LyricTextSize.fromKey("large"))
    }

    @Test
    fun `封面形状两个选项且默认圆角方形`() {
        assertEquals(listOf("圆角方形", "圆形"), ArtworkShape.entries.map { it.label })
        assertEquals(ArtworkShape.ROUNDED, ArtworkShape.fromKey(null))
        assertEquals(ArtworkShape.ROUNDED, ArtworkShape.fromKey("SQUARE"))
        ArtworkShape.entries.forEach {
            assertEquals(it, ArtworkShape.fromKey(it.name))
        }
    }
}
