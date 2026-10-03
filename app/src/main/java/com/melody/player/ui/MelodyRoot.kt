package com.melody.player.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melody.player.core.FileNames
import com.melody.player.core.LyricCopyEntry
import com.melody.player.core.LyricCopyGroup
import com.melody.player.core.LyricCopyGroups
import com.melody.player.core.PlaybackSpeed
import com.melody.player.core.Playlist
import com.melody.player.core.SleepOption
import com.melody.player.core.Song
import com.melody.player.core.SongQuery
import com.melody.player.data.AudioLibrary
import com.melody.player.ui.components.CoverCandidatesDialog
import com.melody.player.ui.components.CoverSourceDialog
import com.melody.player.ui.components.EmbedTagsDialog
import com.melody.player.ui.components.LocalArtworkShape
import com.melody.player.ui.components.LyricCopiesSheet
import com.melody.player.ui.components.MelodyActionSheet
import com.melody.player.ui.components.MelodySnackbarHost
import com.melody.player.ui.components.MiniPlayer
import com.melody.player.ui.components.PlaylistNameDialog
import com.melody.player.ui.components.PlaylistPickerDialog
import com.melody.player.ui.components.SheetAction
import com.melody.player.ui.components.SongEditDialog
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.player.PlayerViewModel
import com.melody.player.ui.screens.LibraryContent
import com.melody.player.ui.screens.LibraryTopBar
import com.melody.player.ui.screens.PlayerScreen
import com.melody.player.ui.screens.QueueContent
import com.melody.player.ui.screens.QueueTopBar
import com.melody.player.ui.screens.SettingsActions
import com.melody.player.ui.screens.SettingsContent
import com.melody.player.ui.screens.SettingsPage
import com.melody.player.ui.screens.SettingsTopBar
import com.melody.player.ui.theme.AccentTheme
import com.melody.player.ui.theme.ThemeMode
import kotlinx.coroutines.flow.collectLatest

private enum class MelodyTab(val label: String, val icon: ImageVector) {
    // 底栏的"音乐库"用品牌标（与启动图标同一套脉冲竖条），不用通用音符符号
    LIBRARY("音乐库", MelodyIcons.PulseBars),
    QUEUE("播放队列", MelodyIcons.QueueList),
    SETTINGS("设置", MelodyIcons.Settings)
}

/**
 * 从 SAF 目录树的 URI 里取一个能给人看的文件夹名。
 *
 * `getTreeDocumentId` 给的是 `primary:Music/网易云` 这种「卷:路径」形式，取冒号后面那段就是用户选的目录。
 * 选的是存储根目录时冒号后面为空，退回整串 —— 总之必须让用户看得出自己授权的是哪儿，
 * 否则「重扫文件夹」这个按钮点下去心里没底。
 */
private fun treeDisplayName(uri: Uri): String? =
    runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        id.substringAfter(':', id).ifBlank { id }.takeLast(48)
    }.getOrNull()

/**
 * 应用根界面：一个 Scaffold（顶栏按标签切换 + 底部导航）+ 一个全屏播放页浮层。
 *
 * 播放页用浮层而不是导航目的地：音乐 App 的「正在播放」是从迷你条长出来的，
 * 不应该占用底部导航的一个位置。
 */
