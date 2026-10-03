package com.melody.player.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.melody.player.core.TimeFormat
import com.melody.player.ui.components.EmptyState
import com.melody.player.ui.components.IconAction
import com.melody.player.ui.components.PlayingBars
import com.melody.player.ui.components.RemoveStyle
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.components.SongRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

/**
 * 播放队列，支持**长按拖到任意位置**。
 *
 * ## 三条不能动的设计决定
 *
 * **一、拖动期间不重排真实队列，松手才提交。**
 * 早期版本在 `onDrag` 里每一次越位都调 `moveInQueue`，看起来"顺序立刻生效"很酷，
 * 但它同时踩了三个坑：数据变了而布局要到下一帧才更新（同一帧内被重复判算，行在两格间横跳）；
 * 换位后行下标变了而手势的 key 里带着下标（手势被取消重建，拖动当场断掉）；
 * 还得额外维护"换位补偿偏移"（行高一不等就错）。
 * 改成松手提交之后布局全程不动，被拖行直接跟手，补偿量这个概念就不存在了。
 *
 * **二、手势挂在容器上，不挂在行上。**
 * 这是这一版修的 bug。挂在行上时，行是 `LazyColumn` 的 item —— 自动滚动会把这一行
 * 在布局上推出可视区，`LazyColumn` 随即**回收**它，`pointerInput` 节点被销毁，
 * 手势当场中断。表现就是用户报的"往上滚超过几首就断触"（往上滚最容易触发：
 * 上边缘的热区是够得着的，而下边缘的热区当时被底栏挡着，所以往下几乎不滚、
 * 也就不会断）。挂在容器上就与滚动解耦了：item 可以被回收，手势不受影响。
 *
 * **三、被拖的那一行画在列表外面（浮层）。**
 * 与第二条同源：行一旦被回收就没人画它了，"手指还在拖、行却没了"。浮层挂在容器这一层，
 * 列表怎么滚都不影响它。列表里原位那一条只留 `alpha = 0` 占住高度，让落点计算不变。
 *
 * ## 自动滚动的边界
 * 下边界必须减掉**被底栏盖住的那一段**。`viewportEndOffset` 把列表底部的
 * `contentPadding` 也算进了可视区，而那段 padding 正是迷你条 + 导航栏的高度，
 * 用它当边界就会得出"要拖到屏幕最底下、被底栏盖住之后才开始滚"。
 */
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

    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ---------------- 拖动会话状态 ----------------

    /** 拖动开始时它在队列里的位置，也是松手提交时的 from。**-1 表示当前没有拖动**。 */
    var dragFromIndex by remember { mutableIntStateOf(-1) }

    /** 按住那一刻，手指在 item 偏移坐标系里的纵坐标。 */
    var dragAnchorCenter by remember { mutableFloatStateOf(0f) }

    /** 手指相对按下点的累计纵向位移。 */
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    /** 当前算出的目标槽位。 */
    var dragTargetIndex by remember { mutableIntStateOf(-1) }

    /**
     * 按住那一刻量到的行高，全程用它做让位位移。
     *
     * **不能在渲染时现算**：被拖那一行在布局上会随列表滚动离开可视区，一旦离开，
     * `visibleItemsInfo` 里就没有它，现算出来的行高变成 0，所有让位位移一起塌掉。
     */
    var dragRowHeight by remember { mutableFloatStateOf(0f) }

    /** 自动滚动方向：-1 向上、0 停、+1 向下。 */
    var autoScrollDir by remember { mutableIntStateOf(0) }

    /**
     * 抑制松手那一下被当成单击。
     *
     * 行的 `clickable` 与容器上的长按拖动是两个识别器。只要拖动**动过**，拖动侧就会
     * `consume` 掉移动事件、`clickable` 自己会取消；但长按之后手指几乎没动就松手时，
     * `clickable` 会认为这是一次普通点击 → 播放，而此刻队列可能刚重排过，
     * 下标指向的早已不是原来那首歌（表现："拖完自动播了另一首"）。
     *
     * 抑制必须从**长按成立那一刻**就开始：`clickable` 的 onClick 与 `onDragEnd`
     * 都在抬手时触发，谁先谁后没有保证。
     */
    var suppressPlay by remember { mutableStateOf(false) }

    /**
     * 列表可视区的高度（像素），由容器自己量出来。
     *
     * 不用 `layoutInfo.viewportEndOffset`：它把列表底部的 `contentPadding` 也算了进去，
     * 而那段正好是被底栏挡住的高度 —— 拿它当边界会得到"拖到底栏下面才开始滚"。
     */
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    val headerCount = if (state.currentSong != null) 3 else 0

    /** 底部被迷你条 + 导航栏盖住的高度。 */
    val bottomOverlayPx = with(density) { contentPadding.calculateBottomPadding().toPx() }
    val edgeZonePx = with(density) { EDGE_ZONE_DP.toPx() }

    val dragging = dragFromIndex >= 0

    // 手势挂在容器上、只跑一次，所以回调里读到的必须是"最新的一份"，
    // 否则会一直用第一次组合时的曲库快照去判命中。
    val queueRef = rememberUpdatedState(state.queue)
    val headerCountRef = rememberUpdatedState(headerCount)
    val moveRef = rememberUpdatedState(onMoveIndex)

    /** 只保留曲目行，并把 LazyColumn 下标换算成队列下标。 */
    fun visibleRows(): List<RowLayout> {
        val queue = queueRef.value
        val header = headerCountRef.value
        return listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val queueIndex = item.index - header
            if (queueIndex in queue.indices) {
                RowLayout(queueIndex, item.offset, item.offset + item.size, item.size)
            } else {
                null
            }
        }
    }

    /**
     * 手指（也就是被拖行的中心）此刻落在 item 偏移坐标系里的哪个纵坐标。
     *
     * 夹在可见行的首尾中心之间：否则行会飘到"正在播放"卡片上或列表外面去。
     * 夹住之后拖到边缘时行会"贴住"边缘，配合自动滚动就能一路把目标推到底。
     *
     * 拖动的每一帧都要算一份 —— 手指动、列表滚，这两个输入都会变。
     */
    fun draggedCenterNow(): Float {
        val rows = visibleRows()
        if (rows.isEmpty()) return 0f
        return (dragAnchorCenter + dragDeltaY).coerceIn(rows.first().center, rows.last().center)
    }

    /**
     * 按当前布局重算"落点"，并同步自动滚动方向。
     *
     * 拖动中和自动滚动的每一帧都要调：列表滚过之后，压在手指下面的已经是**别的**行了。
     */
    fun syncDrag() {
        val rows = visibleRows()
        if (rows.isEmpty()) return
        val center = draggedCenterNow()
        val target = queueDragTarget(rows, center)
        if (target >= 0 && target != dragTargetIndex) {
            dragTargetIndex = target
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        val info = listState.layoutInfo
        autoScrollDir = queueAutoScrollDir(
            center = center,
            viewportTop = info.viewportStartOffset.toFloat(),
            // 下边界减掉被底栏盖住的那一段，热区才落在"眼睛还看得见"的位置上
            viewportBottom = info.viewportStartOffset + viewportHeightPx - bottomOverlayPx,
            edgeZone = edgeZonePx
        )
    }

    fun resetDrag() {
        dragFromIndex = -1
        dragTargetIndex = -1
        dragDeltaY = 0f
        dragAnchorCenter = 0f
        dragRowHeight = 0f
        autoScrollDir = 0
    }

    /** 松手后再静默一小段，让 `clickable` 的那次 onClick 落空。 */
    fun releaseSuppressPlay() {
        scope.launch {
            delay(300)
            suppressPlay = false
        }
    }

    // ---------------- 拖动中的派生量（渲染用） ----------------

    /** 被拖行当前的纵坐标（item 偏移坐标系），与布局无关。 */
    val draggedCenter = if (dragging) draggedCenterNow() else 0f

    /**
     * 自动滚动：手指停在热区里就**持续**滚，而不是只在手指移动的那几帧滚一下。
     * 长队列拖到底部时手指通常是不动的，只靠 `onDrag` 触发的滚动会"滚两下就停"。
     *
     * ## 为什么按「真实帧间隔 × 速度」算，而不是「每帧推进固定像素」
     * 原来写的是 `delay(16); scrollBy(22f)`，观感是"一顿一顿"，三个原因叠在一起：
     *  1. 每帧都跳 22px —— 位移是一串台阶而不是一条线；
     *  2. 速度与手指停在热区**多深**无关：刚进热区就和贴在屏幕边缘一样快，
     *     于是"要不要滚"这件事在热区边界上是**突然**发生的；
     *  3. 16ms 只是个目标值，真正两帧隔多久由调度决定，抖动直接变成滚动的抖动。
     *
     * 现在滚动量 = 速度 × 真实帧间隔；速度随进热区的深度线性上升（见 [queueAutoScrollSpeed]）：
     * 刚进热区慢慢挪、越靠边越快。帧间隔夹一个上限，掉帧时宁可少滚一点，
     * 也别一帧跳出去半屏（那会把列表甩过目标）。
     */
    LaunchedEffect(dragging, autoScrollDir) {
        if (!dragging || autoScrollDir == 0) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        // 攒够 1px 再去滚一次：不足 1px 的请求会被 Scrollable 直接舍掉，
        // 而高刷屏上最慢一档一帧只够滚不到 1px（140px/s ÷ 240Hz ≈ 0.6px），
        // 不攒的话"手指在热区里慢慢挪"会变成完全不动。攒着攒着也是平滑的一部分。
        var pendingPx = 0f
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSeconds = (nowNanos - lastNanos) / 1_000_000_000f
            lastNanos = nowNanos
            val info = listState.layoutInfo
            val speedPxPerSec = queueAutoScrollSpeed(
                center = draggedCenterNow(),
                viewportTop = info.viewportStartOffset.toFloat(),
                viewportBottom = info.viewportStartOffset + viewportHeightPx - bottomOverlayPx,
                edgeZone = edgeZonePx
            )
            if (speedPxPerSec == 0f) break
            pendingPx += speedPxPerSec * dtSeconds.coerceIn(0f, MAX_FRAME_SECONDS)
            if (pendingPx < 1f) continue
            val consumed = listState.scrollBy(pendingPx)
            pendingPx -= consumed
            // 攒满一整像素还是滚不动 → 已经到顶/到底了，停，否则空转一整个循环
            if (consumed == 0f) break
            // 列表动过之后手指底下已是别的行，落点跟着重算
            syncDrag()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pressed ->
                        val info = listState.layoutInfo
                        // 手指位置换到 item 偏移的坐标系里再比，两边才是同一套坐标
                        val y = pressed.y + info.viewportStartOffset
                        val hit = info.visibleItemsInfo.firstOrNull { item ->
                            val queueIndex = item.index - headerCountRef.value
                            queueIndex in queueRef.value.indices &&
                                y >= item.offset && y < item.offset + item.size
                        }
                        // 按在"正在播放"卡片 / 空白 / 列表外的 padding 上：不算拖动
                        if (hit == null) return@detectDragGesturesAfterLongPress
                        val index = hit.index - headerCountRef.value
                        dragFromIndex = index
                        dragTargetIndex = index
                        dragRowHeight = hit.size.toFloat()
                        dragAnchorCenter = y
                        dragDeltaY = 0f
                        autoScrollDir = 0
                        suppressPlay = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragCancel = {
                        resetDrag()
                        releaseSuppressPlay()
                    },
                    onDragEnd = {
                        val from = dragFromIndex
                        val to = dragTargetIndex
                        resetDrag()
                        releaseSuppressPlay()
                        if (from >= 0 && to >= 0 && from != to) moveRef.value(from, to)
                    },
                    onDrag = { change, dragAmount ->
                        if (dragFromIndex < 0) return@detectDragGesturesAfterLongPress
                        change.consume()
                        dragDeltaY += dragAmount.y
                        syncDrag()
                    }
                )
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 拖动期间关掉列表自己的滚动。
            // 两个手势都在 Main 阶段处理，而子节点（列表）**先**拿到事件 ——
            // 不关的话手指一拖，列表会跟着一起滚，行和手指立刻错位。
            userScrollEnabled = !dragging,
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
                    Text(
                        // 拖动是这个页面的主操作，不写在空状态里（空的时候没东西可拖），
                        // 直接放在曲目区标题下面
                        text = "长按任意一首拖到想要的位置，松手生效",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            // key 只用下标：队列里同一首歌**可以出现多次**（「下一首播放」能反复插同一首），
            // 用 song.key 会撞出重复 key 让 LazyColumn 直接抛异常。
            itemsIndexed(items = state.queue, key = { index, _ -> "queue-$index" }) { index, song ->
                val isDragged = index == dragFromIndex

                // 其余行让位：被拖行跨过的那一段整体退开一个行高，用户能看出会落到哪
                val shiftTarget = when {
                    !dragging || isDragged -> 0f
                    dragFromIndex < dragTargetIndex && index in (dragFromIndex + 1)..dragTargetIndex ->
                        -dragRowHeight
                    dragTargetIndex in 0 until dragFromIndex && index in dragTargetIndex until dragFromIndex ->
                        dragRowHeight
                    else -> 0f
                }
                // 让位位移用弹簧而不是 tween：拖动时目标槽位是**连续**变的
                //（自动滚动时每几十毫秒就换一格），tween 被反复打断、每次都从当前值零速重启，
                // 看起来一顿一顿；弹簧会把速度带走，中途换目标也是顺着原来的方向滑过去。
                // NoBouncy：让位位移一旦回弹，行会短暂盖到邻行上，比"慢一点"难看得多。
                val shift by animateFloatAsState(
                    targetValue = shiftTarget,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "queueRowShift"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = shift }
                        // 被拖那一行由下面的浮层负责画；这里只留个空位。
                        // 直接把它从列表里去掉是不行的 —— 那会让后面的行整体上移一格，
                        // 落点计算（基于布局）就跟着漂了。
                        .alpha(
                            when {
                                isDragged -> 0f
                                dragging -> 0.5f
                                else -> 1f
                            }
                        )
                ) {
                    SongRow(
                        song = song,
                        index = index,
                        isCurrent = state.currentIndex == index,
                        isPlaying = state.isPlaying,
                        onClick = { if (!suppressPlay) onPlayIndex(index) },
                        // 队列里没有「下一首播放」这回事：这儿的顺序就是用户自己拖出来的，
                        // 想让它下一个播，把它拖到当前这首后面就行
                        onPlayNext = null,
                        onRemove = { onRemoveIndex(index) },
                        removeStyle = RemoveStyle.QUEUE,
                        // 长按让给容器上的拖动排序：两个长按识别器同时挂在一行上会互相抢
                        longPressEnabled = false,
                        // 拖动已经能改顺序了，菜单里的「上移/下移」在拖动可用时是冗余的
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

        // ---------------- 被拖的那一行（浮层） ----------------
        //
        // 必须画在列表**外面**：列表会滚动，而被拖这一行在布局上还留在原位，
        // 一滚就被 LazyColumn 回收（离开可视区的 item 会被丢掉）——
        // 手势挂在容器上不会中断，可行本身没人画了，看起来就是"拖丢了"。
        // 浮层在容器这一层，列表怎么滚都不影响它。
        if (dragging) {
            val song = state.queue.getOrNull(dragFromIndex)
            val rowHeight = dragRowHeight
            if (song != null && rowHeight > 0f) {
                // 行坐标 → 容器坐标，再让被拖中心落在行高的中点上
                val topPx = (draggedCenter - listState.layoutInfo.viewportStartOffset - rowHeight / 2f)
                    .roundToInt()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, topPx) }
                        .zIndex(1f)
                        .graphicsLayer {
                            scaleX = 1.02f
                            scaleY = 1.02f
                            // 阴影画在图层上：写成 Modifier.shadow(...) 它在图层**之外**，
                            // 行跟着手指走、阴影留在原地
                            shadowElevation = 14.dp.toPx()
                            shape = RoundedCornerShape(16.dp)
                            clip = false
                        }
                        // SongRow 自身没有底色，浮起来之后下面的行会从它身上透出来
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                ) {
                    SongRow(
                        song = song,
                        index = dragFromIndex,
                        isCurrent = state.currentIndex == dragFromIndex,
                        isPlaying = state.isPlaying,
                        // 浮层只是个"影子"：它压在手指底下，不接受任何操作
                        onClick = {},
                        onPlayNext = null,
                        onRemove = null,
                        longPressEnabled = false
                    )
                }
            }
        }
    }
}

