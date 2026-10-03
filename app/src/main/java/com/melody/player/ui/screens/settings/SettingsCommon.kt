package com.melody.player.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melody.player.core.PlayMode
import com.melody.player.core.SleepTimer
import com.melody.player.core.online.RegionPing
import com.melody.player.ui.icons.MelodyIcons
import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------------------
// 这一份是设置页（根页 + 九个二级页）共用的排版零件。
//
// 拆文件之前它们和页面内容挤在同一个 1835 行的文件里；拆完之后**必须**抽到一处，
// 否则每搬一个分区出去都得把用到的零件复制一遍 —— 那种复制是"改一处忘一处"的起点：
// 卡片内边距有两个来源、折叠动画有两种曲线，用户看到的就是"这一页跟那一页不太一样"。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 排版栅格
//
// 设置页原来每个区块各写各的 16.dp / 18.dp / 12.dp，凑在一起就是「乱」的来源。
// 这里收成四个常量，页面上所有卡片、按钮行、展开子行的边距都从这里取：
//
//   卡片内左右内边距 = CardPad            展开子行的文字左边界 = RowInset
//   段内卡片间距     = CardGap            段与段之间再叠一个 GroupGap
// ---------------------------------------------------------------------------

/** 卡片内左右内边距的唯一来源。 */
internal val CardPad = 16.dp

/** 同一段内两张卡片之间的留白。 */
internal val CardGap = 12.dp

/** 换段时的额外留白（叠在 [CardGap] 之上，实际留给 28.dp）。 */
internal val GroupGap = 16.dp

/**
 * 卡片内「图标 + 文字」行的文字左边界：16(卡片内边距) + 22(图标) + 16(间距)。
 *
 * 展开的子行（已归档曲目、待解密文件）缩进到这条线上，与父行的文字对齐 ——
 * 子行各写各的 32dp/66dp 会看着像两套栅格。
 */
internal val RowInset = 54.dp

/**
 * 段与段之间的留白。
 *
 * LazyColumn 的 `verticalArrangement` 已经给 [CardGap]，这里再叠一个 [GroupGap]，
 * 于是「同段 12 / 跨段 28」两档间距一眼能分辨 —— 靠间距分组比再加一行段标题干净。
 */
@Composable
internal fun GroupSpacer() {
    Spacer(Modifier.height(GroupGap))
}

/** 卡片内的分隔线，左边距与行内图标对齐。 */
@Composable
internal fun RowDivider(start: Dp = CardPad) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** 卡片里的一行动作按钮：统一内外边距，两列等宽。 */
@Composable
internal fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPad, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** 卡片里的一句话说明，行距与字幕一致，避免和按钮行黏在一起。 */
@Composable
internal fun NoteText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = CardPad, vertical = 8.dp)
    )
}

/** 字节数的人类可读写法，用于 App 音乐库与封面缓存的占用统计。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

@Composable
internal fun <T> ChipFlow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    // 用两行流式布局排布，避免在窄屏上被挤到换行错位
    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 4.dp)) {
        options.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        label = { Text(labelOf(option)) },
                        modifier = Modifier.weight(1f),
                        leadingIcon = if (option == selected) {
                            {
                                Icon(
                                    imageVector = MelodyIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            null
                        }
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 胶囊右侧的一条小标注（延迟数字、状态词）。
 *
 * [warn] 不是"颜色更好看"的意思，而是一个承诺：标红的都是**用户需要处理**的状态。
 * 这里只有「无法连接」配得上它 —— 「测速中」会自己变，一闪一红只会让人以为出错了。
 */
internal data class ChipBadge(val text: String, val warn: Boolean = false)

/**
 * 一个地区在选项上要显示的延迟标注；`null` 表示这一项还没测过。
 *
 * 四种状态各有各的文案，别合并成一条横线：还没轮到 ≠ 正在测 ≠ 连不上 ——
 * 合并之后用户分不出"该等一下"和"该换个区"。
 *
 * 只有「连不上」带 [ChipBadge.warn]：它是唯一需要用户**做点什么**的状态
 * （换个区，或者过一会儿重测）；把"测速中"也标成警示色，测速时一闪一闪只会干扰。
 *
 * 抽成纯函数放这里（而不是直接写在组合项里）是这一页的老规矩：
 * 它写错了**不崩**，只是屏幕上少一个数字，靠真机肉眼很难复现，所以必须能进 JVM 单测。
 */
