package com.melody.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.ui.icons.MelodyIcons

/**
 * 弹窗里的一个动作。
 *
 * [subtitle] 不是装饰：这一层菜单里有一半的操作**光看名字分不出后果**——
 * 「隐藏这首」到底删不删文件？「归档到 App 库」会不会动我的原文件？
 * 一句灰色小字把边界说清楚，比事后在设置页里发现少了东西好得多。
 *
 * [danger] 用来标出会**丢东西**的动作（删副本、移出列表）。它不是「危险」的装饰色，
 * 而是一个承诺：标了红的都是"做了就回不来"的，没标红的都能撤销。
 *
 * [section] 连续相同的值会被归到同一组，组标题只画一次。分组是这个菜单从
 * "一长串看不懂的列表"变成"能扫一眼的东西"的关键 —— 十二个平铺的菜单项，
 * 找一个操作得逐个读过去。
 */
data class SheetAction(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val section: String? = null,
    /** 放在最后一位，这样调用处可以写成 `SheetAction(icon, "标题") { 动作 }`。 */
    val onClick: () -> Unit
)

/**
 * 模态底部弹窗。
 *
 * ## 为什么把下拉菜单换成它
 * 下拉菜单的宽度由最长的那个词决定，条目一多就变成贴边的一条窄缝：文字被省略号截断、
 * 越过屏幕高度还得内部滚动，而它的锚点是那一行的 ⋮ —— 用户点开会先愣一下"这是哪首歌的菜单"。
 * 底部弹窗有整屏宽度、能分组的组标题、能带副标题，还能把封面与歌名摆在顶上，
 * 一打开就知道"我现在是在对这首歌做什么"。
 *
 * ## 为什么点完不播退场动画
 * 常见写法是 `sheetState.hide()` 之后再回调关闭，但那要等一个协程 ——
 * 而有些动作（隐藏、移出列表）会让这一行**当场从列表里消失**，
 * 挂在这一行上的弹窗连同它的协程作用域一起被销毁，回调就永远不会执行，
 * 调用方那个 `menuOpen` 标志会卡在 true 上，下次这一行被复用时弹窗自己又冒出来。
 * 所以这里是同步的：先清标志、再跑动作，一步到位。
 */
@Composable
fun MelodyActionSheet(
    title: String,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerArt: (@Composable () -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        // 比默认的 surfaceContainerLow 再亮一档：弹窗压在深色列表上时，
        // 两者的明度差得够开才看得出"这是一层浮起来的东西"
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        // ------------------------------------------------------------ 抬头
        // 歌名 + 歌手 + 封面：菜单里的每一项都是对"这一首"做的，
        // 把对象摆在最上面，用户就不用回头去确认自己点的是哪一行
        if (title.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                headerArt?.let { art ->
                    art()
                    Spacer(Modifier.width(14.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // ------------------------------------------------------------ 动作
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(top = 4.dp, bottom = 12.dp)
        ) {
            var shownSection: String? = null
            actions.forEach { action ->
                if (action.section != null && action.section != shownSection) {
                    SheetSectionHeader(action.section)
                }
                shownSection = action.section
                SheetActionRow(
                    action = action,
                    onClick = {
                        // 先清标志（弹窗当场消失），再跑动作：
                        // 反过来的话，让这一行消失的动作会把弹窗连同协程一起拆掉
                        onDismiss()
                        action.onClick()
                    }
                )
            }
        }
    }
}

/**
 * 只讲一件事的说明弹层。
 *
 * 与 [MelodyActionSheet] 分开写，是因为两者的内容形态根本不是一种东西：那个是
 * 「一串并列的动作」（每项一个图标、一句话、一个后果），这个是「一段连续的文字」。
 * 硬塞进同一个函数，就得往动作列表里塞一个假的"正文项"，而分组、副标题、
 * danger 色这些概念对一段说明文字全是噪音。
 *
 * ## 为什么设置页的说明要从"就地展开"改成它
 * 就地展开会把整页往下顶 —— 说明在卡片里的位置是不定的，展开后它下面的开关、
 * 按钮全都跟着挪一格，用户点完还要重新找刚才那一行；几段长文案同时展开时，
 * 设置页会长得没法看。弹层是**独立的一层**：页面纹丝不动，读完划走就回到原处。
 * 这和曲库行的菜单（[MelodyActionSheet]）是同一套交互，用户不用再学第二种。
 *
 * 长文给高度上限并让它内部滚动：一篇几千字的说明不该把整屏占满，
 * 那是"弹出来一堵墙"，不是"看一下说明"。
 */
@Composable
fun MelodyInfoSheet(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = MelodyIcons.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 16.dp)
            )
            // 底部这一段留白挂在滚动区**外面**：滚到底时不至于贴着导航栏，
            // 也不会因为内容短就把这段留白也滚走
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

/** 分组标题。用主色而不是灰色：它是这一屏的目录，不是又一条说明文字。 */
@Composable
private fun SheetSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun SheetActionRow(action: SheetAction, onClick: () -> Unit) {
    val titleColor = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        action.danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val iconTint = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        action.danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = action.enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            modifier = Modifier.size(21.dp),
            tint = iconTint
        )
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor
            )
            if (action.subtitle != null) {
                Text(
                    text = action.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (action.enabled) 1f else 0.38f
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