/**
 * 距列表上下边缘多近开始自动滚动（dp）。
 *
 * 64 而不是更小：这段距离同时是"速度从最慢爬到最快"的行程，太短的话
 * 手指刚进热区就已经贴着边、速度瞬间拉满，还是会有"突然开始滚"的感觉。
 */
private val EDGE_ZONE_DP = 64.dp

/**
 * 手指刚进热区时的滚动速度（像素/秒）。
 *
 * 刻意不为 0：热区边界上速度必须是**连续**的，若从 0 起步，手指在边界附近抖动就会
 * 在"滚一点点"和"完全不滚"之间反复横跳。给一个很小的初速，用户一进热区就能看出这个方向是通的。
 */
private const val MIN_SCROLL_PX_PER_SEC = 140f

/** 手指贴到边缘时的滚动速度（像素/秒）。 */
private const val MAX_SCROLL_PX_PER_SEC = 1500f

/** 单帧最多按多少秒算。掉帧时宁可少滚一点，也别一帧跳过小半屏。 */
private const val MAX_FRAME_SECONDS = 0.05f

/**
 * 一行在屏幕上的位置（像素）。
 *
 * 只保留判算真正用到的四个量：下标、顶边、底边、高度。
 * 之所以不直接传 [androidx.compose.foundation.lazy.LazyListItemInfo]，
 * 是因为那样这个函数就没法在 JVM 单测里跑了 —— 而"落点算错"恰恰是
 * 拖拽里最容易出、又最难靠肉眼复现的一类问题。
 */
