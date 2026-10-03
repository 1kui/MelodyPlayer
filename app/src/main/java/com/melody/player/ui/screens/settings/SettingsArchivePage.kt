package com.melody.player.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.ArchivedEntry
import com.melody.player.core.TimeFormat
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.SettingsActions

/**
 * 已归档曲目最多逐条展示多少首（展开列表时）。
 * 整段是一个 item，归档几百首时不能一口气铺开。
 */
private const val ARCHIVE_LIST_LIMIT = 40

/**
 * 「App 音乐库」二级页。
 *
 * 「取消归档」的二次确认（[pendingUnarchive]）从容器搬到了这一页自己手里：
 * 它是这一页独有的状态，挂在页面顶层已经足够 —— 它**不**跨页，
 * 而当初要求"状态提在页面顶层"是为了对付 LazyColumn 回收行导致的弹层消失，
 * 这里的状态本来就在 LazyColumn 外面。
 */
@Composable
internal fun SettingsArchivePage(
    state: PlayerUiState,
    actions: SettingsActions,
    contentPadding: PaddingValues,
    onHelp: (HelpTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }
    var pendingUnarchive by remember { mutableStateOf<ArchivedEntry?>(null) }
    // 折叠状态：默认收起，点标题行才展开。这一页本来就长，列表默认铺开很难受
    var archivedExpanded by rememberSaveable { mutableStateOf(false) }

    pendingUnarchive?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingUnarchive = null },
            title = {
                Text(
                    text = "取消「${entry.title}」的归档？",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Text(
                    "会删掉 App 音乐库里的这份副本（${formatBytes(entry.sizeBytes)}）和归档时留下的" +
                        "歌词快照，并释放这部分空间。\n" +
                        "你的原文件不会被删：取消之后，它会重新出现在曲库列表里" +
                        "（之前因为 App 库里有副本，列表里只显示副本那一行）。\n" +
                        "注意：如果这首歌的原文件已经不在设备上了，这份副本就是仅存的一份 —— " +
                        "删掉之后不可恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUnarchive = null
                        actions.unarchive(entry)
                    }
                ) { Text("取消归档", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnarchive = null }) { Text("保留") }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除 App 音乐库？") },
            text = {
                Text(
                    "会删掉 App 专属目录里的 ${state.archivedCount} 个音频文件" +
                        "（共 ${formatBytes(state.archivedBytes)}）和它们的歌词副本。" +
                        "如果某些歌的原文件你已经删了，这些副本就是仅存的一份 —— 删掉之后不可恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        actions.clearArchive()
                    }
                ) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        item(key = "app-library") {
            SectionCard(title = "App 音乐库") {
                SettingRow(
                    icon = MelodyIcons.Archive,
                    title = "已归档",
                    subtitle = "复制到 App 专属目录，清理工具不会动这里",
                    trailing = {
                        Text(
                            text = if (state.archivedCount > 0) {
                                "${state.archivedCount} 首 · ${formatBytes(state.archivedBytes)}"
                            } else {
                                "还没有"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.archivedCount > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
                if (state.archiving) {
                    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 10.dp)) {
                        LinearProgressIndicator(
                            progress = { state.archiveProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "正在归档 ${state.archiveDone}/${state.archiveTotal}" +
                                    state.archiveLabel.takeIf { it.isNotBlank() }
                                        ?.let { " · $it" }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = actions.cancelArchive) { Text("取消") }
                        }
                    }
                } else {
                    RowDivider()
                    ActionRow {
                        ChipButton(
                            text = "一键归档全部",
                            icon = MelodyIcons.Archive,
                            onClick = actions.archiveAll,
                            modifier = Modifier.weight(1f)
                        )
                        ChipButton(
                            text = "清除归档",
                            icon = MelodyIcons.Delete,
                            onClick = { if (state.archivedCount > 0) confirmClear = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (state.archivedEntries.isNotEmpty()) {
                    RowDivider()
                    CollapseRow(
                        icon = MelodyIcons.Archive,
                        title = "已归档曲目",
                        subtitle = if (archivedExpanded) {
                            "收起列表"
                        } else {
                            "点这一行展开：可单独取消某一首的归档"
                        },
                        trailing = "${state.archivedEntries.size} 首",
                        expanded = archivedExpanded,
                        onToggle = { archivedExpanded = !archivedExpanded }
                    ) {
                        val shown = state.archivedEntries.take(ARCHIVE_LIST_LIMIT)
                        shown.forEach { entry ->
                            ArchivedRow(
                                entry = entry,
                                onUnarchive = { pendingUnarchive = entry }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = RowInset),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        if (state.archivedEntries.size > ARCHIVE_LIST_LIMIT) {
                            NoteText(
                                "只列出前 $ARCHIVE_LIST_LIMIT 首，共 ${state.archivedEntries.size} 首；" +
                                    "其余用上面的「清除归档」整库清掉。"
                            )
                        }
                        NoteText(
                            "「取消归档」只删 App 库里的那份副本；原文件不会被删，" +
                                "随后会重新出现在曲库里。"
                        )
                    }
                }

                FoldableHelp(
                    HelpTopic(
                        label = "归档是怎么回事、原文件会被删吗",
                        text = "「一键归档全部」会把曲库里的歌复制到 App 专属目录" +
                            "（Android/data/com.melody.player/files/library），归档时顺手把歌词写进文件的" +
                            "内嵌标签 —— 歌词与歌曲从此就是同一个文件，重装、换手机、拷到别处都不会丢。\n" +
                            "原文件不会被删除，也不会被隐藏：同一首歌只保留一行 —— 有 App 库副本时显示副本，" +
                            "副本哪天不在了（被清理工具删掉或自己删了），原文件那一行会自动回到列表。" +
                            "真的不想看到某首歌，请在该行点 ⋮ →「隐藏这首」。"
                    )
                ) { onHelp(it) }
            }
        }
    }
}

/**
 * App 音乐库里已归档的一行 —— 右侧「取消归档」只处理这一首。
 *
 * 与「清除归档」的区别是量级：整库清除是「我不想要这个功能了」，
 * 单首取消是「这一首我不想留在 App 库里」。后者在试听 KWM 解密的成果时最常用。
 */
@Composable
private fun ArchivedRow(entry: ArchivedEntry, onUnarchive: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPad, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = MelodyIcons.Archive,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    entry.artist?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
                    append(formatBytes(entry.sizeBytes))
                    append(" · ")
                    append(TimeFormat.ago(entry.archivedAtSec))
                    if (entry.lyricsEmbedded) append(" · 含内嵌歌词")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onUnarchive) { Text("取消归档") }
    }
}
