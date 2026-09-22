package com.melody.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.Song
import com.melody.player.ui.icons.MelodyIcons

/**
 * 迷你播放条。
 *
 * 顶部那条 2dp 细线就是播放进度 —— 不占额外高度，但用户余光能看到进度在走。
 * 整条除了两个控制按钮之外都可点，点击展开全屏播放页。
 */
@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onPrevious: (() -> Unit)? = null
) {
    if (song == null) return
    val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs) else 0f

    Surface(
        modifier = modifier.fillMaxWidth(),
        // 与设置页卡片共用同一档圆角，避免同屏出现两种弧度
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Column {
            ThinProgressLine(
                fraction = fraction,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArtwork(song = song, size = 44.dp, corner = 10.dp, showGlyph = true)

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artistOrUnknown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (onPrevious != null) {
                    IconAction(
                        imageVector = MelodyIcons.SkipPrevious,
                        contentDescription = "上一首",
                        onClick = onPrevious,
                        iconSize = 22.dp
                    )
                }

                IconAction(
                    imageVector = if (isPlaying) MelodyIcons.Pause else MelodyIcons.Play,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    onClick = onToggle,
                    iconSize = 26.dp,
                    containerColor = MaterialTheme.colorScheme.primary,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    touchSize = 44.dp
                )

                IconAction(
                    imageVector = MelodyIcons.SkipNext,
                    contentDescription = "下一首",
                    onClick = onNext,
                    iconSize = 22.dp
                )
            }
        }
    }
}
