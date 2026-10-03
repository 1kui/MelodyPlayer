package com.melody.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.BatchOps
import com.melody.player.core.FileNames
import com.melody.player.core.Playlist
import com.melody.player.core.Song
import com.melody.player.core.SongQuery
import com.melody.player.core.SortMode
import com.melody.player.ui.components.EmptyState
import com.melody.player.ui.components.IconAction
import com.melody.player.ui.components.MelodyActionSheet
import com.melody.player.ui.components.SheetAction
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
 *
 * **正在看某个歌单时**，标题换成歌单名，排序按钮换成歌单操作按钮：
 * 歌单里的顺序是用户手排的，再给一个「按标题 / 按时长」的排序就是把它当没发生过。
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
    modifier: Modifier = Modifier,
    playlist: Playlist? = null,
    onCreatePlaylist: () -> Unit = {},
    onRenamePlaylist: (Playlist) -> Unit = {},
    onDeletePlaylist: (Playlist) -> Unit = {},
    onPlayWholePlaylist: () -> Unit = {},
    /** 对**当前可见的整份列表**写标签（不用先多选）。 */
    onEmbedAll: () -> Unit = {},
    /** 对当前可见的整份列表重新解析内嵌封面。 */
    onReparseAllEmbedded: () -> Unit = {}
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var playlistMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = playlist?.name ?: "音乐库",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        playlist != null -> {
                            val n = playlist.songKeys.size
                            if (n == 0) "空歌单 · 从曲库挑几首加进来" else "$n 首 · 按歌单顺序播放"
                        }

                        totalCount > 0 -> "$totalCount 首本地音乐"
                        else -> "还没有导入音乐"
                    },
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
            if (playlist != null) {
                // 歌单视图：排序按钮让位给歌单操作（播放全部 / 改名 / 删除）
                Box {
                    IconAction(
                        imageVector = MelodyIcons.Playlist,
                        contentDescription = "歌单操作",
                        onClick = { playlistMenuOpen = true },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (playlistMenuOpen) {
                        MelodyActionSheet(
                            title = playlist.name,
                            subtitle = if (playlist.songKeys.isEmpty()) {
                                "空歌单"
                            } else {
                                "${playlist.songKeys.size} 首 · 按歌单顺序播放"
                            },
                            actions = listOf(
                                SheetAction(
                                    icon = MelodyIcons.Play,
                                    title = "播放这个歌单",
                                    subtitle = "从第一首开始，按你排的顺序",
                                    enabled = playlist.songKeys.isNotEmpty(),
                                    section = "歌单操作",
                                    onClick = onPlayWholePlaylist
                                ),
                                SheetAction(
                                    icon = MelodyIcons.Edit,
                                    title = "重命名歌单",
                                    onClick = { onRenamePlaylist(playlist) }
                                ),
                                SheetAction(
                                    icon = MelodyIcons.Delete,
                                    title = "删除歌单",
                                    subtitle = "只删歌单本身，里面的曲目不会被删除",
                                    danger = true,
                                    onClick = { onDeletePlaylist(playlist) }
                                )
                            ),
                            onDismiss = { playlistMenuOpen = false }
                        )
                    }
                }
            } else {
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
            }
            // 这个按钮以前画的是"导入"图标、菜单里却塞着写标签和重读封面 ——
            // 想找"重扫一遍"的人不会去点一个只说自己管导入的按钮。
            // 改成「更多」+ ⋮，并按对象分两组，找东西不用逐个读过去。
            Box {
                IconAction(
                    imageVector = MelodyIcons.MoreVertical,
                    contentDescription = "更多操作",
                    onClick = { moreMenuOpen = true },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (moreMenuOpen) {
                    MelodyActionSheet(
                        title = playlist?.name ?: "音乐库",
                        subtitle = playlist?.let { "对这个歌单" }
                            ?: "$totalCount 首 · 对当前列表",
                        actions = buildList {
                            add(
                                SheetAction(
                                    icon = MelodyIcons.Refresh,
                                    title = "重新扫描媒体库",
                                    subtitle = "设备里新增的歌曲会出现在列表里",
                                    section = "曲库",
                                    onClick = onRefresh
                                )
                            )
                            add(
                                SheetAction(
                                    icon = MelodyIcons.Folder,
                                    title = "从文件选择…",
                                    subtitle = "用文件选择器导入单个或几个文件",
                                    onClick = onImportAudio
                                )
                            )
                            if (playlist == null) {
                                add(
                                    SheetAction(
                                        icon = MelodyIcons.PlaylistAdd,
                                        title = "新建歌单",
                                        onClick = onCreatePlaylist
                                    )
                                )
                            }
                            // 这两项以前只能从多选底部的操作条进去（要先长按 → 多选 → 滑到底），
                            // 属于"存在但找不到"。放在这里等于给它们一个
                            // 不用改变任何选择状态就能到的入口
                            add(
                                SheetAction(
                                    icon = MelodyIcons.Save,
                                    title = "把标签写进音频文件…",
                                    subtitle = "对当前列表里的每一首，写入后无法撤销",
                                    section = "标签与封面",
                                    enabled = totalCount > 0,
                                    onClick = onEmbedAll
                                )
                            )
                            add(
                                SheetAction(
                                    icon = MelodyIcons.Refresh,
                                    title = "重新解析内嵌封面",
                                    subtitle = "重读文件里的封面（在别的播放器换过图时用）",
                                    enabled = totalCount > 0,
                                    onClick = onReparseAllEmbedded
                                )
                            )
                        },
                        onDismiss = { moreMenuOpen = false }
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

/**
 * 歌单切换条：`全部` + 各歌单 + 新建。
 *
 * 横向滚动而不是下拉菜单：歌单是"常回去的地方"，摆在眼前比藏在菜单里好找；
 * 而新建按钮紧挨着放在末尾，省得再去顶栏的菜单里找。
 */
@Composable
private fun PlaylistChips(
    playlists: List<Playlist>,
    activeId: String?,
    totalCount: Int,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaylistChip(
            text = "全部 $totalCount",
            selected = activeId == null,
            onClick = { onSelect(null) }
        )
        playlists.forEach { playlist ->
            PlaylistChip(
                text = "${playlist.name} ${playlist.songKeys.size}",
                selected = playlist.id == activeId,
                onClick = { onSelect(playlist.id) }
            )
        }
        PlaylistChip(text = "新建", selected = false, onClick = onCreate, accent = true)
    }
}

@Composable
private fun PlaylistChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Boolean = false
) {
    Surface(
        shape = CircleShape,
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            accent -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                accent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
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
    modifier: Modifier = Modifier,
    onSelectPlaylist: (String?) -> Unit = {},
    onCreatePlaylist: () -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onRemoveFromPlaylist: (Song) -> Unit = {},
    onMovePlaylistSong: (index: Int, delta: Int) -> Unit = { _, _ -> },
    onPlaylistSongClick: (Int) -> Unit = {},
    onToggleSelect: (Song) -> Unit = {},
    onBeginSelection: (Song) -> Unit = {},
    onToggleSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onBatchAddToPlaylist: () -> Unit = {},
    onBatchRemoveFromPlaylist: () -> Unit = {},
    onBatchRemoveCovers: () -> Unit = {},
    onBatchHideSongs: () -> Unit = {},
    /**
     * 请求对**任意一组**曲目写标签（单曲 / 多选 / 整个可见列表都走这里）。
     *
     * 只把"要处理哪几首"报上去，勾选框与二次确认由根界面统一弹 ——
     * 那个框以前长在这个页面里，可播放页和顶栏也要用它，
     * 于是顶栏只能靠一个"脉冲信号"隔着一层喊它开框，而播放页那层压根够不着。
     * 传 key 集合而不是"用当前选中项"：多选只是入口之一，
     * 让框自己猜作用对象，单曲入口就会写到别人身上。
     */
    onRequestEmbed: (keys: Set<String>) -> Unit = {},
    /** 重新读一遍这些曲目的内嵌封面；传空集合表示整个曲库。 */
    onReparseEmbedded: (keys: Set<String>) -> Unit = {}
) {
    // 取消归档会删掉 App 库里那份副本（原文件已不在时它就是仅存的一份），
    // 所以从行菜单进来也必须先确认一次，不能点一下就没
    var pendingUnarchive by remember { mutableStateOf<Song?>(null) }

    val playlist = state.activePlaylist
    val inPlaylist = playlist != null

    /**
     * 歌单视图下要用**歌单里的下标**，不是当前渲染出来的行号。
     *
     * 一边搜索一边排序时，渲染出来的第 3 行在歌单里可能是第 1 位的歌；
     * 拿行号去上移/下移/移除，改的就是另一首歌 —— 界面上看着"这一行往上动了"，
     * 实际动的是别的东西。
     */
    val playlistIndexOf: (Song) -> Int = { song ->
        playlist?.songKeys?.indexOf(song.key)?.takeIf { it >= 0 } ?: 0
    }

    val shown: List<Song> = if (playlist == null) {
        state.filtered
    } else if (state.query.isBlank()) {
        // 没在搜索时**原样**按歌单顺序渲染：这里绝不能再套一次 SongQuery.sortBy，
        // 用户手排的播放顺序一打开就被打乱的话，这个功能等于没有
        state.playlistSongs
    } else {
        SongQuery.filter(state.playlistSongs, state.query)
    }

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

    // 批量去封面要确认：用户很容易以为这会连音频文件里内嵌的封面一起删掉，
    // 而那件事这个 App 做不到。确认框把边界说清楚，比事后在设置页里发现少了几张图好。
    var pendingBatchCoverRemoval by remember { mutableStateOf(false) }
    if (pendingBatchCoverRemoval) {
        AlertDialog(
            onDismissRequest = { pendingBatchCoverRemoval = false },
            title = { Text("去掉这 ${state.selectedCount} 首的封面？") },
            text = {
                Text(
                    "会删掉 App 里存的这些封面：联网匹配到的、以及你从相册设的自定义封面。\n\n" +
                        "**音频文件里内嵌的封面删不掉、也不会被动** —— 删完之后这几首会退回显示" +
                        "内嵌封面，或者没有封面时的占位图案。音乐文件本身不会变。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingBatchCoverRemoval = false
                        onBatchRemoveCovers()
                    }
                ) { Text("去掉", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchCoverRemoval = false }) { Text("取消") }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        PlaylistChips(
            playlists = state.playlists,
            activeId = state.activePlaylistId,
            totalCount = state.songs.size,
            onSelect = onSelectPlaylist,
            onCreate = onCreatePlaylist
        )

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
                state.loading -> LoadingState(scanLabel = state.scanLabel)

                !state.permissionGranted -> EmptyState(
                    icon = MelodyIcons.Folder,
                    title = "需要访问本地音乐",
                    description = "授予「音乐和音频」权限后，才能扫描并播放设备里的歌曲。所有数据处理都在本机完成，不会上传。",
                    action = {
                        Button(onClick = onRequestPermission) { Text("授予权限") }
                    },
                    modifier = Modifier.align(Alignment.Center)
                )

                // 歌单视图不看 hasLibrary：整库被隐藏光了，但歌单里点名的几首照样要能播
                inPlaylist && shown.isEmpty() && playlist.songKeys.isEmpty() -> EmptyState(
                    icon = MelodyIcons.Playlist,
                    title = "这个歌单还是空的",
                    description = "回到「全部」，在歌曲右侧的 ⋮ 里选「加入歌单」把这几首收进来。" +
                        "歌单里的顺序就是播放顺序，可以用「上移 / 下移」自己排。",
                    action = {
                        Button(onClick = { onSelectPlaylist(null) }) { Text("回到全部") }
                    },
                    modifier = Modifier.align(Alignment.Center)
                )

                inPlaylist && shown.isEmpty() -> EmptyState(
                    icon = MelodyIcons.Search,
                    title = "这个歌单里没有匹配的曲目",
                    description = "「${state.query}」在这个歌单的 ${playlist.songKeys.size} 首里一首都没匹配上。",
                    action = {
                        Button(onClick = { onQueryChange("") }) { Text("清空搜索") }
                    },
                    modifier = Modifier.align(Alignment.Center)
                )

                !inPlaylist && !state.hasLibrary -> if (state.hiddenSongs.isNotEmpty()) EmptyState(
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
                    description = buildString {
                        if (state.libraryFolderOnly) {
                            append("曲库已限定为「${state.libraryFolderName ?: "所选文件夹"}」，")
                            append("这个文件夹里没有找到音频文件。")
                        } else {
                            append("把歌曲文件放进设备后重新扫描；也可以直接用文件选择器导入单个文件。")
                        }
                    },
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
                        top = 4.dp,
                        bottom = contentPadding.calculateBottomPadding() + 16.dp
                    )
                ) {
                    if (state.query.isNotBlank()) {
                        item(key = "search-header") {
                            Text(
                                text = "找到 ${shown.size} 首与「${state.query}」相关的曲目",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                    }
                    items(items = shown, key = { it.key }) { song ->
                        // 序号与所有针对歌单的操作一律用歌单里的下标
                        val rowIndex = if (inPlaylist) playlistIndexOf(song) else shown.indexOf(song)
                        SongRow(
                            song = song,
                            index = rowIndex,
                            isCurrent = state.currentSong?.key == song.key,
                            isPlaying = state.isPlaying,
                            onClick = {
                                if (inPlaylist) onPlaylistSongClick(rowIndex)
                                else onSongClick(song)
                            },
                            onPlayNext = {
                                if (inPlaylist) onPlaylistSongClick(rowIndex)
                                else onPlayNext(song)
                            },
                            onHide = { onHideSong(song) },
                            onFetchCover = { onFetchCover(song) },
                            onEmbedTags = { onRequestEmbed(setOf(song.key)) },
                            onReparseEmbedded = { onReparseEmbedded(setOf(song.key)) },
                            onEditSong = { onEditSong(song) },
                            onArchive = if (song.archived) null else {
                                { onArchiveSong(song) }
                            },
                            onUnarchive = if (song.archived) {
                                { pendingUnarchive = song }
                            } else {
                                null
                            },
                            // 「加入歌单」只在整库里出现：已经在歌单里还问"要不要加"，是句废话
                            onAddToPlaylist = if (inPlaylist) null else {
                                { onAddToPlaylist(song) }
                            },
                            onMoveUp = if (inPlaylist && rowIndex > 0) {
                                { onMovePlaylistSong(rowIndex, -1) }
                            } else {
                                null
                            },
                            onMoveDown = if (inPlaylist && rowIndex < playlist.songKeys.lastIndex) {
                                { onMovePlaylistSong(rowIndex, 1) }
                            } else {
                                null
                            },
                            onRemoveFromPlaylist = if (inPlaylist) {
                                { onRemoveFromPlaylist(song) }
                            } else {
                                null
                            },
                            archived = song.archived,
                            onRemove = if (song.imported) {
                                { onRemoveImported(song) }
                            } else {
                                null
                            },
                            selected = if (state.selectionMode) song.key in state.selection else null,
                            onToggleSelect = { onToggleSelect(song) },
                            onBeginSelection = { onBeginSelection(song) }
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
                                    if (inPlaylist) {
                                        append("按歌单顺序播放；点 ⋮ 可以上移、下移、从歌单移除")
                                        append("；「从歌单移除」只动歌单，曲库里那首还在")
                                        if (playlist != null && state.songs.isNotEmpty() &&
                                            state.playlistSongs.size < playlist.songKeys.size
                                        ) {
                                            append("（有 ${playlist.songKeys.size - state.playlistSongs.size} 首已不在曲库）")
                                        }
                                    } else {
                                        append(
                                            when {
                                                state.archivedCount > 0 ->
                                                    "列表含系统媒体库、手动导入与 App 库（${state.archivedCount} 首）的曲目"
                                                state.songs.any { it.imported } -> "列表包含媒体库扫描与手动导入的曲目"
                                                else -> "全部来自系统媒体库，新增文件后可在右上角重新扫描"
                                            }
                                        )
                                        append("；长按任意一行可进入多选，批量加入歌单/去封面/隐藏")
                                        append("；点该行右侧 ⋮ 会从底部展开这一首的全部操作")
                                        append("（封面、写入文件标签、编辑信息、归档、隐藏），每一项都带一句说明")
                                        append("；对整份列表写标签或重读内嵌封面，在右上角 ⋮ 里")
                                        if (state.archivedCount > 0) {
                                            append("，已归档的那几行可以在 ⋮ 里「取消归档」")
                                        }
                                        if (state.hiddenSongs.isNotEmpty()) {
                                            append("（已隐藏 ${state.hiddenSongs.size} 首，设置页可恢复）")
                                        }
                                    }
                                    if (state.libraryFolderOnly) {
                                        append("；曲库只扫「${state.libraryFolderName ?: "所选文件夹"}」，设置页可改")
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

        // 批量操作条压在列表底部。多选时才出现 —— 它是"对选中的那几首做什么"的入口，
        // 平时占着位置只会让列表看起来永远有东西挡着。
        if (state.selectionMode) {
            BatchActionBar(
                count = state.selectedCount,
                selectAllClears = BatchOps.selectAllTogglesOff(
                    state.selectedCount,
                    shown.size
                ),
                inPlaylist = inPlaylist,
                playlistName = playlist?.name,
                onSelectAll = onToggleSelectAll,
                onDone = onClearSelection,
                onAddToPlaylist = onBatchAddToPlaylist,
                onRemoveFromPlaylist = onBatchRemoveFromPlaylist,
                onRemoveCovers = { pendingBatchCoverRemoval = true },
                onHide = onBatchHideSongs,
                onEmbed = { onRequestEmbed(state.selection) },
                // 和「写进文件」一样**不动选择**：这两个都是"对选中的这几首做一件事"，
                // 做完一件就自动退出多选，会让想接着做第二件的用户重新选一遍
                onReparse = { onReparseEmbedded(state.selection) },
                modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding())
            )
        }
    }
}

/**
 * 批量操作条。
 *
 * 横向可滚而不是排成两行：这一条上的按钮都带文字，竖着排会把列表压掉一大半高度。
 * 用 Chip 而不是 IconButton 是因为"去掉封面"和"隐藏"这两个动作，光看图标分不清
 * 会发生什么，而误触的代价（几十首一起消失）是这一版最不该出的错。
 */
@Composable
private fun BatchActionBar(
    count: Int,
    selectAllClears: Boolean,
    inPlaylist: Boolean,
    playlistName: String?,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onRemoveCovers: () -> Unit,
    onHide: () -> Unit,
    onEmbed: () -> Unit,
    onReparse: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已选 $count 首",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                // 已全选（或本来就没几首）时这个按钮改叫「取消全选」：同一个按钮两种名字，
                // 用户不必先点一次、再猜"再点一下是不是就全取消了"
                TextButton(onClick = onSelectAll) {
                    Text(if (selectAllClears) "取消全选" else "全选")
                }
                TextButton(onClick = onDone) { Text("完成") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 歌单视图里"加入歌单"没意义（它们已经在这个歌单里了），
                // 换成一个针对当前歌单的动作
                if (inPlaylist) {
                    BatchChip(
                        text = "从这个歌单移除",
                        icon = MelodyIcons.Delete,
                        onClick = onRemoveFromPlaylist
                    )
                } else {
                    BatchChip(
                        text = "加入歌单",
                        icon = MelodyIcons.PlaylistAdd,
                        onClick = onAddToPlaylist
                    )
                }
                BatchChip(
                    text = "去掉封面",
                    icon = MelodyIcons.ImageOff,
                    onClick = onRemoveCovers
                )
                BatchChip(
                    text = "隐藏",
                    icon = MelodyIcons.EyeOff,
                    onClick = onHide
                )
                // 「写进文件」与上面几个性质不同：那几个只改 App 的数据，
                // 这个会**改动用户的音频文件**，所以入口摆在最后、且要点确认框。
                // 「重读内嵌封面」紧挨着它：这两个是一对 —— 一个往文件里写，
                // 一个从文件里读，放一起才看得出这是同一件事的两头
                BatchChip(
                    text = "写进文件",
                    icon = MelodyIcons.Save,
                    onClick = onEmbed
                )
                BatchChip(
                    text = "重读内嵌封面",
                    icon = MelodyIcons.Refresh,
                    onClick = onReparse
                )
            }
            if (inPlaylist && playlistName != null) {
                Text(
                    text = "「从这个歌单移除」只动「$playlistName」，曲库里的歌还在",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 0.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BatchChip(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    )
}

@Composable
private fun LoadingState(scanLabel: String = "") {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = scanLabel.ifBlank { "正在扫描本地音乐…" },
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