@Composable
fun MelodyRoot(
    vm: PlayerViewModel,
    themeMode: ThemeMode,
    accent: AccentTheme,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (AccentTheme) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(MelodyTab.LIBRARY) }
    var playerOpen by remember { mutableStateOf(false) }

    /**
     * 设置页现在停在哪一层。
     *
     * **页面栈放在根界面**，不放 `SettingsScreen` 内部：项目没有导航库，返回键全靠
     * `BackHandler` 的注册顺序排队，而顺序只有在**同一个组合作用域**里才有保证。
     * 设置页自己拿一个 local state，那个 BackHandler 就注册在它的子树里，
     * 与播放页、多选两条的关系就说不清了 —— 表现是"在二级页按返回，App 直接退了"。
     */
    var settingsPage by remember { mutableStateOf(SettingsPage.ROOT) }

    /** 睡眠定时 / 播放速度的弹层开关。挂根界面：它从播放页 ⋮ 和设置页两处被叫起来。 */
    var sleepSheetOpen by remember { mutableStateOf(false) }
    var speedSheetOpen by remember { mutableStateOf(false) }
    // 歌单的"新建 / 改名"共用一个命名对话框；删歌单要二次确认，所以单独一个待删对象
    var pendingPlaylistName by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingPlaylistDelete by remember { mutableStateOf<Playlist?>(null) }
    // 清空队列会**立刻停止播放**，而它是顶栏上一颗不带文字的图标按钮 ——
    // 点错的代价是正在听的歌没了、队列也没了。加一道确认。
    var pendingQueueClear by remember { mutableStateOf(false) }
    // 歌词副本的三个待确认动作（删一份 / 删一首的全部 / 删列出的全部）。
    // 状态提在根界面：弹层本身也在这儿，而它的行是会被刷新的 —— 确认框要是挂在
    // 某一行的作用域里，"删掉这一份"跑完那一行就没了，框会跟着消失。
    var pendingDeleteLyric by remember { mutableStateOf<LyricCopyEntry?>(null) }
    var pendingDeleteLyricGroup by remember { mutableStateOf<LyricCopyGroup?>(null) }
    var pendingDeleteLyricShown by remember { mutableStateOf(false) }
    // 「清理未关联副本」的二次确认。它删的是一批**认不回歌曲**的文件，
    // 用户在弹层里看不出来它们到底属于哪首歌 —— 更要先说清删的是什么。
    var pendingDeleteOrphans by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    // 底部栏（迷你条 + 导航栏）实测高度，用来把提示条顶到它上面。
    // 用测量值而不是写死 148.dp：迷你条只在有曲目时出现，写死的数字总有一半场景是错的。
    val density = LocalDensity.current
    var bottomBarPx by remember { mutableStateOf(0) }
    val bottomBarHeight = with(density) { bottomBarPx.toDp() }

    val permissionList = remember {
        buildList {
            add(AudioLibrary.requiredPermission())
            AudioLibrary.notificationPermission()?.let { add(it) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val audioGranted = result[AudioLibrary.requiredPermission()]
            ?: (AudioLibrary.requiredPermission() == Manifest.permission.READ_EXTERNAL_STORAGE)
        vm.onPermissionResult(audioGranted)
    }

    val audioImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.importAudio(uris)
    }

    val lyricImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importLyricsFile(uri)
    }

    // 授权一个音乐文件夹：SAF 的目录树，授权跨重启保留，
    // 既用于 KWM 解密，也用于「曲库只扫描指定文件夹」
    val kwmFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.scanKwmFolder(uri, treeDisplayName(uri))
    }

    // 曲库要限定的那个文件夹。刻意用**另一个** launcher 而不是复用上面的：
    // 复用的话，用户在设置里点「选择文件夹」会顺手把 KWM 的扫描目录也改了，
    // 而这两个文件夹八竿子打不着。
    val libraryFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.pickLibraryFolder(uri, treeDisplayName(uri))
    }

    // 手动多选 .kwm。MIME 用 */* 是因为 .kwm 不在系统已知类型表里，
    // 写 audio/* 反而会让文件在选择器里变灰、根本选不中。
    val kwmFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.addKwmUris(uris)
    }

    // 自定义封面：系统照片选择器（PickVisualMedia 不需要任何存储权限）
    val coverImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.setCustomCover(uri)
    }

    // 首帧就尝试扫描：有权限直接出内容，没权限走引导页
    LaunchedEffect(Unit) {
        if (state.permissionGranted) vm.refresh() else permissionLauncher.launch(permissionList.toTypedArray())
    }

    // 提示条：新消息**顶掉**旧消息，而不是排队等旧的走完一次。
    // 用 collectLatest 而不是 collect —— 后者会让第二条消息卡在 SnackbarHostState 的
    // 互斥量上，头一条不消失、新的出不来，连点几下就变成一串"迟到"的旧提示。
    LaunchedEffect(Unit) {
        vm.messages.collectLatest { message ->
            // 再显式撤一次旧的：取消旧协程与发新消息之间可能差一两帧，
            // 撤掉它能让新提示出现得更干脆
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // 返回键的优先级由**注册顺序**决定：后注册的先命中。
    //
    // 三条的先后是这么定的（先注册 = 优先级最低）：
    //   1. 多选 —— 最低。它只是曲库页上的一层"选择中"状态，任何时候都该最后一个被吃掉；
    //   2. 设置页二级页 —— 中间。它在 Scaffold 内容区里，正常情况盖不住别的层；
    //   3. 播放页 —— 最高。它是**全屏浮层**，压在所有东西之上，二级页也压在下面。
    //      不这么排的话，用户在"设置 → 歌词"里打开了播放页，按返回会先把二级页退掉，
    //      屏幕下面那层悄悄回退，而眼前的播放页还在 —— 像是返回键没反应。
    //
    // 多选这条写在最前面（声明顺序），是有意让位给播放页：否则用户会被迫
    // 先取消多选才能收起播放器，手感像是两层界面打架。
    BackHandler(enabled = state.selectionMode) { vm.clearSelection() }
    BackHandler(enabled = settingsPage != SettingsPage.ROOT) { settingsPage = SettingsPage.ROOT }
    BackHandler(enabled = playerOpen) { playerOpen = false }

    /**
     * 设置页要用的一整包动作。
     *
     * `remember` 是为了让它**别每帧换一个新对象** —— `SettingsActions` 是
     * `@Immutable` 的，同一个实例传下去，二级页的重组可以整片跳过；
     * 每帧新建一个，等于每次都告诉下游"参数变了"。
     *
     * key 里带上 `themeMode` / `accent`：这两项是**值**不是动作，改了就必须换新实例，
     * 否则设置页会一直画着旧的主题。
     */
    val settingsActions = remember(themeMode, accent, onThemeModeChange, onAccentChange) {
        SettingsActions(
            navigate = { settingsPage = it },
            back = { settingsPage = SettingsPage.ROOT },
            themeMode = themeMode,
            accent = accent,
            setThemeMode = onThemeModeChange,
            setAccent = onAccentChange,
            refresh = vm::refresh,
            importAudio = { audioImportLauncher.launch(arrayOf("audio/*")) },
            setLibraryFolderOnly = vm::setLibraryFolderOnly,
            pickLibraryFolder = { libraryFolderLauncher.launch(null) },
            clearLibraryFolder = vm::clearLibraryFolder,
            archiveAll = vm::archiveAll,
            cancelArchive = vm::cancelArchive,
            clearArchive = vm::clearArchive,
            unarchive = { entry -> vm.unarchiveSong(entry) },
            unhide = vm::unhideSong,
            restoreAllHidden = vm::clearHiddenSongs,
            setAutoFetchLyrics = vm::setAutoFetchLyrics,
            setLyricProvider = vm::setLyricProviderEnabled,
            openLyricCopiesAll = vm::requestLyricCopiesAll,
            setSwipeSwitchSong = vm::setSwipeSwitchSong,
            cyclePlayMode = vm::cyclePlayMode,
            openSleepTimer = { sleepSheetOpen = true },
            setArtworkShape = vm::setArtworkShape,
            setLyricTextSize = vm::setLyricTextSize,
            setAutoFetchCovers = vm::setAutoFetchCovers,
            backfillCovers = vm::backfillCovers,
            cancelBackfillCovers = vm::cancelCoverBackfill,
            clearCoverCache = vm::clearCoverCache,
            reparseEmbedded = { vm.reparseEmbeddedArtwork() },
            setCoverMinScore = vm::setCoverMinScore,
            setCoverRegionCustom = vm::setCoverRegionCustom,
            toggleCoverRegion = vm::toggleCoverRegion,
            probeCoverRegions = vm::probeCoverRegions,
            kwmScanDevice = vm::scanKwmDevice,
            kwmPickFolder = { kwmFolderLauncher.launch(null) },
            kwmRescanFolder = vm::rescanKwmFolder,
            kwmPickFiles = { kwmFileLauncher.launch(arrayOf("*/*")) },
            kwmRemove = vm::removeKwmFile,
            kwmClearList = vm::clearKwmList,
            kwmDecrypt = vm::decryptKwm,
            kwmDecryptAgain = vm::decryptKwmAgain,
            kwmDecryptAll = vm::decryptAllKwm,
            kwmCancel = vm::cancelKwm
        )
    }

    // 封面形状是全局外观设置：用 CompositionLocal 往下传，而不是给四处 SongArtwork
    // 调用点各加一个参数 —— 逐个传参等于给每个调用点都留一次"忘了传"的机会
    CompositionLocalProvider(LocalArtworkShape provides state.artworkShape) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    when (tab) {
                        MelodyTab.LIBRARY -> LibraryTopBar(
                            searchActive = state.searchActive,
                            query = state.query,
                            sort = state.sort,
                            totalCount = state.songs.size,
                            onQueryChange = vm::setQuery,
                            // 关掉搜索框也是"我真的搜过一次"的信号：键盘上的搜索键不是
                            // 每个人都会按。记在清空之前 —— 存的是收手那一刻的完整词，
                            // 不会被一路打字存下半成品（空白串由 RecentSearches.push 丢掉）。
                            onSearchToggle = { active ->
                                if (!active) vm.rememberSearch(state.query)
                                vm.setSearchActive(active)
                            },
                            onSortChange = vm::setSort,
                            onImportAudio = { audioImportLauncher.launch(arrayOf("audio/*")) },
                            onRefresh = vm::refresh,
                            scrollBehavior = scrollBehavior,
                            playlist = state.activePlaylist,
                            onCreatePlaylist = { pendingPlaylistName = "" to "新建歌单" },
                            onRenamePlaylist = { target ->
                                pendingPlaylistName = target.name to "重命名歌单"
                            },
                            onDeletePlaylist = { target -> pendingPlaylistDelete = target },
                            onPlayWholePlaylist = {
                                state.activePlaylistId?.let { vm.playPlaylist(it, 0) }
                            },
                            onEmbedAll = vm::requestEmbedVisible,
                            onReparseAllEmbedded = vm::reparseVisibleEmbedded
                        )

                        MelodyTab.QUEUE -> QueueTopBar(
                            queueSize = state.queue.size,
                            onClear = { pendingQueueClear = true }
                        )

                        MelodyTab.SETTINGS -> SettingsTopBar(
                            page = settingsPage,
                            onBack = { settingsPage = SettingsPage.ROOT }
                        )
                    }
                },
                bottomBar = {
                    Column(modifier = Modifier.onSizeChanged { bottomBarPx = it.height }) {
                        if (tab != MelodyTab.SETTINGS || state.currentSong != null) {
                            MiniPlayer(
                                song = state.currentSong,
                                isPlaying = state.isPlaying,
                                positionMs = position,
                                durationMs = state.durationMs,
                                onToggle = vm::togglePlayPause,
                                onNext = vm::next,
                                onPrevious = vm::previous,
                                onExpand = { playerOpen = true }
                            )
                        }
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            MelodyTab.entries.forEach { entry ->
                                NavigationBarItem(
                                    selected = tab == entry,
                                    onClick = {
                                        tab = entry
                                        // 离开设置标签就把二级页收回首屏：下次再进来
                                        // 看到的应该是"常用"，而不是上次翻到的某一页 ——
                                        // 用户不会记得自己上次停在哪，只会觉得"设置页怎么怪怪的"
                                        if (entry != MelodyTab.SETTINGS) {
                                            settingsPage = SettingsPage.ROOT
                                        }
                                        // 多选只对曲库有意义，跑到别的标签还留着它
                                        // 会让用户一进队列页就看到"已选 N 首"的残留状态
                                        if (entry != MelodyTab.LIBRARY) vm.clearSelection()
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = entry.icon,
                                            contentDescription = entry.label,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    label = { Text(entry.label) }
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                ) {
                    val bottomPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
                    when (tab) {
                        MelodyTab.LIBRARY -> LibraryContent(
                            state = state,
                            contentPadding = bottomPadding,
                            onSongClick = vm::playFromLibrary,
                            onPlayNext = vm::playNext,
                            onHideSong = vm::hideSong,
                            onRemoveImported = vm::removeImportedSong,
                            onArchiveSong = vm::archiveSong,
                            // 两个 unarchiveSong 重载（Song / ArchivedEntry），必须用 lambda 指明是哪一个
                            onUnarchiveSong = { song -> vm.unarchiveSong(song) },
                            onQueryChange = vm::setQuery,
                            onRequestPermission = { permissionLauncher.launch(permissionList.toTypedArray()) },
                            onRefresh = vm::refresh,
                            onImportAudio = { audioImportLauncher.launch(arrayOf("audio/*")) },
                            onRestoreHidden = vm::clearHiddenSongs,
                            onFetchCover = vm::beginCoverPick,
                            onEditSong = vm::beginEditSong,
                            onSelectPlaylist = vm::selectPlaylist,
                            onCreatePlaylist = { pendingPlaylistName = "" to "新建歌单" },
                            onAddToPlaylist = vm::beginAddToPlaylist,
                            onRemoveFromPlaylist = { song ->
                                state.activePlaylistId?.let { vm.removeSongFromPlaylist(it, song.key) }
                            },
                            onMovePlaylistSong = { index, delta ->
                                state.activePlaylistId?.let { vm.movePlaylistSong(it, index, delta) }
                            },
                            // 歌单里点歌 = 从这一首开始放整个歌单（顺序就是歌单里的顺序）
                            onPlaylistSongClick = { index ->
                                state.activePlaylistId?.let { vm.playPlaylist(it, index) }
                            },
                            onToggleSelect = vm::toggleSelection,
                            onBeginSelection = vm::beginSelection,
                            // 全选只作用于当前视图看得见的那些：搜索时用户看到的就是这几行
                            onToggleSelectAll = {
                                vm.toggleSelectAll(libraryVisibleSongs(state))
                            },
                            onClearSelection = vm::clearSelection,
                            onBatchAddToPlaylist = vm::beginBatchAddToPlaylist,
                            onBatchRemoveFromPlaylist = vm::batchRemoveFromPlaylist,
                            onBatchRemoveCovers = vm::batchRemoveCovers,
                            onBatchHideSongs = vm::batchHideSongs,
                            // 曲库页只报"要处理哪几首"，勾选框由根界面统一弹（见下面的
                            // EmbedTagsDialog）—— 播放页也要用同一个框，而它跨不过页面
                            onRequestEmbed = vm::requestEmbed,
                            onReparseEmbedded = vm::reparseEmbeddedArtwork,
                            onManageLyricCopies = vm::requestLyricCopies
                        )

                        MelodyTab.QUEUE -> QueueContent(
                            state = state,
                            positionMs = position,
                            contentPadding = bottomPadding,
                            onPlayIndex = { index -> vm.playAll(state.queue, index) },
                            onRemoveIndex = vm::removeFromQueue,
                            onMoveIndex = vm::moveInQueue
                        )

                        MelodyTab.SETTINGS -> SettingsContent(
                            state = state,
                            page = settingsPage,
                            actions = settingsActions,
                            sleepRemaining = vm.sleepRemainingMs,
                            contentPadding = bottomPadding
                        )
                    }
                }
            }

            // 全屏播放页：从底部滑入，覆盖底部导航
            AnimatedVisibility(
                visible = playerOpen,
                enter = slideInVertically(animationSpec = tween(280), initialOffsetY = { it }) +
                    fadeIn(animationSpec = tween(180)),
                exit = slideOutVertically(animationSpec = tween(240), targetOffsetY = { it }) +
                    fadeOut(animationSpec = tween(160))
            ) {
                PlayerScreen(
                    state = state,
                    positionMs = position,
                    sleepRemaining = vm.sleepRemainingMs,
                    onCollapse = { playerOpen = false },
                    onTogglePlay = vm::togglePlayPause,
                    onNext = vm::next,
                    onPrevious = vm::previous,
                    onSeek = vm::seekTo,
                    onCyclePlayMode = vm::cyclePlayMode,
                    onOpenQueue = {
                        playerOpen = false
                        tab = MelodyTab.QUEUE
                    },
                    onNudgeLyricOffset = vm::nudgeLyricOffset,
                    onResetLyricOffset = vm::resetLyricOffset,
                    onOpenSpeed = { speedSheetOpen = true },
                    onOpenSleepTimer = { sleepSheetOpen = true },
                    onImportLyrics = { lyricImportLauncher.launch(arrayOf("*/*")) },
                    onFetchOnlineLyrics = vm::fetchLyricsOnline,
                    onPickOnlineLyric = vm::applyOnlineCandidate,
                    onDismissOnlinePicker = vm::dismissOnlinePicker,
                    onReloadLyrics = vm::reloadLyrics,
                    onClearImportedLyrics = vm::clearImportedLyrics,
                    onFetchCover = vm::beginCoverPickForCurrent,
                    onEditSongInfo = vm::beginEditSongForCurrent,
                    // 播放页也要能重读封面、写标签、加歌单：用户在这儿正对着封面和歌词，
                    // 这些操作"就在手边"，不该逼他收起播放器再去找那一行
                    onReparseCover = { state.currentSong?.let(vm::reparseEmbeddedForSong) },
                    onEmbedTags = vm::requestEmbedForCurrent,
                    onAddToPlaylist = {
                        state.currentSong?.let(vm::beginAddToPlaylist)
                    },
                    onManageLyricCopies = {
                        state.currentSong?.let { vm.requestLyricCopies(setOf(it.key)) }
                    }
                )
            }

            // 睡眠定时 / 播放速度的弹层。
            //
            // 挂在这里而不是播放页里：这两个动作在**播放页 ⋮ 与设置页「常用」两处**
            // 都能触发，而播放页是一层全屏浮层 —— 弹层长在它里面，从设置页那边
            // 就根本叫不起来（用户点完"什么都没发生"）。
            if (sleepSheetOpen) {
                SleepTimerSheet(
                    timerSet = state.sleepDeadlineMs != null,
                    onPick = { option ->
                        sleepSheetOpen = false
                        vm.setSleepTimer(option)
                    },
                    onCancelTimer = {
                        sleepSheetOpen = false
                        vm.cancelSleepTimer()
                    },
                    onDismiss = { sleepSheetOpen = false }
                )
            }

            if (speedSheetOpen) {
                PlaybackSpeedSheet(
                    current = state.playbackSpeed,
                    onPick = { speed ->
                        speedSheetOpen = false
                        vm.setPlaybackSpeed(speed)
                    },
                    onDismiss = { speedSheetOpen = false }
                )
            }

            // 封面自选流程（来源选择 → 候选列表），歌词候选也是同一套交互
            state.coverPickTarget?.let { target ->
                if (state.coverCandidates.isEmpty()) {
                    CoverSourceDialog(
                        songTitle = target.title,
                        hasExisting = state.coverPickHasExisting,
                        searching = state.coverSearching,
                        searchStage = state.coverStage,
                        onSearchOnline = vm::searchCoverCandidates,
                        onPickFromGallery = {
                            coverImageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemove = vm::removeCover,
                        onDismiss = vm::dismissCoverPick
                    )
                } else {
                    CoverCandidatesDialog(
                        songTitle = target.title,
                        candidates = state.coverCandidates,
                        searching = state.coverSearching,
                        onPick = vm::applyCoverCandidate,
                        onPickFromGallery = {
                            coverImageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onDismiss = vm::dismissCoverPick,
                        loadThumb = vm::loadCoverThumb
                    )
                }
            }

            // 编辑歌曲信息（歌名 / 歌手 / 专辑）
            state.editTarget?.let { target ->
                SongEditDialog(
                    song = target,
                    hasOriginal = state.editHasOriginal,
                    onSave = vm::applySongEdit,
                    onReset = vm::resetSongEdit,
                    onDismiss = vm::dismissEditSong
                )
            }

            // 批量「加入歌单」：挑的是一整批，标题要说清是几首
            if (state.batchPlaylistTargetKeys.isNotEmpty()) {
                PlaylistPickerDialog(
                    playlists = state.playlists,
                    header = "已选 ${state.batchPlaylistTargetKeys.size} 首",
                    createTitle = "新建歌单并加入这 ${state.batchPlaylistTargetKeys.size} 首",
                    // 批量时只要"有一部分在里面"就打勾，不逐首算：这一列要给的是
                    // "加进去会多几首"的直觉提示，不是精确账本（精确账本在完成后的汇报里）
                    alreadyIn = { item ->
                        item.songKeys.any { it in state.batchPlaylistTargetKeys }
                    },
                    onPick = { vm.batchAddToPlaylist(it.id) },
                    onCreateAndAdd = { name -> vm.createPlaylistThenBatchAdd(name) },
                    onDismiss = vm::dismissBatchAddToPlaylist
                )
            }

            // 歌单：命名（新建 / 改名）、挑目标（加入歌单）、删歌单二次确认
            pendingPlaylistName?.let { (initial, title) ->
                PlaylistNameDialog(
                    title = title,
                    initialName = initial,
                    confirmLabel = if (initial.isEmpty()) "创建" else "保存",
                    onConfirm = { name ->
                        pendingPlaylistName = null
                        if (initial.isEmpty()) vm.createPlaylist(name)
                        else state.activePlaylistId?.let { vm.renamePlaylist(it, name) }
                    },
                    onDismiss = { pendingPlaylistName = null }
                )
            }

            state.playlistAddTarget?.let { song ->
                PlaylistPickerDialog(
                    song = song,
                    playlists = state.playlists,
                    onPick = { target -> vm.addSongToPlaylist(song, target.id) },
                    onCreateAndAdd = { name ->
                        val id = vm.createPlaylist(name)
                        if (id != null) vm.addSongToPlaylist(song, id)
                    },
                    onDismiss = vm::dismissAddToPlaylist
                )
            }

            pendingPlaylistDelete?.let { target ->
                AlertDialog(
                    onDismissRequest = { pendingPlaylistDelete = null },
                    title = { Text("删除歌单「${target.name}」？") },
                    text = {
                        Text(
                            "歌单里的 ${target.songKeys.size} 首会从歌单中移除。\n" +
                                "歌曲本身不会被删除，曲库、隐藏状态、归档都不受影响 —— " +
                                "随时可以重新建一个歌单再把它们加回去。"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingPlaylistDelete = null
                                vm.deletePlaylist(target.id)
                            }
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingPlaylistDelete = null }) { Text("保留") }
                    }
                )
            }

            if (pendingQueueClear) {
                val count = state.queue.size
                AlertDialog(
                    onDismissRequest = { pendingQueueClear = false },
                    title = { Text("清空播放队列？") },
                    text = {
                        Text(
                            "队列里的 $count 首会全部移出，正在播放的这首会停下来。\n" +
                                "曲库里的歌、歌单、隐藏与归档状态都不受影响 —— " +
                                "想接着听，回音乐库点一首就行。"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingQueueClear = false
                                vm.clearQueue()
                            }
                        ) { Text("清空", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingQueueClear = false }) { Text("取消") }
                    }
                )
            }

            // 「把标签写进音频文件」的勾选框。
            //
            // 它挂在根界面上，而不是曲库页里：触发它的按钮有四个（曲库行菜单、
            // 多选批量条、曲库顶栏、播放页），而播放页是一层全屏浮层 ——
            // 长在曲库页里的对话框会被它整个盖住，用户在播放页点"写进文件"，
            // 会看到什么都没发生。
            //
            // 作用对象就是上面那个 key 集合原样传回来，不在这里重新解析：
            // 用户确认的是"我看见的那几首"。
            state.embedRequestKeys?.let { keys ->
                val single = if (keys.size == 1) {
                    state.songs.firstOrNull { it.key in keys }?.title
                        ?: state.queue.firstOrNull { it.key in keys }?.title
                } else {
                    null
                }
                EmbedTagsDialog(
                    count = keys.size,
                    countLabel = if (single != null) "将修改「$single」的标签。" else null,
                    onConfirm = { writeLyrics, writeArtwork ->
                        vm.embedTagsInto(keys, writeLyrics, writeArtwork)
                    },
                    onDismiss = vm::dismissEmbedRequest
                )
            }

            // 「歌词副本」弹层。挂在根界面而不是曲库页里：它的入口
            // （曲库行菜单、多选批量条、播放页、设置页「歌词」）跨了两层，
            // 而播放页是一层全屏浮层 —— 长在曲库页里的弹层会被它整个盖住。
            //
            // 弹层开着的时候删除副本不会把它关掉（它与被刷新的列表行没有关系），
            // 用户能一份一份接着收拾，这是从设置页那份清单搬过来时最容易丢掉的一点。
            //
            // 两种口径共用一个弹层（见 LyricCopiesSheet.showAll）：按对象时列表由
            // `restrict` 收窄到用户点的那几首；从设置页进来时没有对象可言，列全库。
            val copyRequestKeys = state.lyricCopyRequestKeys
            if (copyRequestKeys != null || state.lyricCopyShowAll) {
                val showAll = state.lyricCopyShowAll
                val shown = if (showAll) {
                    state.lyricCopyGroups
                } else {
                    LyricCopyGroups.restrict(state.lyricCopyGroups, copyRequestKeys.orEmpty())
                }
                LyricCopiesSheet(
                    groups = shown,
                    // 全局口径下"已选 N 首"没意义，传的是**有几首歌**（副标题按 showAll 另写）
                    requestedCount = if (showAll) {
                        shown.count { it.songKey != null }
                    } else {
                        copyRequestKeys?.size ?: 0
                    },
                    showAll = showAll,
                    onPreview = vm::previewLyricCopy,
                    onDelete = { pendingDeleteLyric = it },
                    onDeleteGroup = { pendingDeleteLyricGroup = it },
                    onDeleteAllShown = {
                        if (shown.any { it.entries.isNotEmpty() }) pendingDeleteLyricShown = true
                    },
                    onDeleteOrphans = if (showAll) {
                        { pendingDeleteOrphans = true }
                    } else {
                        null
                    },
                    onDismiss = vm::dismissLyricCopies
                )

                // 删一份 / 删一首的全部 / 删列出的全部 / 清理孤儿 —— 四种量级共用上面那个
                // "待删"状态，放在这里是因为它们都得先说清"删掉之后这首歌会退回什么"，
                // 而那句话与列表里是哪几首无关
                pendingDeleteLyric?.let { entry ->
                    AlertDialog(
                        onDismissRequest = { pendingDeleteLyric = null },
                        title = { Text("删除这份歌词副本？") },
                        text = {
                            Text(
                                "「${entry.label}」的这一份会被删掉。" +
                                    "不会动你的音乐文件，也不会动写进音频文件里的歌词 —— " +
                                    "删掉之后这首歌退回使用文件内嵌歌词或同名 .lrc 文件。"
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingDeleteLyric = null
                                    vm.deleteLyricCopy(entry)
                                }
                            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDeleteLyric = null }) { Text("取消") }
                        }
                    )
                }

                pendingDeleteLyricGroup?.let { group ->
                    AlertDialog(
                        onDismissRequest = { pendingDeleteLyricGroup = null },
                        title = {
                            Text(
                                text = "删除「${group.title}」的全部副本？",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        text = { Text(lyricGroupDeleteText(group)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingDeleteLyricGroup = null
                                    vm.deleteLyricCopyGroup(group)
                                }
                            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDeleteLyricGroup = null }) { Text("取消") }
                        }
                    )
                }

                if (pendingDeleteLyricShown) {
                    val total = shown.sumOf { it.count }
                    AlertDialog(
                        onDismissRequest = { pendingDeleteLyricShown = false },
                        title = { Text("删除这 $total 份歌词副本？") },
                        text = {
                            Text(
                                "上面列出的 ${shown.count { it.entries.isNotEmpty() }} 首歌的 $total 份副本会一起删掉。" +
                                    "不会动你的音乐文件，也不会动已经写进音频文件里的歌词 —— " +
                                    "删掉之后这几首退回使用文件内嵌歌词或同名 .lrc 文件。\n" +
                                    "其中联网自动匹配的那些删掉后，下次播到它们、" +
                                    "且「自动联网获取歌词」开着的话，会重新匹配一次。"
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingDeleteLyricShown = false
                                    vm.deleteLyricCopyGroups(shown)
                                }
                            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDeleteLyricShown = false }) { Text("取消") }
                        }
                    )
                }

                if (pendingDeleteOrphans) {
                    val orphans = LyricCopyGroups.orphans(shown)
                    val count = orphans.sumOf { it.count }
                    AlertDialog(
                        onDismissRequest = { pendingDeleteOrphans = false },
                        title = { Text("清理这 $count 份未关联副本？") },
                        text = {
                            Text(
                                "这些副本的歌词索引已经丢失，认不回是哪首歌，也不会再被播放用到 —— " +
                                    "它们只是占着 App 目录的空间。\n" +
                                    "清理**只删这几份未关联的**：上面那些能认回歌曲的副本一份都不动，" +
                                    "你的音乐文件与写进音频文件里的歌词也不动。"
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingDeleteOrphans = false
                                    vm.deleteOrphanLyricCopies()
                                }
                            ) { Text("清理", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDeleteOrphans = false }) { Text("取消") }
                        }
                    )
                }
            }

            // 副本正文。预览要能盖在弹层上面，所以和弹层同级挂在这里
            state.lyricPreview?.let { preview ->
                AlertDialog(
                    onDismissRequest = vm::dismissLyricPreview,
                    title = {
                        Text(
                            text = preview.label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = preview.text.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = vm::dismissLyricPreview) { Text("关闭") }
                    }
                )
            }

            // 提示条是全局唯一的，但必须画在**最上层**：挂在 Scaffold 的 snackbarHost 上时，
            // 全屏播放页那层浮层会把它整个盖住 —— 用户在播放页点「获取封面」，
            // 提示却压在播放页底下，只有退回主界面才看见，像是提示跑错了页面。
            // 所以从 Scaffold 里挪出来放到根 Box 的最后一位，谁在上面它就在谁上面。
            MelodySnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(
                        if (playerOpen) {
                            // 播放页铺满全屏、底下没有导航栏：在手势条之上再抬过整片播放控件区，
                            // 否则提示条正好压在播放/切歌按钮上，那几秒里点都点不到
                            Modifier
                                .navigationBarsPadding()
                                .padding(bottom = PLAYER_SNACKBAR_LIFT)
                        } else {
                            // 普通页面：迷你条 + 导航栏（实测高度）之上再留一条缝，
                            // 别贴着迷你条的播放键
                            Modifier.padding(bottom = bottomBarHeight + SNACKBAR_GAP)
                        }
                    )
            )
        }
    }
}

