package com.melody.player.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.melody.player.core.HiddenSongEntry
import com.melody.player.ui.components.MelodyListSheet
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.components.SheetFooterButton
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.SettingsActions

/**
 * 「已隐藏的曲目」二级页。
 *
 * 它本身只有一行入口 —— 真正的清单在弹层里（见 [HiddenSongsSheet]）。
 * 为什么不直接把列表铺在这一页：隐藏可能几百条，铺开之后页面上就没有别的东西了，
 * 而这一页唯一要给的判断是"我到底藏了多少首"。弹层还能给逐首恢复的滚动列表，
 * 页面本身则短到一眼看完。
 */
@Composable
internal fun SettingsHiddenPage(
    state: PlayerUiState,
    actions: SettingsActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val hidden = state.hiddenSongs

    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        item(key = "hidden") {
            SectionCard(title = "已隐藏的曲目") {
                SettingRow(
                    icon = MelodyIcons.EyeOff,
                    title = if (hidden.isEmpty()) {
                        "还没有隐藏任何曲目"
                    } else {
                        "共 ${hidden.size} 首不参与列表与搜索"
                    },
                    subtitle = if (hidden.isEmpty()) {
                        "在曲库列表里点该行的 ⋮ →「隐藏这首」，把不是歌的音频移开"
                    } else {
                        "点这一行逐首恢复（只影响显示，不删文件）"
                    },
                    onClick = if (hidden.isEmpty()) null else ({ sheetOpen = true }),
                    trailing = if (hidden.isEmpty()) {
                        null
                    } else {
                        {
                            Icon(
                                imageVector = MelodyIcons.ChevronDown,
                                contentDescription = "查看已隐藏的曲目",
                                // ChevronDown 转过 -90° 就是向右的箭头（同一个几何只维护一份）
                                modifier = Modifier
                                    .size(18.dp)
                                    .rotate(-90f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }
    }

    // 弹层挂在列表外面：列表里的行会被 LazyColumn 回收，
    // 状态（和弹层）挂在行上，行一没就跟着没了
    if (sheetOpen) {
        HiddenSongsSheet(
            hidden = hidden,
            missingKeys = state.hiddenMissingKeys,
            onUnhide = actions.unhide,
            onRestoreAll = actions.restoreAllHidden,
            onDismiss = { sheetOpen = false }
        )
    }
}

/**
 * 「已隐藏的曲目」底部弹层。
 *
 * 以前是设置页里的一段折叠列表，两个毛病：
 *  1. 它只铺前 30 首（整段只是 LazyColumn 里的一个 item，不能指望它懒加载），
 *     隐藏了几百首时除了"全部恢复"就没法逐首挑；
 *  2. 摊开之后整页被顶长，读完还得重新找那一段在哪。
 * 换成弹层之后这两个都没了：弹层里是**真**的 LazyColumn（要多少有多少，
 * 不必再截断），页面也纹丝不动。
 */
@Composable
private fun HiddenSongsSheet(
    hidden: List<HiddenSongEntry>,
    missingKeys: Set<String>,
    onUnhide: (String) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit
) {
    MelodyListSheet(
        title = "已隐藏的曲目",
        subtitle = if (hidden.isEmpty()) {
            null
        } else {
            "${hidden.size} 首 · 只影响显示，不删文件"
        },
        onDismiss = onDismiss,
        footer = if (hidden.isEmpty()) {
            null
        } else {
            { SheetFooterButton(text = "全部恢复（${hidden.size}）", onClick = onRestoreAll) }
        }
    ) {
        if (hidden.isEmpty()) {
            Text(
                text = "媒体库扫描会把一些不是歌的音频也算进来。\n\n" +
                    "在曲库列表里点该行的 ⋮ →「隐藏这首」，它就不再出现在曲库和搜索结果里" +
                    "（只影响显示，不删文件，也不动已排好的播放队列）。" +
                    "隐藏过的曲目会列在这里，随时可以恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = hidden, key = { it.key }) { entry ->
                    val missing = entry.key in missingKeys
                    SettingRow(
                        icon = MelodyIcons.EyeOff,
                        title = entry.title,
                        // 文件不在设备上要如实说：否则用户按了"恢复"却什么都没回来，
                        // 会以为是恢复没生效，而不是"这个文件本来就没了"
                        subtitle = buildString {
                            entry.artist?.let { append(it) }
                            if (missing) {
                                if (isNotEmpty()) append(" · ")
                                append("文件已不在设备上")
                            }
                        }.ifEmpty { null },
                        trailing = {
                            TextButton(onClick = { onUnhide(entry.key) }) { Text("恢复") }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}
