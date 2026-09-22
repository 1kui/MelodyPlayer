package com.melody.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melody.player.core.Song
import com.melody.player.core.TimeFormat
import com.melody.player.ui.icons.MelodyIcons

/**
 * 曲库列表的行。
 *
 * 正在播放的那一行：标题换成主色、右侧的时长换成跳动柱 —— 用户在长列表里
 * 一眼就能定位到当前曲目，不需要靠背景色差去猜。
 */
@Composable
fun SongRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
    onHide: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onUnarchive: (() -> Unit)? = null,
    /** 手动联网取这张专辑封面（本地标签里没有封面时用户会想试一下）。 */
    onFetchCover: (() -> Unit)? = null,
    /** 改这首歌的歌名/歌手/专辑（App 内显示，不动文件）。 */
    onEditSong: (() -> Unit)? = null,
    /** 已经有 App 库副本（本条就是副本，或它的原文件已归档）。 */
    archived: Boolean = false
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongArtwork(song = song, size = 52.dp, corner = 12.dp)

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (archived) {
                    Spacer(Modifier.width(6.dp))
                    ArchiveBadge()
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${song.artistOrUnknown} · ${song.albumOrUnknown}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier.width(34.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isCurrent) {
                PlayingBars(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(width = 18.dp, height = 18.dp),
                    animate = isPlaying
                )
            } else {
                Text(
                    text = TimeFormat.clock(song.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box {
            IconAction(
                imageVector = MelodyIcons.MoreVertical,
                contentDescription = "「${song.title}」的更多操作",
                onClick = { menuOpen = true },
                iconSize = 20.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("下一首播放") },
                    leadingIcon = { Icon(MelodyIcons.SkipNext, null, Modifier.size(20.dp)) },
                    onClick = {
                        menuOpen = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("立即播放") },
                    leadingIcon = { Icon(MelodyIcons.Play, null, Modifier.size(20.dp)) },
                    onClick = {
                        menuOpen = false
                        onClick()
                    }
                )
                if (onFetchCover != null) {
                    DropdownMenuItem(
                        text = { Text("选择专辑封面") },
                        leadingIcon = { Icon(MelodyIcons.AlbumArt, null, Modifier.size(20.dp)) },
                        onClick = {
                            menuOpen = false
                            onFetchCover()
                        }
                    )
                }
                if (onEditSong != null) {
                    DropdownMenuItem(
                        text = { Text("编辑歌曲信息") },
                        leadingIcon = { Icon(MelodyIcons.Edit, null, Modifier.size(20.dp)) },
                        onClick = {
                            menuOpen = false
                            onEditSong()
                        }
                    )
                }
                if (onArchive != null) {
                    DropdownMenuItem(
                        text = { Text("归档到 App 库") },
                        leadingIcon = { Icon(MelodyIcons.Archive, null, Modifier.size(20.dp)) },
                        onClick = {
                            menuOpen = false
                            onArchive()
                        }
                    )
                }
                if (onUnarchive != null) {
                    DropdownMenuItem(
                        text = { Text("取消归档") },
                        leadingIcon = { Icon(MelodyIcons.ArchiveOff, null, Modifier.size(20.dp)) },
                        onClick = {
                            menuOpen = false
                            onUnarchive()
                        }
                    )
                }
                if (onHide != null) {
                    DropdownMenuItem(
                        text = { Text("隐藏这首") },
                        leadingIcon = { Icon(MelodyIcons.EyeOff, null, Modifier.size(20.dp)) },
                        onClick = {
                            menuOpen = false
                            onHide()
                        }
                    )
                }
                if (onRemove != null) {
                    DropdownMenuItem(
                        text = { Text("移出列表") },
                        leadingIcon = { Icon(MelodyIcons.Delete, null, Modifier.size(20.dp)) },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        }
                    )
                }
            }
        }
    }
}

/**
 * 「已归档」小标签。
 *
 * 用 secondaryContainer 而不是主色：这是**状态**而不是可点的操作，
 * 用主色会和「正在播放」的标题抢注意力。
 */
@Composable
private fun ArchiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = "已归档",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