internal data class RowLayout(
    val index: Int,
    val offset: Int,
    val end: Int,
    val size: Int
) {
    val center: Float get() = (offset + end) / 2f
}

/**
 * 被拖行现在压在第几行上，就落到第几行 —— 一次可以跨任意多格。
 *
 * ## 判据：行的**区间**，不是行的中心
 * 取"顶边 ≤ 被拖中心"的最后一行。行是连续排布的半开区间 `[offset, end)`，
 * 所以这等价于"被拖中心落在谁的槽位里"。
 *
 * 不用"跨过行中心"作判据，是因为那要拖过半行才换位，
 * 与"拖到哪一行就落到哪一行"的直觉差半格 —— 落点看起来总慢一拍。
 *
 * ## 为什么早期版本做不到任意跨度
 * 中途改成了"只看紧邻的上下两行、一次推进一格"，那是为了绕开
 * "原地不动也被判成跨过了上面某一行"的 bug。但代价是**拖不快**：
 * 一次 `onDrag` 只推进一格，手指甩得快、事件来得稀，行就始终落在手指后面，
 * 用户的感觉正是"只能一格一格地调，不能拖到我想去的地方"。
 * 真正的问题不在"看几行"，而在判据本身会原地乱跳。
 *
 * 这个判据随手指**单调**变化，所以既不会原地乱跳，又天然支持任意跨度。
 * 传空列表返回 -1（拖动开始时列表还没布局完）。
 */
