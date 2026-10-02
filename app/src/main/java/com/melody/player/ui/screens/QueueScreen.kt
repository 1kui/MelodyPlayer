package com.melody.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.TimeFormat
import com.melody.player.ui.components.EmptyState
import com.melody.player.ui.components.IconAction
import com.melody.player.ui.components.PlayingBars
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.components.SongRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState

@Composable
fun QueueTopBar(
    queueSize: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "播放队列",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$queueSize 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            IconAction(
                imageVector = MelodyIcons.Delete,
                contentDescription = "清空播放队列",
                onClick = onClear,
                enabled = queueSize > 0,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
fun QueueContent(
    state: PlayerUiState,
    positionMs: Long,
    contentPadding: PaddingValues,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onMoveIndex: (Int, Int) -> Unit = { _, _ -> }
) {
    if (state.queue.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = MelodyIcons.QueueList,
                title = "播放队列是空的",
                description = "去音乐库点一首歌，队列会自动填满整张列表；也可以在曲目右侧菜单里选「下一首播放」。"
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp
        )
    ) {
        val current = state.currentSong
        if (current != null) {
            item(key = "now-playing-header") {
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp)
                )
            }
            item(key = "now-playing") {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SongArtwork(song = current, size = 62.dp, corner = 14.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = current.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = current.artistOrUnknown,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        PlayingBars(
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(width = 20.dp, height = 20.dp),
                            animate = state.isPlaying
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = TimeFormat.clock(positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            item(key = "queue-divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "队列中的曲目",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = TimeFormat.clock(state.queue.sumOf { it.durationMs }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        itemsIndexed(items = state.queue, key = { _, song -> "queue-${song.key}" }) { index, song ->
            SongRow(
                song = song,
                index = index,
                isCurrent = state.currentIndex == index,
                isPlaying = state.isPlaying,
                onClick = { onPlayIndex(index) },
                onPlayNext = { onPlayIndex(index) },
                onRemove = { onRemoveIndex(index) },
                // 队列顺序即播放顺序，所以在这里也能改；到两端时菜单项直接不出现，
                // 而不是留一个点了没反应还弹提示的按钮
                onMoveUp = if (index > 0) {
                    { onMoveIndex(index, index - 1) }
                } else {
                    null
                },
                onMoveDown = if (index < state.queue.lastIndex) {
                    { onMoveIndex(index, index + 1) }
                } else {
                    null
                }
            )
        }
    }
}