/**
 * 睡眠定时的选择弹层。
 *
 * 五挡就够，不给"自定义分钟数"：这个功能的使用场景是躺着准备睡，
 * 不该让人为了填一个数字去调键盘。**当前是否已设定**只改副标题与尾部那一项 ——
 * 已设定时时多一个「取消定时」，否则用户只能靠"重设一个更长的"来抵消，
 * 而列表里没有任何一项叫"不要定时"。
 */
@Composable
private fun SleepTimerSheet(
    timerSet: Boolean,
    onPick: (SleepOption) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    val actions = buildList {
        SleepOption.entries.forEach { option ->
            add(
                SheetAction(
                    icon = MelodyIcons.Moon,
                    title = option.label,
                    subtitle = "从现在起 ${option.label}后自动暂停（队列与进度都留着）",
                    onClick = { onPick(option) }
                )
            )
        }
        if (timerSet) {
            add(
                SheetAction(
                    icon = MelodyIcons.Close,
                    title = "取消定时",
                    subtitle = "取消后不会自动暂停",
                    section = "取消",
                    onClick = onCancelTimer
                )
            )
        }
    }.let { list ->
        // 组标题只打在第一项上（与播放页那份动作清单同一套约定）
        list.mapIndexed { index, action ->
            if (index == 0) action.copy(section = "设定时长") else action
        }
    }

    MelodyActionSheet(
        title = "睡眠定时",
        subtitle = if (timerSet) {
            "已设定 · 到点只暂停，不停止服务"
        } else {
            "到点自动暂停播放"
        },
        actions = actions,
        onDismiss = onDismiss
    )
}