internal fun queueDragTarget(rows: List<RowLayout>, draggedCenter: Float): Int {
    if (rows.isEmpty()) return -1
    val sorted = rows.sortedBy { it.offset }
    // 拖到列表最上方（比第一行顶边还高）时落第一行
    return (sorted.lastOrNull { it.offset <= draggedCenter } ?: sorted.first()).index
}

/**
 * 被拖中心落在上下热区里时该往哪边自动滚：-1 向上、0 不动、+1 向下。
 *
 * 方向直接由 [queueAutoScrollSpeed] 的符号给出 —— 这两件事本来就是一体的，
 * 各写一份迟早会写出"方向说往上、速度却是正的"这种自相矛盾。
 *
 * 抽成纯函数是因为它的两个边界都很容易写错，而且错了**不崩**：
 * 用户只会觉得"拖不动"，然后放弃这个功能。具体：
 *
 *  1. **下边界必须由调用方交进来**，而不能用 `viewportEndOffset` ——
 *     那里面含着列表底部的 `contentPadding`（= 迷你条 + 导航栏的高度），
 *     于是热区被推到屏幕之外，要拖到行被底栏盖住才开始滚。
 *  2. **热区不能比可视区还高**。列表很短（或键盘弹出）时，
 *     上下热区会重叠成"两边同时成立"，行就会在两点之间来回抖。
 *     热区压到可视区的三分之一以内，两侧就永远不可能重叠（见 [queueAutoScrollSpeed]）。
 */
