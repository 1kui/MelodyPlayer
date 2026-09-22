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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melody.player.data.AudioLibrary
import com.melody.player.ui.components.CoverCandidatesDialog
import com.melody.player.ui.components.CoverSourceDialog
import com.melody.player.ui.components.LocalArtworkShape
import com.melody.player.ui.components.MelodySnackbarHost
import com.melody.player.ui.components.MiniPlayer
import com.melody.player.ui.components.SongEditDialog
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerViewModel
import com.melody.player.ui.screens.LibraryContent
import com.melody.player.ui.screens.LibraryTopBar
import com.melody.player.ui.screens.PlayerScreen
import com.melody.player.ui.screens.QueueContent
import com.melody.player.ui.screens.QueueTopBar
import com.melody.player.ui.screens.SettingsContent
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

    // 授权一个音乐文件夹：SAF 的目录树，授权跨重启保留，是 KWM 在新系统上最可靠的发现方式
    val kwmFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.scanKwmFolder(uri, treeDisplayName(uri))
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

    BackHandler(enabled = playerOpen) { playerOpen = false }

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
                            onSearchToggle = vm::setSearchActive,
                            onSortChange = vm::setSort,
                            onImportAudio = { audioImportLauncher.launch(arrayOf("audio/*")) },
                            onRefresh = vm::refresh,
                            scrollBehavior = scrollBehavior
                        )

                        MelodyTab.QUEUE -> QueueTopBar(
                            queueSize = state.queue.size,
                            onClear = vm::clearQueue
                        )

                        MelodyTab.SETTINGS -> SettingsTopBar()
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
                                    onClick = { tab = entry },
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
                            onEditSong = vm::beginEditSong
                        )

                        MelodyTab.QUEUE -> QueueContent(
                            state = state,
                            positionMs = position,
                            contentPadding = bottomPadding,
                            onPlayIndex = { index -> vm.playAll(state.queue, index) },
                            onRemoveIndex = vm::removeFromQueue
                        )

                        MelodyTab.SETTINGS -> SettingsContent(
                            state = state,
                            themeMode = themeMode,
                            accent = accent,
                            onThemeModeChange = onThemeModeChange,
                            onAccentChange = onAccentChange,
                            onUnhide = vm::unhideSong,
                            onRestoreAllHidden = vm::clearHiddenSongs,
                            onRefresh = vm::refresh,
                            onImportAudio = { audioImportLauncher.launch(arrayOf("audio/*")) },
                            onArchiveAll = vm::archiveAll,
                            onCancelArchive = vm::cancelArchive,
                            onClearArchive = vm::clearArchive,
                            onUnarchive = { entry -> vm.unarchiveSong(entry) },
                            onAutoFetchLyricsChange = vm::setAutoFetchLyrics,
                            onPreviewLyricCopy = vm::previewLyricCopy,
                            onDismissLyricPreview = vm::dismissLyricPreview,
                            onDeleteLyricCopy = vm::deleteLyricCopy,
                            onDeleteLyricCopyGroup = vm::deleteLyricCopyGroup,
                            onDeleteAllLyricCopies = vm::deleteAllLyricCopies,
                            onKwmScanDevice = vm::scanKwmDevice,
                            onKwmPickFolder = { kwmFolderLauncher.launch(null) },
                            onKwmRescanFolder = vm::rescanKwmFolder,
                            onKwmPickFiles = { kwmFileLauncher.launch(arrayOf("*/*")) },
                            onKwmRemove = vm::removeKwmFile,
                            onKwmClearList = vm::clearKwmList,
                            onKwmDecrypt = vm::decryptKwm,
                            onKwmDecryptAgain = vm::decryptKwmAgain,
                            onKwmDecryptAll = vm::decryptAllKwm,
                            onKwmCancel = vm::cancelKwm,
                            onAutoFetchCoversChange = vm::setAutoFetchCovers,
                            onBackfillCovers = vm::backfillCovers,
                            onCancelBackfillCovers = vm::cancelCoverBackfill,
                            onClearCoverCache = vm::clearCoverCache,
                            onSwipeSwitchSongChange = vm::setSwipeSwitchSong,
                            onArtworkShapeChange = vm::setArtworkShape,
                            onLyricTextSizeChange = vm::setLyricTextSize,
                            onLyricProviderChange = vm::setLyricProviderEnabled,
                            onCoverMinScoreChange = vm::setCoverMinScore,
                            onCoverRegionCustomChange = vm::setCoverRegionCustom,
                            onToggleCoverRegion = vm::toggleCoverRegion,
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
                    onImportLyrics = { lyricImportLauncher.launch(arrayOf("*/*")) },
                    onFetchOnlineLyrics = vm::fetchLyricsOnline,
                    onPickOnlineLyric = vm::applyOnlineCandidate,
                    onDismissOnlinePicker = vm::dismissOnlinePicker,
                    onReloadLyrics = vm::reloadLyrics,
                    onClearImportedLyrics = vm::clearImportedLyrics,
                    onFetchCover = vm::beginCoverPickForCurrent,
                    onEditSongInfo = vm::beginEditSongForCurrent
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

/** 普通页面里，提示条与底部栏之间留的缝（dp）：贴着迷你条会挡住它的播放键。 */
private val SNACKBAR_GAP = 14.dp

/**
 * 播放页里提示条要抬多高（dp）。
 *
 * 播放页底部那一摞是「信息行 + 控制行（72dp 的主键）+ 进度条」，整片约 160dp；
 * 抬 170dp 让它落在进度条以上，谁也不挡 —— 这里不是留白，是那几秒里唯一不闹事的位置。
 */
private val PLAYER_SNACKBAR_LIFT = 170.dp
