package com.melody.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.FileNames
import com.melody.player.core.LyricCopyEntry
import com.melody.player.core.LyricCopyGroup
import com.melody.player.core.LyricOrigin
import com.melody.player.core.TimeFormat
import com.melody.player.ui.icons.MelodyIcons

/**
 * 「歌词副本」底部弹层。
 *
 * ## 为什么从设置页搬到这里
 * 设置页里那份清单是**全局**的：它按歌列出磁盘上所有副本，而用户真正会有的念头
 * 从来不是"我要去整理一下歌词目录"，而是"这首歌的歌词怎么不对劲"——
 * 他此刻正看着那一行、或正开着播放页。把入口放在他看到歌的地方
 * （曲库行 ⋮ / 播放页 ⋮ / 多选批量条），就不必先想"这功能在哪一栏"。
 *
 * ## 为什么按歌分组、组内平铺
 * 同一首歌最多有两份副本（用户选定的那份、联网自动匹配的缓存）。按份平铺时
 * 用户看到的是两条互不相干的行，既看不出它们属于同一首歌，也没法表达
 * "这首歌的歌词我都不要了"。所以组头写歌名与合计，组内才是每一份。
 *
 * 设置页那版是"组可折叠"的：那儿是几百行的清单，折叠是必要的；
 * 这里是用户点名的那几首（通常 1~2 首），一共没几行，再套一层折叠只是多一次点击。
 *
 * ## 孤儿的去处
 * 认不回歌曲的副本（歌词索引丢了）没有别的入口 —— 设置页那份"看全部"的清单
 * 这一版已经拆掉。所以它们永远跟着出现，并在组头上写明"只能删"，
 * 而不是变成磁盘上永远清不掉的死角（见 `LyricCopyGroups.restrict`）。
 */
@Composable
fun LyricCopiesSheet(
    /** 已经筛过的那几组（调用方走 `LyricCopyGroups.restrict`）。 */
    groups: List<LyricCopyGroup>,
    /** 用户点名了几首歌（用于"已选 N 首"的措辞；组数可能比它少）。 */
    requestedCount: Int,
    onPreview: (LyricCopyEntry) -> Unit,
    onDelete: (LyricCopyEntry) -> Unit,
    onDeleteGroup: (LyricCopyGroup) -> Unit,
    onDeleteAllShown: () -> Unit,
    onDismiss: () -> Unit
) {
    val entries = groups.flatMap { it.entries }
    MelodyListSheet(
        title = "歌词副本",
        subtitle = lyricCopiesSummary(groups, requestedCount).ifBlank { null },
        onDismiss = onDismiss,
        // 只有一份时"删除以上全部"与那一行自己的删除键是同一件事，不必再来一个
        footer = if (entries.size > 1) {
            {
                SheetFooterButton(
                    text = "删除以上全部副本（${entries.size} 份）",
                    danger = true,
                    onClick = onDeleteAllShown
                )
            }
        } else {
            null
        }
    ) {
        if (entries.isEmpty()) {
            Text(
                text = "这几首现在没有歌词副本。\n\n" +
                    "原文件被删、文件夹授权失效、歌词标签没写进文件里的时候，" +
                    "存在这里的副本就是仅存的一份 —— 这也是它值得留着的原因。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)
            )
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                groups.forEach { group ->
                    LyricCopyGroupBlock(
                        group = group,
                        onPreview = onPreview,
                        onDelete = onDelete,
                        onDeleteGroup = { onDeleteGroup(group) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** 一首歌：组头（歌名 + 合计 + 全部删除）＋ 它的每一份副本。 */
@Composable
private fun LyricCopyGroupBlock(
    group: LyricCopyGroup,
    onPreview: (LyricCopyEntry) -> Unit,
    onDelete: (LyricCopyEntry) -> Unit,
    onDeleteGroup: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SheetPad, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MelodyIcons.Lyrics,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                // 认不回歌曲的那组不上主色：它不是"你的某一首歌"，是一堆待处理的文件
                tint = if (group.songKey == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = lyricCopyGroupSubtitle(group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (group.entries.size > 1) {
                TextButton(onClick = onDeleteGroup) { Text("全部删除") }
            }
        }
        if (group.songKey == null) {
            Text(
                text = "这些副本认不回是哪首歌（歌词索引丢失），没法预览关联，只能删掉。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = EntryInset, end = SheetPad, bottom = 6.dp)
            )
        }
        group.entries.forEach { entry ->
            LyricCopyEntryRow(
                entry = entry,
                onPreview = { onPreview(entry) },
                onDelete = { onDelete(entry) }
            )
        }
    }
}

/** 一份副本：点整行看正文，右侧的垃圾桶删掉这一份。 */
@Composable
private fun LyricCopyEntryRow(
    entry: LyricCopyEntry,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview)
            .padding(start = EntryInset, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = lyricCopySubtitle(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        IconAction(
            imageVector = MelodyIcons.Delete,
            contentDescription = "删除这份歌词副本",
            onClick = onDelete,
            iconSize = 20.dp,
            touchSize = 40.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 弹层里文字的左边界。与 [MelodyActionSheet] 的动作行同一套栅格（22dp）。 */
private val SheetPad = 22.dp

/**
 * 组内条目的左边界：组头文字的位置（22 + 21 + 18），比组头的图标再进一格 ——
 * 缩进才看得出"这几行属于上面那首歌"。
 */
private val EntryInset = 61.dp

/**
 * 弹层副标题：一共几份、占多大，顺带说明其中几份是随时可再取的缓存。
 *
 * 点名的歌一首都没有副本时返回空串（调用方据此不画副标题）——
 * 那儿已经有一整段空状态说明，再顶一行"共 0 份"是重复。
 */
internal fun lyricCopiesSummary(groups: List<LyricCopyGroup>, requestedCount: Int): String {
    val entries = groups.flatMap { it.entries }
    if (entries.isEmpty()) return ""
    return buildString {
        if (requestedCount > 1) append("已选 $requestedCount 首 · ")
        append("共 ${entries.size} 份 · ")
        append(FileNames.size(entries.sumOf { it.sizeBytes }))
        val auto = entries.count { it.autoMatched }
        // 自动匹配那份删了还会再取回来，和"选定"那份不是一回事，得说清
        if (auto > 0) append(" · 含 $auto 份自动匹配缓存")
    }
}

/** 组头副标题：几份 · 多大 · 最近一次是什么时候存的。 */
internal fun lyricCopyGroupSubtitle(group: LyricCopyGroup): String = buildString {
    append("${group.count} 份 · ")
    append(FileNames.size(group.sizeBytes))
    append(" · ")
    append(TimeFormat.ago(group.latestSavedAtSec))
}

/** 一份副本的副标题：怎么来的（联网的还要写清是哪一家）· 多大 · 什么时候存的。 */
internal fun lyricCopySubtitle(entry: LyricCopyEntry): String = buildString {
    append(
        when (entry.origin) {
            LyricOrigin.FILE -> "导入的 .lrc"
            LyricOrigin.ONLINE -> if (entry.autoMatched) "联网自动匹配" else "联网获取"
            LyricOrigin.ARCHIVED -> "归档时的快照"
            null -> "来源未知"
        }
    )
    // 两家来源的曲库不同，管理副本时要能看出这份到底是哪来的
    entry.container?.takeIf { it.isNotBlank() }?.let {
        append(" · ")
        append(it)
    }
    append(" · ")
    append(FileNames.size(entry.sizeBytes))
    append(" · ")
    append(TimeFormat.ago(entry.savedAtSec))
}