internal fun regionPingBadge(ping: RegionPing?): ChipBadge = when (ping) {
    null -> ChipBadge("未测速")
    RegionPing.Testing -> ChipBadge("测速中…")
    is RegionPing.Ok -> ChipBadge("${ping.ms} ms")
    RegionPing.Unreachable -> ChipBadge("无法连接", warn = true)
}

/**
 * 多选版的选择胶囊（[ChipFlow] 是单选）。
 *
 * 与单选版的差别只有两点：传的是选中**集合**而不是单个值，以及选中态全靠勾号表达。
 * 「一个都不选」这种边界不在这里拦 —— 它取决于业务含义（地区全不选等于没得搜，
 * 而别的多选项未必），所以由调用方的 [onToggle] 判断并给出提示。
 *
 * [badgeOf] 给每项挂一条状态标注（目前只有地区用它显示延迟）。
 * 标注画在胶囊**里面**而不是旁边：它描述的是"这个选项现在怎么样"，
 * 分开画就成了两列对不齐的信息，反而要多花一次眼睛去找对应关系。
 */
@Composable
internal fun <T> MultiChipFlow(
    options: List<T>,
    selected: Set<T>,
    labelOf: (T) -> String,
    onToggle: (T) -> Unit,
    badgeOf: ((T) -> ChipBadge?)? = null
) {
    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 4.dp)) {
        options.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { option ->
                    val on = option in selected
                    val badge = badgeOf?.invoke(option)
                    val badgeSlot: (@Composable () -> Unit)? = if (badge != null) {
                        { ChipBadgeLabel(badge) }
                    } else {
                        null
                    }
                    FilterChip(
                        selected = on,
                        onClick = { onToggle(option) },
                        label = {
                            // 单行不换行：换行会让同一行的两个胶囊高低不一，
                            // 而这里的文字都短，省略号是永远用不到的安全网
                            Text(labelOf(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.weight(1f),
                        leadingIcon = if (on) {
                            {
                                Icon(
                                    imageVector = MelodyIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            null
                        },
                        trailingIcon = badgeSlot
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 胶囊里的状态标注。比标签小一档，免得把地区名挤掉。 */
@Composable
private fun ChipBadgeLabel(badge: ChipBadge) {
    Text(
        text = badge.text,
        style = MaterialTheme.typography.labelSmall,
        color = if (badge.warn) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1
    )
}

/**
 * 展开 / 收起的统一动画。
 *
 * 设置页里所有「点一下才铺开」的区域 —— 折叠列表 —— 都从这里取动画，
 * 保证点哪一处的手感都一样：
 *
 *  - 展开用竖向弹簧（dampingRatio 0.7），先冲过去一点点再落回。纯 tween 是「匀速推开」，
 *    在几百行的长列表上会显得拖沓；带一点回弹才有「弹开」的感觉；
 *  - 收起**不弹**（dampingRatio 1f）：收的时候内容在往下缩，回弹会把最后一行推出去又拽回来，
 *    看着像抖动；
 *  - 透明度只跟 110–150ms 一小段，避免「尺寸在动、字还是糊的」；
 *  - `expandFrom` / `shrinkTowards` 都取 Top，展开方向一致地向下长出来。
 *
 * 收起时内容为空、高度自然为 0，所以没展开的卡片里不会留一条空白。
 */
@Composable
internal fun ExpandableContent(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntSize.VisibilityThreshold
            ),
            expandFrom = Alignment.Top
        ) + fadeIn(animationSpec = tween(durationMillis = 140)),
        exit = shrinkVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
                visibilityThreshold = IntSize.VisibilityThreshold
            ),
            shrinkTowards = Alignment.Top
        ) + fadeOut(animationSpec = tween(durationMillis = 110))
    ) {
        // 这里必须套一层 Column —— 不是排版洁癖，是这个 API 的硬约束：
        // AnimatedVisibility 的内容槽只接受**一个**子项，它的 measure policy 是
        //     layout(maxWidth, maxHeight) { placeables.fastForEach { it.place(0, 0) } }
        // 即所有子项都放在 (0,0)、容器高度取子项最大值。直接塞多行进来就会
        // 全部叠在同一位置、卡片只剩一行高（v2.1.1 真机上就是这么炸的）。
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * 折叠箭头的统一写法：方向翻转走弹簧，与展开 / 收起同一条曲线。
 *
 * 不做成「ChevronUp + ChevronDown 两个图标」是因为同一套几何只该有一份 ——
 * 旋转 180° 与换图标视觉等价，却少维护一个图标。角度的回弹比尺寸更安全：
 * 尺寸回弹过头会在卡片底部顶出一条空白，角度回弹只是箭头多转十几度再落回来。
 *
 * 尺寸由调用方给：折叠列表的标题行用 20dp。
 * （说明行不再用它 —— 说明改成弹层之后没有"两个状态"可翻，见 [FoldableHelp]。）
 */
@Composable
internal fun CollapseChevron(expanded: Boolean, size: Dp) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    Icon(
        imageVector = MelodyIcons.ChevronDown,
        contentDescription = if (expanded) "收起" else "展开",
        modifier = Modifier
            .size(size)
            .rotate(angle),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 可折叠列表的标题行。
 *
 * 设置页里有几段列表是「平时用不上、一展开又可能几百行」的（已归档曲目、待解密文件），
 * 默认铺开只会把页面撑得很长。这里统一收成一行：点整行切换，右侧箭头跟着翻转。
 *
 * 三件套各司其职，别互相重复：**标题**说这是什么（区块标题已同名时才改说状态），
 * **副标题**说点开会怎样，**[trailing]** 放数量或占用。
 *
 * 标题行与展开体是一体的：展开动画写在 [ExpandableContent] 里，[content] 只管内容 ——
 * 调用方写成 `CollapseRow(...) { 列表 }` 就自动带上弹性，不会再出现"某一处忘了加动画"。
 */
@Composable
internal fun CollapseRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: String? = null,
    content: @Composable () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = CardPad, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(4.dp))
            CollapseChevron(expanded = expanded, size = 20.dp)
        }
        // 展开体由这一行自己管：调用方只把内容塞进 content，动画不会漏做
        ExpandableContent(visible = expanded) { content() }
    }
}

/**
 * 设置页里一条「读一次就够」的说明。
 *
 * [label] 是一句概括，要能当标题用；[text] 才是全文。两者放在一起是为了让调用处
 * 写成 `FoldableHelp(HelpTopic("...", "...")) { helpTopic = it }` ——
 * 一个对象、一次传递，不会出现"标题改了、弹层里还是旧标题"这种两处不同步。
 */
internal data class HelpTopic(val label: String, val text: String)

/**
 * 折叠……不，是**弹出**来的说明文字。
 *
 * 设置页里有几段「读一次就够」的长文案（归档规则、KWM 解密原理、歌词来源、封面来源）。
 * 原来点一下是就地铺开，问题是：说明在卡片里的深度不一，铺开之后它下面的开关、
 * 按钮**全都被顶下去一格**，用户读完还得重新找刚才那一行；两段同时展开时页面长得没法看。
 * 现在改为弹一层（[com.melody.player.ui.components.MelodyInfoSheet]）：
 * 页面纹丝不动，读完划走就回到原处 —— 与曲库行的菜单是同一套交互。
 *
 * 箭头朝**右**（向下那个箭头是折叠的语义，会被读成"就在这一页铺开"）。
 * 图标从旋转的 [CollapseChevron] 换成固定的右箭头：这里没有"展开/收起"两个状态，
 * 一直在转的箭头只会让人以为下面还藏着东西。
 */
@Composable
internal fun FoldableHelp(topic: HelpTopic, onOpen: (HelpTopic) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(topic) }
            .padding(horizontal = CardPad, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = MelodyIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = topic.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = MelodyIcons.ChevronDown,
            contentDescription = "查看说明",
            // ChevronDown 转过 -90° 就是向右的箭头（同一个几何只维护一份）
            modifier = Modifier
                .size(16.dp)
                .rotate(-90f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 「当前这一页」的滚动状态，由 [com.melody.player.ui.screens.SettingsContent] 提供。
 *
 * 走 CompositionLocal 而不是给九个页面各加一个参数：这九个页面的签名里已经躺着
 * `state / actions / contentPadding / openHelp` 四样东西，再加一样就要改九份调用；
 * 而它本来就是**页面的容器**才知道的事情 —— 页面自己不该关心"我的滚动位置要活多久"。
 *
 * 不提供时取 `null`，[SettingsPageList] 会退回自己 `remember` 一份（单独预览一页时用）。
 */
internal val LocalSettingsListState = staticCompositionLocalOf<LazyListState?> { null }

/**
 * 设置页每一页（首屏与九个二级页）的列表骨架。
 *
 * 抽出来只为一件事：**首屏和二级页的栅格必须一模一样**。用户从首屏点进"曲库"，
 * 卡片却往左挪了 4dp、或顶上少了一条留白，读起来就是"这是另一个地方"。
 * 九个页面各写一遍 LazyColumn(...) 参数，迟早会有某一页的参数被人顺手改掉。
 *
 * ## 滚动状态为什么从上面来
 * 默认的 `LazyColumn` 会在内部 `rememberLazyListState()` —— 而它随页面一起被销毁：
 * 从首屏点进二级页时 `when (page)` 换了分支，首屏整棵子树离开组合，
 * 它记住的"我滚到第几项"也就没了。返回时列表重建，**必定弹回顶部**。
 * 所以状态由 [SettingsContent] 按页持有并经 [LocalSettingsListState] 下发，
 * 一页一份，切回来还在原处。
 */
@Composable
internal fun SettingsPageList(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState? = LocalSettingsListState.current,
    content: LazyListScope.() -> Unit
) {
    // 没有上层提供时兜一个自己的（例如把一个页面单独拿出来预览）。
    // 必须无条件调用，不能写成 `?: rememberLazyListState()` —— 条件组合会让
    // 插桩后的槽位在不同的组合路径上错位。
    val fallback = rememberLazyListState()
    LazyColumn(
        state = listState ?: fallback,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CardPad,
            end = CardPad,
            top = CardGap,
            // 底部再叠 24dp：列表最后一张卡片贴着迷你条会很挤
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        content = content
    )
}

/** 卡片里的一颗带图标的动作胶囊。 */
@Composable
internal fun ChipButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        modifier = modifier,
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    )
}

/**
 * 播放模式对应的图标。图标**就是当前状态**，所以这张表只有一个来源 ——
 * 播放页那颗循环按钮和设置页「常用」里那一行显示的是同一件事，
 * 各写一份 `when` 迟早会出现"两边图标不一样"。
 */
internal fun playModeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.SEQUENTIAL -> MelodyIcons.PlayOrder
    PlayMode.LIST_LOOP -> MelodyIcons.Repeat
    PlayMode.SINGLE_LOOP -> MelodyIcons.RepeatOne
    PlayMode.SHUFFLE -> MelodyIcons.Shuffle
}

/**
 * 睡眠定时剩余时间的读数（`29:59` / `1:29:00`）。
 *
 * 收的是**流**而不是一个 `Long?` 值：这个读数每秒都在变，值要是从上层当参数传进来，
 * 上面每一层（含整个设置页的 LazyColumn）都会跟着每秒重组一遍。
 * 流在**这里**被消费，重组范围就只剩这一个 `Text`。
 *
 * 没设定时（`null`）时什么都不画 —— 那几行右侧本来就有别的信息，
 * 硬塞一句"未设定"只会让每行都多一段噪音。
 */
@Composable
internal fun SleepCountdownText(remaining: StateFlow<Long?>) {
    val value by remaining.collectAsStateWithLifecycle()
    val ms = value ?: return
    Text(
        text = SleepTimer.remainingLabel(ms),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
    )
}
