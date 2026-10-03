package com.melody.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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

/**
 * 「把内容写进音频文件」的确认框。
 *
 * 刻意做成**先选内容、再二次确认**两步：这个操作会改用户的音频文件，
 * 不可撤销（App 没有原文件备份）。一步到位的按钮最容易让人误点，
 * 而歌词与封面分开勾是因为多数用户只想要其中一样。
 *
 * 它住在**根界面**上，而不是某一个页面里：作用对象由调用方显式传进来
 * （单曲 / 选中的一批 / 当前列表 / 播放页当前这首），
 * 让框自己去猜"用户现在选了什么"必然在单曲入口上出错。
 */
@Composable
fun EmbedTagsDialog(
    count: Int,
    onConfirm: (writeLyrics: Boolean, writeArtwork: Boolean) -> Unit,
    onDismiss: () -> Unit,
    countLabel: String? = null
) {
    var writeLyrics by remember { mutableStateOf(true) }
    var writeArtwork by remember { mutableStateOf(true) }
    var confirmed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把内容写进音频文件？") },
        text = {
            Column {
                Text(
                    text = countLabel ?: "将修改 $count 个音频文件的标签。",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                EmbedOption(
                    checked = writeLyrics,
                    title = "写入歌词",
                    subtitle = "把当前这一份歌词写进内嵌标签",
                    onToggle = { writeLyrics = !writeLyrics }
                )
                EmbedOption(
                    checked = writeArtwork,
                    title = "写入封面",
                    subtitle = "把 App 里存的封面写进文件，别的播放器也能看到",
                    onToggle = { writeArtwork = !writeArtwork }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "只支持 MP3 与 FLAC；文件本身不会被转码，音频数据原样保留。\n\n" +
                        "这一步会改动原文件，且没有备份、无法撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = writeLyrics || writeArtwork,
                onClick = {
                    if (!confirmed) {
                        // 第一次点只把按钮变成"确实要改文件"，第二次才真的动手：
                        // 不可撤销的操作值得多一次确认
                        confirmed = true
                    } else {
                        onConfirm(writeLyrics, writeArtwork)
                    }
                }
            ) { Text(if (confirmed) "确认修改文件" else "下一步") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun EmbedOption(
    checked: Boolean,
    title: String,
    subtitle: String,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
