package com.melody.player.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
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
 * ## 拖动模型：拖动期间不重排真实队列，松手才提交
 * 这是这份实现里唯一的"设计决定"，其余都是它的推论。
 *
 * 早期版本在 `onDrag` 里每一次越位都调 `moveInQueue`，看起来"顺序立刻生效"很酷，
 * 但它同时踩了三个坑：
 *  1. `state.queue` 变了而 `listState.layoutInfo` 要到下一帧才更新，同一帧内
 *     会被同一次拖动再判一次 —— 行在两格之间来回横跳。
 *  2. 换位后行下标变了，而 `pointerInput(index)` 的 key 里带着下标 →
 *     手势被取消重建，拖动当场断掉。表现就是"一次只能挪一格，挪完还得重新按"。
 *  3. 拖动期间还要维护"换位补偿偏移"（`dragOffsetY += ±行高`），行高一不等就错。
 *
 * 改成松手提交之后，**布局在拖动全程不动**：
 *  - 被拖行用 [graphicsLayer] 的 `translationY` 直接跟手，没有基准位置变化，
 *    自然也就不需要补偿；
 *  - 目标位置由"被拖中心的屏幕坐标落在哪个槽位"直接算出，一次可以跨任意多格；
 *  - 其它行按区间做让位位移，用户能预判松手会落到哪；
 *  - 松手时只调**一次** [onMoveIndex]，真实队列与播放器下标只重排一次。
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

    // ---------------- 拖动会话状态 ----------------

    /** 被拖歌曲的 key；null 表示当前没有拖动。用 key 而不是下标：下标在提交时会变。 */
    var draggedKey by remember { mutableStateOf<String?>(null) }

    /** 拖动开始时它在队列里的位置，也是松手提交时的 from。全程不变。 */
    var dragFromIndex by remember { mutableIntStateOf(-1) }

    /** 按住那一刻，被拖行在屏幕上的中心（列表坐标系）。 */
    var dragAnchorCenter by remember { mutableFloatStateOf(0f) }

    /** 手指相对按下点的累计纵向位移。 */
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    /** 当前算出的目标槽位；-1 表示没在拖动。 */
    var dragTargetIndex by remember { mutableIntStateOf(-1) }

    /**
     * 抑制松手那一下被当成单击。
     *
     * 行的 `clickable` 与这里的长按拖动是两个识别器。只要拖动**动过**，
     * 拖动侧就会 `consume` 掉移动事件，`clickable` 自己会取消；但如果长按之后
     * 手指几乎没动（不到 touch slop）就松手，`clickable` 会认为这是一次普通点击 → 播放。
     * 而此刻队列可能刚重排过，[index] 指向的早已不是原来那首歌 ——
     * 表现就是"拖完自动播了另一首"。
     *
     * 抑制必须从 `onDragStart`（长按判定成功那一刻）就开始：
     * `clickable` 的 onClick 与 `onDragEnd` 都在抬手时触发，**谁先谁后没有保证**。
     */
    var suppressPlay by remember { mutableStateOf(false) }

    /**
     * 队列上方的头部占了几个 item。
     *
     * [androidx.compose.foundation.lazy.LazyListLayoutInfo.visibleItemsInfo] 里的
     * `index` 算的是 **LazyColumn 的 item 下标**，而不是队列里的第几首 ——
     * 上面还有"正在播放"那一块。差一位就会拖错行，所以这里显式记下来。
     */
    val headerCount = if (state.currentSong != null) 3 else 0

    /** 只保留曲目行，并把 LazyColumn 下标换算成队列下标。 */
    fun visibleRows(): List<RowLayout> = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
        val queueIndex = item.index - headerCount
        if (queueIndex in state.queue.indices) {
            RowLayout(queueIndex, item.offset, item.offset + item.size, item.size)
        } else {
            null
        }
    }

    fun resetDrag() {
        draggedKey = null
        dragFromIndex = -1
        dragTargetIndex = -1
        dragDeltaY = 0f
        dragAnchorCenter = 0f
    }

    /** 松手后再静默一小段，让 `clickable` 的那次 onClick 落空。 */
    fun releaseSuppressPlay() {
        scope.launch {
            delay(300)
            suppressPlay = false
        }
    }

    // ---------------- 拖动中的派生量（渲染用） ----------------

    val dragging = draggedKey != null
    val rows = if (dragging) visibleRows() else emptyList()
    val draggedRowHeight = rows.firstOrNull { state.queue.getOrNull(it.index)?.key == draggedKey }
        ?.size?.toFloat() ?: 0f

    /**
     * 被拖行当前的中心（屏幕坐标）。
     *
     * 夹在可见行的首尾中心之间：否则行会飘到"正在播放"卡片上或列表外面去。
     * 夹住之后拖到边缘时行会"贴住"边缘，配合自动滚动就能一路把目标推到底。
     */
    val draggedCenter = if (dragging && rows.isNotEmpty()) {
        (dragAnchorCenter + dragDeltaY).coerceIn(rows.first().center, rows.last().center)
    } else {
        0f
    }

    LazyColumn(
        state = listState,
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
            val isDragged = dragging && song.key == draggedKey
            val itemInfo = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == index + headerCount }
            val rowCenter = itemInfo?.let { it.offset + it.size / 2f } ?: 0f

            // 被拖行：绝对定位。translationY 是"相对本行布局位置"的位移，
            // 所以直接用「目标中心 − 当前布局中心」，不需要维护任何补偿量
            val dragTranslation = if (isDragged && itemInfo != null) draggedCenter - rowCenter else 0f

            // 其余行让位：被拖行跨过的那一段整体退开一个行高，用户能看出会落到哪
            val shiftTarget = when {
                !dragging || isDragged -> 0f
                dragFromIndex < dragTargetIndex && index in (dragFromIndex + 1)..dragTargetIndex ->
                    -draggedRowHeight
                dragTargetIndex in 0 until dragFromIndex && index in dragTargetIndex until dragFromIndex ->
                    draggedRowHeight
                else -> 0f
            }
            val shift by animateFloatAsState(
                targetValue = shiftTarget,
                animationSpec = tween(durationMillis = 140),
                label = "queueRowShift"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 被拖行浮到最上层；zIndex 必须排在 graphicsLayer 之前，
                    // 否则抬升的层级不会作用到实际绘制的那一层
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        translationY = dragTranslation + shift
                        // 略微放大，给一个"被拿起来了"的视觉
                        val s = if (isDragged) 1.02f else 1f
                        scaleX = s
                        scaleY = s
                        // 阴影必须画在 graphicsLayer 上：写成 Modifier.shadow(...) 的话
                        // 它位于图层**之外**，行跟着手指走、阴影却留在原地
                        shadowElevation = if (isDragged) 14.dp.toPx() else 0f
                        if (isDragged) {
                            shape = RoundedCornerShape(16.dp)
                            clip = false
                        }
                    }
                    .then(
                        // 被拖行要不透明：SongRow 自身没有底色，浮起来之后
                        // 下面的行会从它身上透出来，看起来糊成一团
                        if (isDragged) {
                            Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        } else {
                            Modifier
                        }
                    )
                    .pointerInput(song.key, index, headerCount) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                val item = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == index + headerCount }
                                    ?: return@detectDragGesturesAfterLongPress
                                draggedKey = song.key
                                dragFromIndex = index
                                dragTargetIndex = index
                                dragAnchorCenter = item.offset + item.size / 2f
                                dragDeltaY = 0f
                                // 长按已经成立，这一次抬手就不再是"点击"了
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
                                if (from >= 0 && to >= 0 && from != to) onMoveIndex(from, to)
                            },
                            onDrag = { change, dragAmount ->
                                if (draggedKey == null) return@detectDragGesturesAfterLongPress
                                change.consume()
                                dragDeltaY += dragAmount.y

                                val visible = visibleRows()
                                if (visible.isEmpty()) return@detectDragGesturesAfterLongPress

                                val raw = dragAnchorCenter + dragDeltaY
                                val center = raw.coerceIn(visible.first().center, visible.last().center)

                                // 一次跨几格都行：目标就是"被拖中心落在谁身上"
                                val target = queueDragTarget(visible, center)
                                if (target != dragTargetIndex && target >= 0) {
                                    dragTargetIndex = target
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }

                                // 拖到列表边缘时自动滚动：否则长队列只能重排眼前这几屏，
                                // 想要的那首在很下面时就完全够不着
                                val info = listState.layoutInfo
                                if (center < info.viewportStartOffset + EDGE_ZONE_PX) {
                                    scope.launch { listState.scrollBy(-EDGE_SCROLL_PX) }
                                } else if (center > info.viewportEndOffset - EDGE_ZONE_PX) {
                                    scope.launch { listState.scrollBy(EDGE_SCROLL_PX) }
                                }
                            }
                        )
                    }
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
                    // 长按让给外层的拖动排序：这两个手势不能同时挂在一行上
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
                    },
                    // 别的行在拖动期间压暗，视线自然落到被拖的那一行
                    modifier = if (!isDragged && dragging) Modifier.alpha(0.5f) else Modifier
                )
            }
        }
    }
}

/** 距离列表上下边缘多近就开始自动滚动（像素）。 */
private const val EDGE_ZONE_PX = 120f

/** 每次自动滚动多少像素。 */
private const val EDGE_SCROLL_PX = 28f

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
