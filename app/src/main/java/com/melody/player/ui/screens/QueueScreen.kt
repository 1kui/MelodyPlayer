package com.melody.player.ui.screens

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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
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
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.components.SongRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
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
 * 播放队列，支持**长按拖动排序**。
 *
 * ## 为什么拖动要自己写，而不是靠现成组件
 * 依赖清单是刻意压到最低的（无第三方标签库、无拖拽库），所以这套手势在这里实现。
 * 真正难的不是"跟着手指动"，而是三件事：
 *  1. **算目标位置**：不能只看"手指现在压在谁身上"，而要看被拖行**跨过了谁的中心线**。
 *     差半行就该换位，差一点点就换位会让列表抖个不停。
 *  2. **换位后补偿偏移**：换位会重排，被拖行的新基准位置变了，拖动偏移必须跟着减去
 *     一段距离，否则它会在松手前"跳"一下。
 *  3. **拖动时给出反馈**：放大 + 触觉 + 其它行变淡，否则用户不知道松手会落在哪。
 *
 * [onMoveIndex] 每次换位都会立刻改真正的播放队列（见 `PlayerViewModel.moveInQueue`），
 * 所以拖动过程中听到的顺序变化是真实生效的，不是松手才生效。
 */
@Composable
fun QueueContent(
    state: PlayerUiState,
    positionMs: Long,
    contentPadding: PaddingValues,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 拖动排序专用：中途换位不弹提示（[PlayerViewModel.moveInQueue] 的 announce = false）。 */
    onDragIndex: (from: Int, to: Int) -> Unit = { _, _ -> },
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

    // 正在被拖动的那一行；null 表示当前没有拖动
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    // 手指相对这一行原始位置的纵向偏移
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    /**
     * 队列上方的头部占了几个 item。
     *
     * [androidx.compose.foundation.lazy.LazyListLayoutInfo.visibleItemsInfo] 里的
     * `index` 算的是 **LazyColumn 的 item 下标**，而不是队列里的第几首 ——
     * 上面还有"正在播放"那一块。差一位就会拖错行，所以这里显式记下来。
     */
    val headerCount = if (state.currentSong != null) 3 else 0

    fun clearDrag() {
        draggingIndex = null
        dragOffsetY = 0f
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
                    text = "长按任意一首可以拖动排序，顺序立刻生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        itemsIndexed(items = state.queue, key = { _, song -> "queue-${song.key}" }) { index, song ->
            val dragging = draggingIndex == index

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 拖动中的行浮在最上层，否则它会被别的行盖住，用户看不见自己拖的是谁
                    .then(
                        if (dragging) {
                            Modifier
                                .zIndex(1f)
                                .shadow(8.dp)
                        } else {
                            Modifier
                        }
                    )
                    .graphicsLayer {
                        translationY = if (dragging) dragOffsetY else 0f
                        // 略微放大，给一个"被拿起来了"的视觉
                        scaleX = if (dragging) 1.02f else 1f
                        scaleY = if (dragging) 1.02f else 1f
                    }
                    .pointerInput(index, state.queue.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetY = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragCancel = { clearDrag() },
                            onDragEnd = { clearDrag() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y

                                val info = listState.layoutInfo
                                val rowLayouts = info.visibleItemsInfo.mapNotNull { item ->
                                    val queueIndex = item.index - headerCount
                                    if (queueIndex !in state.queue.indices) {
                                        null
                                    } else {
                                        RowLayout(queueIndex, item.offset, item.offset + item.size, item.size)
                                    }
                                }
                                val dragged = rowLayouts.firstOrNull { it.index == index }
                                if (dragged == null) return@detectDragGesturesAfterLongPress

                                val decision = queueDragTarget(
                                    rows = rowLayouts,
                                    draggingIndex = index,
                                    draggedCenter = dragged.offset + dragOffsetY + dragged.size / 2f
                                )
                                if (decision != null && decision.to != index) {
                                    onDragIndex(index, decision.to)
                                    // 换位后被拖行的基准位置挪了，把偏移补回去，
                                    // 否则它会在手指底下"跳"一整行
                                    dragOffsetY += decision.compensation
                                    draggingIndex = decision.to
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }

                                // 拖到列表边缘时自动滚动：否则长队列只能重排眼前这几屏，
                                // 想要的那首在很下面时就完全够不着
                                val viewport = info.viewportStartOffset to info.viewportEndOffset
                                val center = dragged.offset + dragOffsetY + dragged.size / 2f
                                if (center < viewport.first + EDGE_ZONE_PX) {
                                    scope.launch { listState.scrollBy(-EDGE_SCROLL_PX) }
                                } else if (center > viewport.second - EDGE_ZONE_PX) {
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
                    onClick = { onPlayIndex(index) },
                    onPlayNext = { onPlayIndex(index) },
                    onRemove = { onRemoveIndex(index) },
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
                    modifier = if (dragging) {
                        // 拖动时把整行抬亮一点：缩放之外再加一层底色，
                        // 在浅色主题下这层差别比阴影更容易看出来
                        Modifier
                    } else {
                        // 别的行在拖动期间压暗，视线自然落到被拖的那一行
                        Modifier.alpha(if (draggingIndex != null) 0.55f else 1f)
                    }
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
 * 只保留拖动判算真正用到的四个量：下标、顶边、底边、高度。
 * 之所以不直接传 [androidx.compose.foundation.lazy.LazyListItemInfo]，
 * 是因为那样这个函数就没法在 JVM 单测里跑了 —— 而换位判算恰恰是
 * "看起来对、实际会跳位"的高发区，必须能离线验。
 */
internal data class RowLayout(
    val index: Int,
    val offset: Int,
    val end: Int,
    val size: Int
) {
    val center: Float get() = (offset + end) / 2f
}

/** 换位判算的结果。[compensation] 是换位后要加回拖动偏移的像素数。 */
internal data class DragDecision(val to: Int, val compensation: Float)

/**
 * 算出被拖行现在该落到谁的位置。
 *
 * ## 判据：只看紧邻的下一行 / 上一行，且比的是**中心线**
 *  - 为什么只看相邻行：一次只挪一格。跨过头了就停在原地，等下一次 onDrag 再推进 ——
 *    一次跳到第三格会让行在手指底下瞬移，反而更跟不住。
 *  - 为什么比中心线：若按"手指压在谁身上"判定，手指停在两行边界时，
 *    重排后它可能落到另一边，列表就会一格一格地抽搐。
 *  - 为什么必须是**紧邻**的：早期版本把"所有中心在拖动中心上方的行"都算成目标，
 *    结果原地不动（第 2 行中心 250）也会被判成"跨过了第 0 行"（中心 50）而乱换位。
 *
 * ## compensation 的符号：往下拖是**负数**
 * 换位后，被拖行的**基准位置** [RowLayout.offset] 会挪到新下标对应的位置。
 * 往下换时基准变大，而行必须留在手指底下不动，于是 `基准 + 偏移` 要保持不变 ——
 * 偏移只能**变小**。所以往下的补偿是被拖行高度的负值，往上是正值。
 * 符号写反的表征是：拖动时行会朝相反方向窜一大截，比不写补偿更糟。
 *
 * 返回 null 表示"还没跨过相邻行的中心"，此时不该换位。
 */
internal fun queueDragTarget(
    rows: List<RowLayout>,
    draggingIndex: Int,
    draggedCenter: Float
): DragDecision? {
    val dragged = rows.firstOrNull { it.index == draggingIndex } ?: return null
    val next = rows.firstOrNull { it.index == draggingIndex + 1 }
    val prev = rows.firstOrNull { it.index == draggingIndex - 1 }

    val target = when {
        // 往下拖：中心越过下一行才换位
        next != null && draggedCenter > next.center -> next
        // 往上拖：中心越过上一行才换位
        prev != null && draggedCenter < prev.center -> prev
        else -> return null
    }

    val movedDown = target.index > draggingIndex
    val compensation = if (movedDown) -dragged.size.toFloat() else dragged.size.toFloat()
    return DragDecision(target.index, compensation)
}
