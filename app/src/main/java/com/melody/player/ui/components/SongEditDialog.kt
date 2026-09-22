package com.melody.player.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melody.player.core.Song

/**
 * 「编辑歌曲信息」对话框：改歌名 / 歌手 / 专辑。
 *
 * 三个字段都以**当前值**预填（改过的是改后的值，没改过的是文件标签里的原值），
 * 用户清空歌手/专辑等于"这首歌就是没有歌手"——界面上显示「未知歌手」，
 * 不是把字段留在原值上不动。
 *
 * 「恢复原始信息」只在**确实改过**时出现：撤销数据存在编辑记录自己身上，
 * 撤完是文件标签里的原值，不是"清空三个框"。
 */
@Composable
fun SongEditDialog(
    song: Song,
    hasOriginal: Boolean,
    onSave: (title: String, artist: String, album: String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist.orEmpty()) }
    var album by remember { mutableStateOf(song.album.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑歌曲信息") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = song.displayName ?: song.uri.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("歌名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("歌手") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("专辑") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        dismissButton = {
            if (hasOriginal) {
                TextButton(onClick = onReset) { Text("恢复原始信息") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onSave(title, artist, album) }
            ) { Text("保存") }
        }
    )
}
