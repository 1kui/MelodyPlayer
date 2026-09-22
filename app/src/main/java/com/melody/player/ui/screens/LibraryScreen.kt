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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.FileNames
import com.melody.player.core.Song
import com.melody.player.core.SortMode
import com.melody.player.ui.components.EmptyState
import com.melody.player.ui.components.IconAction
import com.melody.player.ui.components.SongRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState

/**
 * 曲库页顶栏。
 *
 * 用固定高度的 TopAppBar 而不是 LargeTopAppBar：后者在大标题收起时高度是动画变化的，
 * 内容的顶部内边距会跟着抖一下；固定高度配 pinned 滚动行为，滚动时的表面色变化
 * 同样是 Material 规范里的效果，但稳定得多。
 *
 * 搜索默认收起成图标，点开才在原位展开输入框 —— 首屏要把空间留给曲目本身。
 */
@Composable
fun LibraryTopBar(
    searchActive: Boolean,
    query: String,
    sort: SortMode,
    totalCount: Int,
    onQueryChange: (String) -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onSortChange: (SortMode) -> Unit,
    onImportAudio: () -> Unit,
    onRefresh: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var importMenuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = "音乐库",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (totalCount > 0) "$totalCount 首本地音乐" else "还没有导入音乐",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            IconAction(
                imageVector = if (searchActive) MelodyIcons.Close else MelodyIcons.Search,
                contentDescription = if (searchActive) "关闭搜索" else "搜索曲目",
                onClick = { onSearchToggle(!searchActive) },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                IconAction(
                    imageVector = MelodyIcons.Sort,
                    contentDescription = "排序方式：${sort.label}",
                    onClick = { sortMenuOpen = true },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            leadingIcon = {
                                if (mode == sort) {
                                    Icon(MelodyIcons.Check, null, Modifier.size(18.dp))
                                } else {
                                    Spacer(Modifier.size(18.dp))
                                }
                            },
                            onClick = {
                                sortMenuOpen = false
                                onSortChange(mode)
                            }
                        )
                    }
                }
            }
            Box {
                IconAction(
                    imageVector = MelodyIcons.ImportMusic,
                    contentDescription = "导入音乐",
                    onClick = { importMenuOpen = true },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DropdownMenu(expanded = importMenuOpen, onDismissRequest = { importMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重新扫描媒体库") },
                        leadingIcon = { Icon(MelodyIcons.Refresh, null, Modifier.size(18.dp)) },
                        onClick = {
                            importMenuOpen = false
                            onRefresh()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("从文件选择…") },
                        leadingIcon = { Icon(MelodyIcons.Folder, null, Modifier.size(18.dp)) },
                        onClick = {
                            importMenuOpen = false
                            onImportAudio()
                        }
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

/** 曲库页主体内容。 */
@Composable
fun LibraryContent(
    state: PlayerUiState,
    contentPadding: PaddingValues,
    onSongClick: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onHideSong: (Song) -> Unit,
    onRemoveImported: (Song) -> Unit,
    onArchiveSong: (Song) -> Unit,
    onUnarchiveSong: (Song) -> Unit,
    onQueryChange: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onImportAudio: () -> Unit,
    onRestoreHidden: () -> Unit,
    onFetchCover: (Song) -> Unit,
    onEditSong: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    // 取消归档会删掉 App 库里那份副本（原文件已不在时它就是仅存的一份），
    // 所以从行菜单进来也必须先确认一次，不能点一下就没
    var pendingUnarchive by remember { mutableStateOf<Song?>(null) }

    pendingUnarchive?.let { song ->
        AlertDialog(
            onDismissRequest = { pendingUnarchive = null },
            title = {
                Text(
                    text = "取消「${song.title}」的归档？",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Text(
                    "会删掉 App 音乐库里的这份副本（${FileNames.size(song.sizeBytes)}）和归档时留下的" +
                        "歌词快照，并释放这部分空间。你的原文件不会被删：取消之后，它会重新出现在" +
                        "曲库列表里。\n" +
                        "注意：如果这首歌的原文件已经不在设备上了，这份副本就是仅存的一份 —— " +
                        "删掉之后不可恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = song
                        pendingUnarchive = null
                        onUnarchiveSong(target)
                    }
                ) { Text("取消归档", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnarchive = null }) { Text("保留") }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.searchActive) {
            SearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> LoadingState()
                !state.permissionGranted -> EmptyState(
                    icon = MelodyIcons.Folder,
                    title = "需要访问本地音乐",
                    description = "授予「音乐和音频」权限后，才能扫描并播放设备里的歌曲。所有数据处理都在本机完成，不会上传。",
                    action = {
                        Button(onClick = onRequestPermission) { Text("授予权限") }
                    },
                    modifier = Modifier.align(Alignment.Center)
                )

                !state.hasLibrary -> if (state.hiddenSongs.isNotEmpty()) EmptyState(
                    icon = MelodyIcons.EyeOff,
                    title = "曲目都被隐藏了",
                    description = "设备里扫到的 ${state.hiddenSongs.size} 首曲目当前处于隐藏状态。" +
                        "点下面的按钮一次性恢复，或者到「设置 → 已隐藏的曲目」逐首挑。",
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onRestoreHidden) { Text("全部恢复") }
                            FilledTonalButton(onClick = onRefresh) { Text("重新扫描") }
                        }
                    },
                    modifier = Modifier.align(Alignment.Center)
                ) else EmptyState(
                    icon = MelodyIcons.MusicNote,
                    title = "没有找到本地音乐",
                    description = "把歌曲文件放进设备后重新扫描；也可以直接用文件选择器导入单个文件。",
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onRefresh) { Text("重新扫描") }
                            FilledTonalButton(onClick = onImportAudio) { Text("从文件导入") }
                        }
                    },
                    modifier = Modifier.align(Alignment.Center)
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 16.dp
                    )
                ) {
                    if (state.query.isNotBlank()) {
                        item(key = "search-header") {
                            Text(
                                text = "找到 ${state.filtered.size} 首与「${state.query}」相关的曲目",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                    }
                    items(items = state.filtered, key = { it.key }) { song ->
                        SongRow(
                            song = song,
                            index = state.filtered.indexOf(song),
                            isCurrent = state.currentSong?.key == song.key,
                            isPlaying = state.isPlaying,
                            onClick = { onSongClick(song) },
                            onPlayNext = { onPlayNext(song) },
                            onHide = { onHideSong(song) },
                            onFetchCover = { onFetchCover(song) },
                            onEditSong = { onEditSong(song) },
                            onArchive = if (song.archived) null else {
                                { onArchiveSong(song) }
                            },
                            onUnarchive = if (song.archived) {
                                { pendingUnarchive = song }
                            } else {
                                null
                            },
                            archived = song.archived,
                            onRemove = if (song.imported) {
                                { onRemoveImported(song) }
                            } else {
                                null
                            }
                        )
                    }
                    item(key = "tail") {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Text(
                                text = buildString {
                                    append(
                                        when {
                                            state.archivedCount > 0 ->
                                                "列表含系统媒体库、手动导入与 App 库（${state.archivedCount} 首）的曲目"
                                            state.songs.any { it.imported } -> "列表包含媒体库扫描与手动导入的曲目"
                                            else -> "全部来自系统媒体库，新增文件后可在右上角重新扫描"
                                        }
                                    )
                                    append("；不想听的点该行右侧 ⋮ →「隐藏这首」")
                                    append("；怕误删的点 ⋮ →「归档到 App 库」")
                                    if (state.archivedCount > 0) {
                                        append("，已归档的那几行可以在 ⋮ 里「取消归档」")
                                    }
                                    if (state.hiddenSongs.isNotEmpty()) {
                                        append("（已隐藏 ${state.hiddenSongs.size} 首，设置页可恢复）")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "正在扫描本地音乐…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MelodyIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索标题、歌手或专辑",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconAction(
                    imageVector = MelodyIcons.Close,
                    contentDescription = "清空搜索",
                    onClick = { onQueryChange("") },
                    iconSize = 16.dp,
                    touchSize = 36.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
