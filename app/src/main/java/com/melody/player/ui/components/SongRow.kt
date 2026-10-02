package com.melody.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    /** 把这首歌加进某个自建歌单（会弹出歌单选择）。 */
    onAddToPlaylist: (() -> Unit)? = null,
    /** 歌单视图里：把这一首往上挪一位。到第一位时传 null，菜单项就不出现。 */
    onMoveUp: (() -> Unit)? = null,
    /** 歌单视图里：把这一首往下挪一位。 */
    onMoveDown: (() -> Unit)? = null,
    /** 歌单视图里：只从歌单移除，不动曲库。 */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** 已经有 App 库副本（本条就是副本，或它的原文件已归档）。 */
    archived: Boolean = false,
    /**
     * 多选模式下这一行处于选中状态。
     *
     * 为 null 时就是普通单选态：不出复选框、行菜单照常。
     * 用 Boolean? 而不是「bool + 另一个开关」是因为界面上只有两种状态，
     * 分成两个参数就会出现「复选框显示但没人管它」这种组合。
     */
    selected: Boolean? = null,
    /** 多选模式下点这一行（切换选中）。 */
    onToggleSelect: (() -> Unit)? = null,
    /** 长按进入多选并选中这一首。 */
    onBeginSelection: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selectionMode = selected != null
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            // combinedClickable 而不是 clickable：长按是多选的入口，而 clickable 只给单击回调。
            // 已在多选态时长按不再嵌套进多选，改为切换这一行的选中 —— 长按去做"再确认一次"
            // 比什么都不做强，尤其是批量操作中途想再勾两首的时候。
            .combinedClickable(
                onClick = { (if (selectionMode) onToggleSelect ?: onClick else onClick)() },
                onLongClick = {
                    if (onBeginSelection != null || onToggleSelect != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (selectionMode) onToggleSelect?.invoke() else onBeginSelection?.invoke()
                    }
                }
            )
            .padding(
                start = if (selectionMode) 8.dp else 16.dp,
                end = 4.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected == true,
                onCheckedChange = { onToggleSelect?.invoke() }
            )
            Spacer(Modifier.width(6.dp))
        }

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

        // 多选模式下不挂行菜单：那一整套「下一首播放 / 编辑信息…」都是针对这一首的，
        // 而用户此时的注意力在"选了哪些"上。留着只会让人在批量操作时误点单曲操作。
        if (!selectionMode) {
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
                    // 歌单视图里的三个操作排在归档/隐藏之前：它们是"当前正在做的事"，
                    // 埋在一堆通用操作里等于找不到
                    if (onMoveUp != null) {
                        DropdownMenuItem(
                            text = { Text("上移一位") },
                            leadingIcon = { Icon(MelodyIcons.ArrowUp, null, Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onMoveUp()
                            }
                        )
                    }
                    if (onMoveDown != null) {
                        DropdownMenuItem(
                            text = { Text("下移一位") },
                            leadingIcon = { Icon(MelodyIcons.ArrowDown, null, Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onMoveDown()
                            }
                        )
                    }
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("从歌单移除") },
                            leadingIcon = { Icon(MelodyIcons.Delete, null, Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onRemoveFromPlaylist()
                            }
                        )
                    }
                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("加入歌单") },
                            leadingIcon = { Icon(MelodyIcons.PlaylistAdd, null, Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onAddToPlaylist()
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
                }   // DropdownMenu
            }
        }   // if (!selectionMode)
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
