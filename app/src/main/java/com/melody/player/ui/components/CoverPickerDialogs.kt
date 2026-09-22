package com.melody.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import com.melody.player.core.TimeFormat
import com.melody.player.core.online.ITunesHit
import com.melody.player.ui.icons.MelodyIcons

/**
 * 「选择专辑封面」的来源选择：在线搜索 / 从相册选 / 移除。
 *
 * 与歌词的候选流程同一套交互：先把"给哪首歌"钉死（标题写明），
 * 再让用户选来源 —— 不默认联网，也不默认用本地标签里的图。
 */
@Composable
fun CoverSourceDialog(
    songTitle: String,
    hasExisting: Boolean,
    searching: Boolean,
    /** 正在进行的步骤（搜索候选 / 下载封面 / 写入缓存）；空表示没有在跑。 */
    searchStage: String,
    onSearchOnline: () -> Unit,
    onPickFromGallery: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择专辑封面") },
        text = {
            Column {
                Text(
                    text = songTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                SourceRow(
                    title = if (searching) "正在联网获取…" else "在线搜索封面",
                    // 取一张封面要过三步：搜候选（受接口限流，最慢）→ 下载图 → 写盘。
                    // 只显示"正在搜索…"的话，卡在后两步时会像是卡死了
                    subtitle = if (searching && searchStage.isNotBlank()) {
                        "进行中：$searchStage"
                    } else {
                        "从 iTunes 搜候选，自己挑一张"
                    },
                    icon = MelodyIcons.CloudDownload,
                    enabled = !searching,
                    onClick = onSearchOnline
                )
                SourceRow(
                    title = "从相册选择",
                    subtitle = "用本机图片当封面（自定义）",
                    icon = MelodyIcons.AlbumArt,
                    enabled = true,
                    onClick = onPickFromGallery
                )
                if (hasExisting) {
                    SourceRow(
                        title = "移除封面",
                        subtitle = "删掉这张，退回内嵌封面或占位图案",
                        icon = MelodyIcons.Delete,
                        enabled = true,
                        onClick = onRemove
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 在线封面候选列表。小图异步加载（100×100，CDN 不限流），滚动时按 trackId 缓存。
 *
 * 底部保留"从相册选一张"：搜索结果经常是现场版/翻唱的封面，都不像时别把用户堵死。
 */
@Composable
fun CoverCandidatesDialog(
    songTitle: String,
    candidates: List<ITunesHit>,
    searching: Boolean,
    onPick: (ITunesHit) -> Unit,
    onPickFromGallery: () -> Unit,
    onDismiss: () -> Unit,
    loadThumb: suspend (ITunesHit) -> Bitmap?
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择封面") },
        text = {
            Column {
                Text(
                    text = "本地曲目：$songTitle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(candidates, key = { it.trackId }) { hit ->
                        CandidateRow(
                            hit = hit,
                            loadThumb = loadThumb,
                            onPick = { onPick(hit) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onPickFromGallery)
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = MelodyIcons.AlbumArt,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = "都不像？从相册选一张",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (searching) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun CandidateRow(
    hit: ITunesHit,
    loadThumb: suspend (ITunesHit) -> Bitmap?,
    onPick: () -> Unit
) {
    var thumb by remember(hit.trackId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(hit.trackId) { thumb = loadThumb(hit) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(MaterialTheme.shapes.small)
                .background(
                    // 没加载出来时先给一块和 App 占位封面同语言的渐变，避免白块闪烁
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(46.dp)
                )
            } else {
                Icon(
                    imageVector = MelodyIcons.AlbumArt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hit.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(hit.artist)
                    if (hit.album.isNotBlank()) append(" · ").append(hit.album)
                    if (hit.durationMs > 0) append(" · ").append(TimeFormat.clock(hit.durationMs))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
