package com.melody.player.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * 「正在播放」的跳动柱状指示器。
 *
 * 三条柱用不同周期做无限动画，看起来像随机跳动，实际完全确定性 ——
 * 重组不会重置动画，停下来时也不会显得突兀。
 */
@Composable
fun PlayingBars(
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 3,
    animate: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "playing-bars")
    val indices = remember(barCount) { (0 until barCount).toList() }

    // map 是 inline 函数，所以可以在里面调用 @Composable 的 animateFloat
    val bars: List<Float> = indices.map { index ->
        if (!animate) {
            0.45f
        } else {
            val value by transition.animateFloat(
                initialValue = 0.32f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 520 + index * 170,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar-$index"
            )
            value
        }
    }

    Canvas(modifier) {
        val gap = size.width * 0.22f
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val minHeight = size.height * 0.20f
        for (i in 0 until barCount) {
            val barHeight = minHeight + (size.height - minHeight) * bars[i]
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