internal fun queueAutoScrollDir(
    center: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgeZone: Float
): Int {
    val speed = queueAutoScrollSpeed(center, viewportTop, viewportBottom, edgeZone)
    return when {
        speed < 0f -> -1
        speed > 0f -> 1
        else -> 0
    }
}

/**
 * 自动滚动的速度（像素/秒，负数是向上），0 表示"这个位置不该滚"。
 *
 * 返回**速度**而不是"要不要滚 + 固定步长"，是为了让滚动本身平滑：
 * 速度随手指进热区的深度从 [MIN_SCROLL_PX_PER_SEC] 线性爬到 [MAX_SCROLL_PX_PER_SEC]，
 * 调用方再乘以真实帧间隔 —— 于是"刚进热区"与"贴着边"是同一套连续变化的量，
 * 不会在热区边界上突然开始/突然变快。
 *
 * 热区被压在可视区的三分之一以内（与 [queueAutoScrollDir] 的说明同一条理由），
 * 所以上下两侧不可能同时为非零，`when` 里谁先谁后都无所谓。
 */
internal fun queueAutoScrollSpeed(
    center: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgeZone: Float
): Float {
    val height = viewportBottom - viewportTop
    if (height <= 0f) return 0f
    val zone = edgeZone.coerceIn(1f, height / 3f)
    // 两侧各算一个"进热区的深度"（>0 表示在这一侧的热区里），深度越大速度越快
    val upDepth = ((viewportTop + zone) - center) / zone
    val downDepth = (center - (viewportBottom - zone)) / zone
    return when {
        upDepth > 0f -> -speedRamp(upDepth)
        downDepth > 0f -> speedRamp(downDepth)
        else -> 0f
    }
}

/** 进热区的深度（0 = 刚进热区，1 = 已到边缘）→ 滚动速度，线性插值。 */
private fun speedRamp(depth: Float): Float {
    val d = depth.coerceIn(0f, 1f)
    return MIN_SCROLL_PX_PER_SEC + (MAX_SCROLL_PX_PER_SEC - MIN_SCROLL_PX_PER_SEC) * d
}
