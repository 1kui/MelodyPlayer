package com.melody.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melody.player.core.LyricOffset
import com.melody.player.core.LyricTextSize
import com.melody.player.core.Lyrics
import com.melody.player.core.PlayMode
import com.melody.player.core.PlaybackSpeed
import com.melody.player.core.SleepTimer
import com.melody.player.core.Song
import com.melody.player.core.TimeFormat
import com.melody.player.core.online.OnlineSong
import com.melody.player.ui.components.IconAction
import com.melody.player.ui.components.MelodyActionSheet
import com.melody.player.ui.components.MelodySeekBar
import com.melody.player.ui.components.SheetAction
import com.melody.player.ui.components.PillSwitcher
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.settings.playModeIcon
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 播放页的两个视图。 */
private enum class PlayerPage { ARTWORK, LYRICS }

@Composable
fun PlayerScreen(
    state: PlayerUiState,
    positionMs: Long,
    /**
     * 睡眠定时的剩余毫秒（每秒刷新一次）。
     *
     * 刻意传**流**而不是一个 `Long?` 值：这一页每秒都要重画那个倒计时小标，
     * 而值是走 `PlayerUiState` 的话，整页（封面、歌词列表、进度条）会跟着每秒重组一次。
     * 倒计时读数的重组范围收在一个几十 dp 的小标里，就只剩那一个节点在动。
     */
    sleepRemaining: StateFlow<Long?>,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    /** 歌词偏移挪一挡（+1 延后 / −1 提前）。 */
    onNudgeLyricOffset: (Int) -> Unit,
    /** 歌词偏移清回 0。 */
    onResetLyricOffset: () -> Unit,
    /** 打开播放速度弹层（挂在根界面）。 */
    onOpenSpeed: () -> Unit,
    /** 打开睡眠定时弹层（挂在根界面）。 */
    onOpenSleepTimer: () -> Unit,
    onImportLyrics: () -> Unit,
    onFetchOnlineLyrics: () -> Unit,
    onPickOnlineLyric: (OnlineSong) -> Unit,
    onDismissOnlinePicker: () -> Unit,
    onReloadLyrics: () -> Unit,
    onClearImportedLyrics: () -> Unit,
    /** 重新联网匹配当前这首歌的封面（跟着这首歌走，不是"歌词"那一摊，但放在同一个菜单里）。 */
    onFetchCover: () -> Unit,
    /** 改当前这首歌的歌名/歌手/专辑。 */
    onEditSongInfo: () -> Unit,
    /**
     * 重新读一遍这个文件里内嵌的封面。
     *
     * 放在播放页是有道理的：用户在这儿正对着封面看，也刚在别的播放器里换过图，
     * 心里想的就是"把这张图重新读一遍"。以前它只藏在曲库行的 ⋮ 里，
     * 从播放页出发得先收起播放器、再找到那一行。
     */
    onReparseCover: () -> Unit,
    /** 把当前这首的歌词 / 封面写进音频文件（弹勾选框确认）。 */
    onEmbedTags: () -> Unit,
    /** 把当前这首加进自建歌单。 */
    onAddToPlaylist: () -> Unit,
    /** 看一眼当前这首在 App 里存了哪几份歌词副本（可预览、可删）。 */
    onManageLyricCopies: () -> Unit,
    modifier: Modifier = Modifier
) {
    val song = state.currentSong
    var menuOpen by remember { mutableStateOf(false) }

    if (state.onlineCandidates.isNotEmpty()) {
        OnlineLyricPickerDialog(
            songTitle = song?.title.orEmpty(),
            candidates = state.onlineCandidates,
            onPick = onPickOnlineLyric,
            onDismiss = onDismissOnlinePicker
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // ---------------------------------------------------------- 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(
                    imageVector = MelodyIcons.ChevronDown,
                    contentDescription = "收起播放页",
                    onClick = onCollapse,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 顶栏只留歌名：原来上面那行「正在播放」说的每件事别处都已经有了
                    // （播放键的图标、进度条在不在走、迷你条），占的却是最显眼的居中位。
                    Text(
                        text = song?.title ?: "未选择曲目",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconAction(
                        imageVector = MelodyIcons.MoreVertical,
                        contentDescription = "更多操作",
                        enabled = song != null,
                        onClick = { menuOpen = true },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (menuOpen && song != null) {
                        MelodyActionSheet(
                            title = song.title,
                            subtitle = "${song.artistOrUnknown} · ${song.albumOrUnknown}",
                            headerArt = { SongArtwork(song = song, size = 46.dp, corner = 12.dp) },
                            actions = playerActions(
                                lyricsImported = state.lyricsImported,
                                onlineSearching = state.onlineSearching,
                                playbackSpeed = state.playbackSpeed,
                                sleepSet = state.sleepDeadlineMs != null,
                                onFetchCover = onFetchCover,
                                onReparseCover = onReparseCover,
                                onEmbedTags = onEmbedTags,
                                onEditSongInfo = onEditSongInfo,
                                onAddToPlaylist = onAddToPlaylist,
                                onFetchOnlineLyrics = onFetchOnlineLyrics,
                                onImportLyrics = onImportLyrics,
                                onReloadLyrics = onReloadLyrics,
                                onClearImportedLyrics = onClearImportedLyrics,
                                onManageLyricCopies = onManageLyricCopies,
                                onOpenSpeed = onOpenSpeed,
                                onOpenSleepTimer = onOpenSleepTimer
                            ),
                            onDismiss = { menuOpen = false }
                        )
                    }
                }
            }

            // ---------------------------------------------------------- 视图切换
            // 封面/歌词只通过顶部胶囊切换，切换本身带滑动 + 淡入淡出过渡。
            // 故意不做左右滑动手势：它和封面页的上下滑切歌在手势竞技场里互相抢，
            // 斜着滑经常被误判成翻页；只留胶囊切页，方向判定就再也不会错。
            var page by remember { mutableStateOf(PlayerPage.ARTWORK) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                PillSwitcher(
                    options = listOf(PlayerPage.ARTWORK, PlayerPage.LYRICS),
                    selected = page,
                    labelOf = { if (it == PlayerPage.ARTWORK) "封面" else "歌词" },
                    iconOf = { if (it == PlayerPage.ARTWORK) MelodyIcons.AlbumArt else MelodyIcons.Lyrics },
                    onSelect = { page = it }
                )
            }

            // ---------------------------------------------------------- 主体
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    // 往右翻（封面→歌词）新页从右侧进，往左翻则从左侧进，方向感与胶囊位置一致
                    val forward = targetState.ordinal > initialState.ordinal
                    (
                        slideInHorizontally { full -> if (forward) full / 3 else -full / 3 } +
                            fadeIn(tween(220))
                    ) togetherWith (
                        slideOutHorizontally { full -> if (forward) -full / 3 else full / 3 } +
                            fadeOut(tween(160))
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                label = "player-page"
            ) { current ->
                when (current) {
                    PlayerPage.ARTWORK -> ArtworkPage(
                        song = song,
                        swipeSwitchEnabled = state.swipeSwitchSong,
                        onNext = onNext,
                        onPrevious = onPrevious
                    )
                    // 歌词来源不显示了：整块 weight(1f) 内容区只剩歌词本体，
                    // 也就没有任何"跨页不同高"的东西再去挤播放控件。
                    PlayerPage.LYRICS -> LyricsPage(
                        state = state,
                        positionMs = positionMs,
                        textSize = state.lyricTextSize,
                        onSeek = onSeek,
                        onNudgeOffset = onNudgeLyricOffset,
                        onResetOffset = onResetLyricOffset,
                        onImportLyrics = onImportLyrics,
                        onFetchOnlineLyrics = onFetchOnlineLyrics
                    )
                }
            }

            // ---------------------------------------------------------- 标题 / 歌手
            if (page == PlayerPage.ARTWORK) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = song?.title ?: "未选择曲目",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = song?.let { "${it.artistOrUnknown} · ${it.albumOrUnknown}" } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ---------------------------------------------------------- 进度
            MelodySeekBar(
                positionMs = positionMs,
                durationMs = state.durationMs,
                onSeek = onSeek,
                enabled = song != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )

            // ---------------------------------------------------------- 控制区
            TransportControls(
                isPlaying = state.isPlaying,
                buffering = state.buffering,
                playMode = state.playMode,
                enabled = song != null,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onPrevious = onPrevious,
                onCyclePlayMode = onCyclePlayMode,
                onOpenQueue = onOpenQueue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp)
            )

            // ---------------------------------------------------------- 底部信息
            // 这一行**两个页面必须长得一样**：它是整列的最后一个子项，行高一变，
            // 上面的进度条和播放键就跟着被顶上去。歌词来源那颗胶囊以前挂在这儿，
            // 它比同一行的「3 / 12」高一截（labelSmall + 4dp 竖内边距），于是
            // 封面↔歌词来回切时整片播放控件会上下跳 —— 所以它搬去了歌词区顶部。
            // 字号调节已收进设置页，播放页不再放 A− / A＋。
            //
            // 左边的两个小标（变速 / 睡眠倒计时）是 v2.19 加的。它们**两页都画**，
            // 且都做成一行的最小高度（labelSmall，无竖向 padding）—— 只在歌词页多一行、
            // 或者比旁边的「3 / 12」高一点点，都会让上面那摞控件跟着动。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpeedBadge(state.playbackSpeed)
                SleepCountdownBadge(sleepRemaining = sleepRemaining)
                Spacer(Modifier.weight(1f))
                if (state.queue.isNotEmpty()) {
                    Text(
                        text = "${(state.currentIndex + 1).coerceAtLeast(1)} / ${state.queue.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 播放页的动作清单。
 *
 * 分成「播放 / 封面 / 歌曲 / 歌词」四组 —— 播放页顶栏那个 ⋮ 以前的内容描述写的是
 * "更多歌词操作"，可里面却混着换封面、改歌曲信息：找封面的人不会去点一个
 * 说自己只管歌词的按钮。现在按对象分组，名字也改成「更多操作」。
 *
 * 「播放」那一组（变速 / 睡眠定时）放在最前面：它是这一页**每天都要按一下**的东西，
 * 而下面三组是"这首不对劲时才会来"的。顺序按使用频次排，不按对象敏感度排。
 *
 * 「重新解析内嵌封面」以前只在曲库行里；用户在这一页正对着封面看，
 * 想重读的就是它，所以这儿必须有。
 */
private fun playerActions(
    lyricsImported: Boolean,
    onlineSearching: Boolean,
    playbackSpeed: Float,
    sleepSet: Boolean,
    onFetchCover: () -> Unit,
    onReparseCover: () -> Unit,
    onEmbedTags: () -> Unit,
    onEditSongInfo: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFetchOnlineLyrics: () -> Unit,
    onImportLyrics: () -> Unit,
    onReloadLyrics: () -> Unit,
    onClearImportedLyrics: () -> Unit,
    onManageLyricCopies: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit
): List<SheetAction> {
    val playback = listOf(
        SheetAction(
            MelodyIcons.Speed,
            "播放速度",
            // 只写"播放速度"用户不知道现在是多少 —— 非原速时把倍数写出来，
            // 顺手把"这东西现在是生效的"也说明了
            subtitle = if (PlaybackSpeed.isDefault(playbackSpeed)) {
                "当前原速；0.75×～1.5× 变速不变调"
            } else {
                "当前 ${PlaybackSpeed.label(playbackSpeed)}，点这里改回去或再调"
            },
            onClick = onOpenSpeed
        ),
        SheetAction(
            MelodyIcons.Moon,
            "睡眠定时",
            subtitle = if (sleepSet) {
                "已设定，到点自动暂停（可在这里改时长或取消）"
            } else {
                "15 分钟～1.5 小时，到点自动暂停并保留队列"
            },
            onClick = onOpenSleepTimer
        )
    ).startSection("播放")

    val cover = listOf(
        SheetAction(
            MelodyIcons.AlbumArt,
            "选择专辑封面",
            subtitle = "联网匹配一张，或从相册里挑",
            onClick = onFetchCover
        ),
        SheetAction(
            MelodyIcons.Refresh,
            "重新解析内嵌封面",
            subtitle = "重读文件里的封面（在别的播放器换过图时用）",
            onClick = onReparseCover
        ),
        SheetAction(
            MelodyIcons.Save,
            "写入文件标签…",
            subtitle = "把这份歌词与封面写进音频文件，写入后无法撤销",
            onClick = onEmbedTags
        )
    ).startSection("封面")

    val songInfo = listOf(
        SheetAction(
            MelodyIcons.Edit,
            "编辑歌曲信息",
            subtitle = "只改 App 里的显示，不动文件",
            onClick = onEditSongInfo
        ),
        SheetAction(MelodyIcons.PlaylistAdd, "加入歌单", onClick = onAddToPlaylist)
    ).startSection("歌曲")

    val lyrics = buildList {
        add(
            SheetAction(
                MelodyIcons.CloudDownload,
                "联网获取歌词",
                subtitle = if (onlineSearching) "正在搜索…" else "从网易云 / LRCLIB 里找一份",
                enabled = !onlineSearching,
                onClick = onFetchOnlineLyrics
            )
        )
        add(
            SheetAction(
                MelodyIcons.Folder,
                "导入 .lrc 歌词文件",
                subtitle = "用手边已有的歌词文件覆盖",
                onClick = onImportLyrics
            )
        )
        add(
            SheetAction(
                MelodyIcons.Refresh,
                "重新解析内嵌歌词",
                subtitle = "重读文件里内嵌的那份歌词",
                onClick = onReloadLyrics
            )
        )
        // 就放在歌词这几项中间：此刻用户正对着歌词，想收拾的就是它
        add(
            SheetAction(
                MelodyIcons.Lyrics,
                "歌词副本…",
                subtitle = "看看 App 里为这首存了哪几份歌词，可预览、可删",
                onClick = onManageLyricCopies
            )
        )
        if (lyricsImported) {
            // 标红而不是放在最后：清掉之后就退回内嵌歌词或同名 .lrc，
            // 是"做了才知道原来那份多难找"的操作，值得一句边界说明和一个警示色
            add(
                SheetAction(
                    MelodyIcons.Delete,
                    "清除选定的歌词",
                    subtitle = "退回使用文件内嵌歌词或同名 .lrc 文件",
                    danger = true,
                    onClick = onClearImportedLyrics
                )
            )
        }
    }.startSection("歌词")

    return playback + cover + songInfo + lyrics
}

/** 把组标题打在这一组的第一项上。 */
private fun List<SheetAction>.startSection(title: String): List<SheetAction> =
    mapIndexed { i, action -> if (i == 0) action.copy(section = title) else action }

/**
 * 封面页。上下滑动切歌（可在设置里关），带完整的滑动动画：
 *
 *  - 拖动时封面**跟着手指走**（位移 + 渐隐），不是闷声切完歌才反应；
 *  - 累计位移过阈值：旧封面沿手势方向滑出 → 切歌 → 新封面从对侧滑入；
 *  - 没过阈值松手：封面弹回原位；
 *  - 一次手势最多切一首（fired 锁存到滑入动画结束），抖两下不会连跳；
 *  - 页面上没有其他方向的手势竞争（左右翻页已改为胶囊切换），
 *    斜着滑也会被稳定判成上下滑；歌词页不挂这个手势，滚动权留给歌词列表。
 */
@Composable
private fun ArtworkPage(
    song: Song?,
    swipeSwitchEnabled: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { SWIPE_SWITCH_THRESHOLD.toPx() }
    val slidePx = with(density) { SLIDE_OUT_DISTANCE.toPx() }
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    var fired by remember { mutableStateOf(false) }

    // fillMaxSize 是关键：容器要撑满整个页面区域，内容才谈得上"居中"，
    // 否则 Box 收缩到内容大小、封面永远贴在左上角
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(swipeSwitchEnabled) {
                if (!swipeSwitchEnabled) return@pointerInput
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (fired) return@detectVerticalDragGestures
                        scope.launch {
                            dragOffset.snapTo((dragOffset.value + dragAmount).coerceIn(-slidePx, slidePx))
                            if (abs(dragOffset.value) >= thresholdPx) {
                                fired = true
                                val downward = dragOffset.value > 0f
                                // 下滑 = 上一首（内容向下出、从上进）；上滑 = 下一首，方向对称
                                dragOffset.animateTo(if (downward) slidePx else -slidePx, tween(160))
                                if (downward) onPrevious() else onNext()
                                dragOffset.snapTo(if (downward) -slidePx else slidePx)
                                dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                fired = false
                            }
                        }
                    },
                    onDragEnd = {
                        if (!fired) {
                            scope.launch { dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                        }
                    },
                    onDragCancel = {
                        if (!fired) {
                            scope.launch { dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val side = minOf(maxWidth * 0.74f, 340.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                translationY = dragOffset.value
                alpha = (1f - abs(dragOffset.value) / slidePx).coerceIn(0f, 1f)
            }
        ) {
            // 这里原来还有一行「正在播放 / 已暂停」。删掉它是因为它说的每个字都在
            // 别处已经有了：播放键的图标、进度条在不在走、迷你条。而它占着封面正下方
            // 最显眼的位置，把封面挤得不上不下。
            SongArtwork(song = song, size = side, corner = 26.dp)
        }
    }
}

@Composable
private fun LyricsPage(
    state: PlayerUiState,
    positionMs: Long,
    textSize: LyricTextSize,
    onSeek: (Long) -> Unit,
    onNudgeOffset: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onImportLyrics: () -> Unit,
    onFetchOnlineLyrics: () -> Unit
) {
    val lyrics = state.lyrics
    // 高亮用的是**校正后**的位置：偏移只在这一步参与计算，不进 Lyrics 模型、
    // 不重建时间轴，所以边听边调是立刻生效的（见 LyricOffset.apply）
    val queryMs = LyricOffset.apply(positionMs, state.lyricOffsetMs)
    val currentIndex = lyrics.indexAt(queryMs)
    val listState = rememberLazyListState()

    // 字号按档位缩放。基准就是「标准」那一挡（旧版本一直用的 16sp），
    // 行高和行距一起缩放 —— 只放大字号、行高不动，大号歌词会挤成一坨
    val fontSp = LYRIC_BASE_SP * textSize.scale
    val lineSp = LYRIC_LINE_SP * textSize.scale
    val rowPad = LYRIC_ROW_PAD_DP * textSize.scale

    // 当前行变化时把歌词滚到视口约 1/3 处 —— 既看得清当前句，也能预读下一句
    LaunchedEffect(currentIndex, lyrics) {
        if (currentIndex >= 0 && lyrics.items.isNotEmpty()) {
            val viewport = listState.layoutInfo.viewportSize.height
            val offset = if (viewport > 0) -(viewport * 0.34f).toInt() else 0
            runCatching { listState.animateScrollToItem(currentIndex, offset) }
        }
    }

    // 小条只在**真的能调**的时候出现（有歌词、且没在忙）：对着一屏"这首歌没有歌词"
    // 还能挪偏移，只会让人以为挪了会重新匹配一份歌词。
    // 它固定在内容区最上面、不做成列表的第一项 —— 做成列表项会被滚走，
    // 而"歌词不对齐"这件事是边听边调，调完还想再调一下。
    val showOffsetBar = lyrics.isUsable && !state.lyricsLoading && !state.onlineSearching

    Column(modifier = Modifier.fillMaxSize()) {
        if (showOffsetBar) {
            LyricOffsetBar(
                offsetMs = state.lyricOffsetMs,
                onNudge = onNudgeOffset,
                onReset = onResetOffset
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LyricsBody(
                state = state,
                lyrics = lyrics,
                currentIndex = currentIndex,
                listState = listState,
                fontSp = fontSp,
                lineSp = lineSp,
                rowPad = rowPad,
                offsetMs = state.lyricOffsetMs,
                onSeek = onSeek,
                onImportLyrics = onImportLyrics,
                onFetchOnlineLyrics = onFetchOnlineLyrics
            )
        }
    }
}

/** 歌词区的四种形态（解析中 / 联网中 / 没有歌词 / 正常列表）。 */
@Composable
private fun LyricsBody(
    state: PlayerUiState,
    lyrics: Lyrics,
    currentIndex: Int,
    listState: LazyListState,
    fontSp: Float,
    lineSp: Float,
    rowPad: Float,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    onImportLyrics: () -> Unit,
    onFetchOnlineLyrics: () -> Unit
) {
    when {
        state.lyricsLoading -> LyricsBusy("正在解析歌词…")

        state.onlineSearching -> LyricsBusy("正在联网搜索歌词…")

        !lyrics.isUsable -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(96.dp)
                ) {}
                Icon(
                    imageVector = MelodyIcons.Lyrics,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "这首歌没有歌词",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已尝试读取文件内嵌歌词、同名 .lrc 与在线匹配。\n也可以手动联网获取或导入一份 .lrc。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            LyricsActionButton(
                icon = MelodyIcons.CloudDownload,
                text = "联网获取歌词",
                filled = true,
                enabled = state.currentSong != null,
                onClick = onFetchOnlineLyrics
            )
            Spacer(Modifier.height(10.dp))
            LyricsActionButton(
                icon = MelodyIcons.Folder,
                text = "导入歌词文件",
                filled = false,
                enabled = true,
                onClick = onImportLyrics
            )
        }

        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "lyrics-top") { Spacer(Modifier.height(40.dp)) }
            itemsIndexed(lyrics.items) { index, line ->
                val active = index == currentIndex
                val scale by animateFloatAsState(
                    targetValue = if (active) 1f else 0.95f,
                    animationSpec = tween(240),
                    label = "lyric-scale"
                )
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = (if (active) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    }).copy(fontSize = fontSp.sp, lineHeight = lineSp.sp),
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .scale(scale)
                        // 点一行跳过去时也要**带上偏移**：屏幕上那一行是在
                        // 「本来时刻 + 偏移」才出现的，跳的是位置本身、不是那一行 ——
                        // 少加这一下会先跳对、再被偏移拉走半秒，看着像"点了没对准"
                        .clickable { onSeek((line.timeMs + offsetMs).coerceAtLeast(0L)) }
                        .padding(vertical = rowPad.dp, horizontal = 4.dp)
                )
            }
            item(key = "lyrics-bottom") { Spacer(Modifier.height(140.dp)) }
        }
    }
}

/**
 * 歌词时间轴偏移小条：`歌词偏移   −   未校正   ＋`。
 *
 * ## 为什么在这里、而不是 ⋮ 里
 * 偏移是**一边听一边调**的东西：听到"这句早了半秒"，手指伸过去按两下就对了。
 * 塞进 ⋮ 弹层意味着每调一挡都要开一次菜单、关一次菜单，而弹层还盖着歌词区 ——
 * 等于调的过程中看不见自己调的是什么。
 *
 * ## 为什么中间那格可以点
 * 点它清回 0（只在不是 0 的时候可点）。清回原状是这个功能唯一的"取消"，
 * 而它必须比"再按二十下减号"更容易够到 —— 用户试出一个不喜欢的偏移之后
 * 想的是"算了"，不是"一步一步退回去"。
 */
@Composable
private fun LyricOffsetBar(
    offsetMs: Long,
    onNudge: (Int) -> Unit,
    onReset: () -> Unit
) {
    val changed = offsetMs != 0L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "歌词偏移",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(Modifier.weight(1f))
        IconAction(
            imageVector = MelodyIcons.Minus,
            contentDescription = "歌词提前 0.5 秒",
            onClick = { onNudge(-1) },
            iconSize = 18.dp,
            touchSize = 36.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 顶在 ±30 秒上就别再让它可点：按下去什么都不发生，会像卡了
            enabled = offsetMs > -LyricOffset.MAX_MS
        )
        Text(
            text = LyricOffset.label(offsetMs),
            style = MaterialTheme.typography.labelLarge,
            // 改过就用主色：不改的时候它该像不存在，改过就必须一眼看出"不是原状了"
            color = if (changed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .width(84.dp)
                .clickable(enabled = changed, onClick = onReset)
                .padding(vertical = 4.dp),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        IconAction(
            imageVector = MelodyIcons.Plus,
            contentDescription = "歌词延后 0.5 秒",
            onClick = { onNudge(1) },
            iconSize = 18.dp,
            touchSize = 36.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            enabled = offsetMs < LyricOffset.MAX_MS
        )
    }
}

/** 播放速度小标。原速时什么都不画 —— 一个永远写着 `1.00×` 的角标只是噪音。 */
@Composable
private fun SpeedBadge(speed: Float) {
    if (PlaybackSpeed.isDefault(speed)) return
    Text(
        text = PlaybackSpeed.label(speed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(end = 10.dp)
    )
}

/**
 * 睡眠定时倒计时小标。
 *
 * 直接读流（而不是收一个值进来）是为了把每秒的重组限制在这**一个** `Text` 上：
 * 倒计时每秒都在变，值要是从上层传进来，整页会跟着每秒重组一遍。
 */
@Composable
private fun SleepCountdownBadge(sleepRemaining: StateFlow<Long?>) {
    val remainingMs by sleepRemaining.collectAsStateWithLifecycle()
    val value = remainingMs ?: return
    Text(
        text = SleepTimer.remainingLabel(value),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(end = 10.dp)
    )
}

/** 歌词页的空/忙状态：一个转圈加一行说明，两处共用。 */
@Composable
private fun LyricsBusy(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 歌词页里的胶囊按钮：[filled] 为 true 时用主题色实心，作为首选动作。 */
@Composable
private fun LyricsActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        filled -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        filled -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = CircleShape,
        color = container,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = content
            )
            Spacer(Modifier.width(8.dp))
            Text(text = text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/**
 * 联网搜到的候选歌曲列表。
 *
 * 让用户自己确认一首，而不是默默取第一条 —— 本地 mp3 的标题/歌手标签经常不准，
 * 自动匹配一旦猜错，用户会一脸疑惑地看到别人的歌词。
 */
@Composable
private fun OnlineLyricPickerDialog(
    songTitle: String,
    candidates: List<OnlineSong>,
    onPick: (OnlineSong) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择匹配的歌曲") },
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
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(candidates) { _, candidate ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(candidate) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = candidate.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    // 标出来源：两家的曲库不一样，用户挑的时候心里要有数
                                    append(candidate.provider.label)
                                    append(" · ")
                                    append(candidate.artistText)
                                    candidate.album?.let { append(" · ").append(it) }
                                    if (candidate.durationMs > 0) {
                                        append(" · ").append(TimeFormat.clock(candidate.durationMs))
                                    }
                                    // 只有提供方明确说了"没有时间轴"才提示；网易云要取到词才知道，不猜
                                    if (candidate.synced == false) append(" · 纯文本")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    buffering: Boolean,
    playMode: PlayMode,
    enabled: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val idleTint = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 播放模式：一个按钮循环切换四种互斥状态，图标即当前状态
        PlayModeButton(
            mode = playMode,
            enabled = enabled,
            onClick = onCyclePlayMode
        )
        IconAction(
            imageVector = MelodyIcons.SkipPrevious,
            contentDescription = "上一首",
            onClick = onPrevious,
            iconSize = 26.dp,
            touchSize = 44.dp,
            tint = MaterialTheme.colorScheme.onSurface,
            enabled = enabled
        )

        // 主播放键：全页唯一的实心大圆钮
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable(enabled = enabled, onClick = onTogglePlay),
            contentAlignment = Alignment.Center
        ) {
            when {
                buffering -> CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp
                )

                else -> Icon(
                    imageVector = if (isPlaying) MelodyIcons.Pause else MelodyIcons.Play,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(34.dp),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        IconAction(
            imageVector = MelodyIcons.SkipNext,
            contentDescription = "下一首",
            onClick = onNext,
            iconSize = 26.dp,
            touchSize = 44.dp,
            tint = MaterialTheme.colorScheme.onSurface,
            enabled = enabled
        )
        IconAction(
            imageVector = MelodyIcons.QueueList,
            contentDescription = "查看播放队列",
            onClick = onOpenQueue,
            iconSize = 22.dp,
            touchSize = 44.dp,
            tint = idleTint,
            enabled = true
        )
    }
}

/** 播放模式按钮：图标随当前模式变化，长按等价的说明由 contentDescription 提供。 */
@Composable
private fun PlayModeButton(
    mode: PlayMode,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconAction(
        imageVector = playModeIcon(mode),
        contentDescription = "播放模式：${mode.label}，点击切换",
        onClick = onClick,
        iconSize = 22.dp,
        touchSize = 44.dp,
        tint = if (mode.isDefault) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
        enabled = enabled
    )
}

/** 上下滑切歌需要的累计位移（dp）。太小吃一下就切歌，太大像没反应。 */
private val SWIPE_SWITCH_THRESHOLD = 110.dp

/** 滑出/滑入动画的最大位移（dp），同时也是拖动中封面位移的上限。 */
private val SLIDE_OUT_DISTANCE = 220.dp

/**
 * 歌词的基准字号 / 行高 / 行内边距。
 *
 * 「标准」那一挡就是这三个数本身（16sp / 25sp / 9dp，也就是旧版本一直用的值），
 * 其余三挡在它们上面乘一个 scale —— 加档位时不用重新调这四个数字。
 */
private const val LYRIC_BASE_SP = 16f
private const val LYRIC_LINE_SP = 25f
private const val LYRIC_ROW_PAD_DP = 9f
