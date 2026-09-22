package com.melody.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.melody.player.core.TimeFormat

/**
 * 可拖拽的播放进度条。
 *
 * 没有用 Material 的 Slider：一来 1.3.1 的 Slider 不支持自定义轨道/滑块外观，
 * 二来音乐播放器需要「拖动时进度条跟随手指、但不要每帧都去 seek 播放器」的行为，
 * 自己画反而更短更可控。
 *
 * 拖动过程中的预览位置只在本地状态里更新，抬手时才提交一次 seek ——
 * 避免持续 seek 造成音频卡顿。
 */
@Composable
fun MelodySeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackHeight: Dp = 6.dp,
    showTimeLabels: Boolean = true
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val safeDuration = durationMs.coerceAtLeast(0L)
    val progressFraction = if (safeDuration > 0L) {
        (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = dragFraction ?: progressFraction
    val shownPosition = if (dragFraction != null) (shownFraction * safeDuration).toLong() else positionMs

    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight + THUMB_TOUCH_PADDING * 2)
                .pointerInput(enabled, safeDuration) {
                    if (!enabled || safeDuration <= 0L) return@pointerInput
                    awaitEachGesture {
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            dragFraction = (down.position.x / width).coerceIn(0f, 1f)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (change.positionChanged()) {
                                    dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                                    change.consume()
                                }
                            }
                            // 抬手才提交一次 seek（拖动过程中只更新本地预览）
                            dragFraction?.let { fraction -> onSeek((fraction * safeDuration).toLong()) }
                        } finally {
                            // 手势被中途取消（切页、切曲打断，协程被 cancel）时也必须清掉，
                            // 否则进度条会永久停在手指最后停下的位置 —— 再也没有事件来复位它。
                            dragFraction = null
                        }
                    }
                }
        ) {
            val centerY = this.size.height / 2f
            val radius = trackHeight.toPx() / 2f
            val thumbRadius = THUMB_RADIUS.toPx()
            val trackLeft = thumbRadius
            val trackRight = this.size.width - thumbRadius
            val trackWidth = (trackRight - trackLeft).coerceAtLeast(1f)

            // 轨道
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(trackLeft, centerY - radius),
                size = Size(trackWidth, trackHeight.toPx()),
                cornerRadius = CornerRadius(radius, radius)
            )
            // 已播放部分
            val filled = trackWidth * shownFraction
            if (filled > 0f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(trackLeft, centerY - radius),
                    size = Size(filled, trackHeight.toPx()),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
            // 滑块
            val thumbX = trackLeft + filled
            drawCircle(color = surfaceColor, radius = thumbRadius + 1.5.dp.toPx(), center = Offset(thumbX, centerY))
            drawCircle(color = thumbColor, radius = thumbRadius, center = Offset(thumbX, centerY))
        }

        if (showTimeLabels) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = TimeFormat.clock(shownPosition),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = TimeFormat.clock(safeDuration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 仅做视觉展示的细进度线，用于迷你播放条顶部。 */
@Composable
fun ThinProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 2.dp,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified
) {
    val active = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    val track = if (trackColor == Color.Unspecified) Color.Transparent else trackColor
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(color = track)
        drawRect(color = active, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
    }
}

private val THUMB_RADIUS = 7.dp
private val THUMB_TOUCH_PADDING = 10.dp