/**
 * 播放速度的选择弹层。
 *
 * 只列**预设**，不给 ±0.05 的微调：0.75× 到 1.5× 之间"听得出区别"的档就这几个，
 * 摆一串碎步只会让人纠结"1.10 和 1.15 到底差在哪"。
 * 当前那一挡靠副标题标出（而不是换掉图标）—— 图标位置一换，整列会在视觉上跳一下。
 */
@Composable
private fun PlaybackSpeedSheet(
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val actions = buildList {
        PlaybackSpeed.PRESETS.forEach { speed ->
            val isCurrent = kotlin.math.abs(speed - current) < 0.001f
            add(
                SheetAction(
                    icon = MelodyIcons.Speed,
                    title = PlaybackSpeed.label(speed),
                    subtitle = when {
                        isCurrent -> "当前速度"
                        PlaybackSpeed.isDefault(speed) -> "原速"
                        else -> null
                    },
                    onClick = { onPick(speed) }
                )
            )
        }
        if (!PlaybackSpeed.isDefault(current)) {
            add(
                SheetAction(
                    icon = MelodyIcons.Refresh,
                    title = "恢复原速",
                    subtitle = "回到 1.00×",
                    section = "其他",
                    onClick = { onPick(1f) }
                )
            )
        }
    }.let { list ->
        list.mapIndexed { index, action ->
            if (index == 0) action.copy(section = "选择倍数") else action
        }
    }

    MelodyActionSheet(
        title = "播放速度",
        // 说清"不落盘"：用户会担心为这一首播客调的速度被静默套到所有音乐上
        subtitle = "变速不变调 · 只对这次会话有效，重开 App 回到原速",
        actions = actions,
        onDismiss = onDismiss
    )
}

