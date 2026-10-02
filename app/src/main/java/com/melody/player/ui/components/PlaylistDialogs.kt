package com.melody.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.melody.player.core.Playlist
import com.melody.player.core.Song
import com.melody.player.ui.icons.MelodyIcons

/**
 * 歌单命名对话框（新建与改名共用）。
 *
 * 确认按钮的可用状态**跟着输入走**：空名字时置灰，而不是让人点了之后弹一句
 * "名字不能为空" —— 同一个对话框，新建和改名都走这里。
 */
@Composable
fun PlaylistNameDialog(
    title: String,
    initialName: String = "",
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("歌单名") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 「加入歌单」：挑一个已有歌单，或顺手新建一个。
 *
 * 已经在歌单里的那几行打勾但**仍然可点** —— 用户可能想把同一首歌放进好几个歌单，
 * 这里给的是"再放一份"。真要去掉请在歌单视图里用「从歌单移除」，
 * 免得在这里搞出一个"点一下就是取消"的隐形开关。
 */
@Composable
fun PlaylistPickerDialog(
    song: Song,
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    PlaylistPickerDialog(
        playlists = playlists,
        header = song.title,
        createTitle = "新建歌单并加入「${song.title}」",
        alreadyIn = { item -> song.key in item.songKeys },
        onPick = onPick,
        onCreateAndAdd = onCreateAndAdd,
        onDismiss = onDismiss
    )
}

/**
 * 批量「加入歌单」：把一批曲目放进同一个歌单。
 *
 * 和单曲版共用同一份列表实现，只把三处不同的地方参数化：标题、勾判定、
 * 新建时的措辞。复制一份歌单列表 UI 的话，下次改样式就得记得改两处 ——
 * 这种"看起来只是复制一下"的重复最容易漏。
 */
@Composable
fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    header: String,
    createTitle: String,
    alreadyIn: (Playlist) -> Boolean,
    onPick: (Playlist) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        PlaylistNameDialog(
            title = createTitle,
            confirmLabel = "创建并加入",
            onConfirm = { name ->
                creating = false
                onCreateAndAdd(name)
            },
            onDismiss = { creating = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入歌单") },
        text = {
            Column {
                Text(
                    text = header,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))

                if (playlists.isEmpty()) {
                    Text(
                        text = "还没有歌单。点下面的「新建歌单」，建好之后会自动放进去。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Box(modifier = Modifier.heightIn(max = 280.dp)) {
                        LazyColumn {
                            itemsIndexed(items = playlists, key = { _, item -> item.id }) { _, item ->
                                val inside = alreadyIn(item)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(item) }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = MelodyIcons.Playlist,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.songKeys.size} 首",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (inside) {
                                        Icon(
                                            imageVector = MelodyIcons.Check,
                                            contentDescription = "已在这个歌单里",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { creating = true }) {
                Icon(
                    imageVector = MelodyIcons.PlaylistAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (playlists.isEmpty()) "新建歌单" else "新建歌单并加入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}