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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.kwm.KwmFile
import com.melody.player.core.kwm.KwmFiles
import com.melody.player.ui.components.IconAction
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.SettingsActions

/**
 * 待解密列表最多逐条展示多少个。整段是 LazyColumn 里的**一个 item**，
 * KWM 目录动辄上百个文件，一口气铺开会让这一页卡顿。超出的部分用「一键解密全部」兜。
 */
private const val KWM_LIST_LIMIT = 40

/**
 * 解密结果明细最多列多少条。批量解密 200 首时，200 行「已解密并归档」没有信息量。
 */
private const val KWM_RESULT_LIMIT = 20

/** 「KWM音乐解密」二级页。 */
@Composable
internal fun SettingsKwmPage(
    state: PlayerUiState,
    actions: SettingsActions,
    contentPadding: PaddingValues,
    onHelp: (HelpTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClearKwm by remember { mutableStateOf(false) }

    if (confirmClearKwm) {
        AlertDialog(
            onDismissRequest = { confirmClearKwm = false },
            title = { Text("清空待解密列表？") },
            text = {
                Text(
                    "只会清掉列表里这 ${state.kwmFiles.size} 项记录。" +
                        "你的 .kwm 源文件不动，已经解密归档到 App 音乐库的歌也不动 —— " +
                        "下次重新扫描一下就能把列表找回来。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearKwm = false
                        actions.kwmClearList()
                    }
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearKwm = false }) { Text("取消") }
            }
        )
    }

    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        item(key = "kwm") {
            SectionCard(title = "KWM音乐解密") {
                val doneKeys = KwmFiles.doneKeys(state.kwmFiles, state.kwmDoneKeys)
                val pending = state.kwmFiles.size - doneKeys.size

                SettingRow(
                    icon = MelodyIcons.LockOpen,
                    title = "待解密文件",
                    subtitle = buildString {
                        append(".kwm 是加密音频容器，解密后自动归档进 App 音乐库")
                        state.kwmFolderName?.takeIf { it.isNotBlank() }
                            ?.let { append("；已授权文件夹「$it」") }
                    },
                    trailing = {
                        Text(
                            text = when {
                                state.kwmFiles.isEmpty() -> "还没有"
                                pending > 0 -> "$pending 个待处理"
                                else -> "都已处理"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (pending > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
                RowDivider()

                if (state.kwmWorking) {
                    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 10.dp)) {
                        // 进度按**件数**算：不再有转码阶段，一份文件就是一个刻度
                        LinearProgressIndicator(
                            progress = { state.kwmProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buildString {
                                    append(
                                        "正在解密 " +
                                            "${(state.kwmDone + 1).coerceAtMost(state.kwmTotal)}" +
                                            "/${state.kwmTotal}"
                                    )
                                    if (state.kwmLabel.isNotBlank()) append(" · ${state.kwmLabel}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = actions.kwmCancel) { Text("取消") }
                        }
                    }
                } else {
                    // 找文件的两条路固定在左，选文件 + 重扫在右；两者都是「往列表里加东西」
                    ActionRow {
                        ChipButton(
                            text = "扫描设备",
                            icon = MelodyIcons.Refresh,
                            onClick = actions.kwmScanDevice,
                            modifier = Modifier.weight(1f)
                        )
                        ChipButton(
                            text = "指定文件夹",
                            icon = MelodyIcons.Folder,
                            onClick = actions.kwmPickFolder,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    ActionRow {
                        ChipButton(
                            text = "选择文件",
                            icon = MelodyIcons.FileMusic,
                            onClick = actions.kwmPickFiles,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.kwmFolderName != null) {
                            ChipButton(
                                text = "重扫文件夹",
                                icon = MelodyIcons.Refresh,
                                onClick = actions.kwmRescanFolder,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }

                    state.kwmScanNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = CardPad, vertical = 10.dp)
                        )
                    }

                    if (state.kwmFiles.isNotEmpty()) {
                        RowDivider()
                        state.kwmFiles.take(KWM_LIST_LIMIT).forEach { file ->
                            KwmFileRow(
                                file = file,
                                done = KwmFiles.doneKeyOf(file) in doneKeys,
                                onDecrypt = { actions.kwmDecrypt(file) },
                                onDecryptAgain = { actions.kwmDecryptAgain(file) },
                                onRemove = { actions.kwmRemove(file.key) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = RowInset),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        if (state.kwmFiles.size > KWM_LIST_LIMIT) {
                            NoteText(
                                "只列出前 $KWM_LIST_LIMIT 个，共 ${state.kwmFiles.size} 个；" +
                                    "其余用「一键解密」一并处理。"
                            )
                        }
                        // 列表下方只放这一行：主操作在左、「清空列表」在右。
                        // 清空的位置固定在这里（原来会随「是否授权过文件夹」在上下两处漂移）
                        ActionRow {
                            ChipButton(
                                text = if (pending > 0) "一键解密（$pending）" else "一键解密",
                                icon = MelodyIcons.LockOpen,
                                onClick = actions.kwmDecryptAll,
                                modifier = Modifier.weight(1f)
                            )
                            ChipButton(
                                text = "清空列表",
                                icon = MelodyIcons.Delete,
                                onClick = { confirmClearKwm = true },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // 结果里失败的排前面：两百行「已解密并归档」里找那两条失败是最烦的
                        val (failed, succeeded) = state.kwmResults.partition {
                            !it.substringAfterLast("：").startsWith("已解密")
                        }
                        val shown = (failed + succeeded).take(KWM_RESULT_LIMIT)
                        if (shown.isNotEmpty()) {
                            RowDivider()
                            Text(
                                text = "解密结果",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = CardPad,
                                    top = 10.dp,
                                    bottom = 4.dp
                                )
                            )
                            shown.forEach { line ->
                                val bad = !line.substringAfterLast("：").startsWith("已解密")
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (bad) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = CardPad, vertical = 2.dp)
                                )
                            }
                            if (state.kwmResults.size > KWM_RESULT_LIMIT) {
                                NoteText("（另有 ${state.kwmResults.size - KWM_RESULT_LIMIT} 条未显示）")
                            }
                        }
                    }
                }

                // 只留「原理 + 怎么找文件」两件真正影响使用的事，一次读完
                FoldableHelp(
                    HelpTopic(
                        label = "解密原理、怎么找文件",
                        text = ".kwm 是一种加密音频容器：文件头 1KB 是明文说明，之后的音频整段用" +
                            "「固定口令 ⊕ 文件密钥」异或加密。解密就是把这段异或回来 —— " +
                            "无损、可逆，不联网、不上传，全程在本机完成。\n" +
                            "三条路找文件：「扫描设备」查系统媒体库最省事，但 .kwm 不是系统认识的" +
                            "媒体类型，Android 11 之后多半扫不到；「指定文件夹」授权音乐目录后递归查找，" +
                            "授权跨重启保留，新系统上最可靠；「选择文件」手动多选，一定可用。\n" +
                            "解密结果落进 App 专属目录并登记进曲库，接着就能播；原 .kwm 不会被删除，" +
                            "也不会被改动。"
                    )
                ) { onHelp(it) }
            }
        }
    }
}

/**
 * 待解密列表里的一行。
 *
 * 已经解过的显示「再解一次」（用户很可能删过副本、想再来一份），没解过的点锁形图标直接解。
 * 右侧垃圾桶只把这一项从列表里去掉，不碰磁盘上的文件 —— 所以不需要二次确认。
 */
@Composable
private fun KwmFileRow(
    file: KwmFile,
    done: Boolean,
    onDecrypt: () -> Unit,
    onDecryptAgain: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPad, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = MelodyIcons.FileMusic,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (done) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    // 有些来源（MediaStore）拿不到大小，宁可不说也别显示成 0 B
                    if (file.sizeBytes > 0L) {
                        append(formatBytes(file.sizeBytes))
                        append(" · ")
                    }
                    append(file.source.label)
                    if (done) append(" · 已解密")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (done) {
            TextButton(onClick = onDecryptAgain) { Text("再解一次") }
        } else {
            IconAction(
                imageVector = MelodyIcons.LockOpen,
                contentDescription = "解密这个文件",
                onClick = onDecrypt,
                iconSize = 20.dp,
                touchSize = 40.dp,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconAction(
            imageVector = MelodyIcons.Delete,
            contentDescription = "从列表里去掉",
            onClick = onRemove,
            iconSize = 20.dp,
            touchSize = 40.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