/** 普通页面里，提示条与底部栏之间留的缝（dp）：贴着迷你条会挡住它的播放键。 */
private val SNACKBAR_GAP = 14.dp

/**
 * 播放页里提示条要抬多高（dp）。
 *
 * 播放页底部那一摞是「信息行 + 控制行（72dp 的主键）+ 进度条」，整片约 160dp；
 * 抬 170dp 让它落在进度条以上，谁也不挡 —— 这里不是留白，是那几秒里唯一不闹事的位置。
 */
private val PLAYER_SNACKBAR_LIFT = 170.dp

/**
 * 曲库页当前**渲染**的那份列表。
 *
 * 批量「全选」只能用这一份：用户看到的是哪几行，认为自己选的就是哪几首。
 * 歌单视图要按歌单自己的顺序（不能再套一次曲库排序，否则手排的顺序会被打乱），
 * 其余情况就是曲库排序 + 搜索的结果。必须与 `LibraryContent` 内部那份保持一致。
 */
private fun libraryVisibleSongs(state: PlayerUiState): List<Song> =
    if (state.isPlaylistView) {
        if (state.query.isBlank()) state.playlistSongs
        else SongQuery.filter(state.playlistSongs, state.query)
    } else {
        state.filtered
    }

/**
 * 「删除「某某」的全部副本？」那一屏的正文。
 *
 * 单独写出来是为了把**两处**容易写错的边界固定住：
 *  1. 说清删的是"App 里的副本"，不是用户的音频文件 —— 这是最常见的一种误解，
 *     用户看到"删除歌词"第一反应是"我的歌会不会被改"；
 *  2. 自动匹配的那几份删了还会自己回来（下次播到它、联网获取开着的话），
 *     而"选定"的那份不会 —— 不说这句，用户会以为删除没生效。
 */
private fun lyricGroupDeleteText(group: LyricCopyGroup): String = buildString {
    append("这首歌名下的 ${group.count} 份副本（共 ${FileNames.size(group.sizeBytes)}）会一起删掉。")
    append("不会动你的音乐文件，也不会动已经写进音频文件里的歌词 —— ")
    append("删掉之后这首歌退回使用文件内嵌歌词或同名 .lrc 文件。")
    if (group.autoCount > 0) {
        append("其中 ${group.autoCount} 份是联网自动匹配的缓存：下次播到它、")
        append("且「自动联网获取歌词」开着的话，会重新匹配一次。")
    }
}
