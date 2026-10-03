package com.melody.player.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.melody.player.core.TimeFormat
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.SettingsActions

/**
 * 「曲库」二级页。
 *
 * 与首屏那张同名卡片的内容一字不差 —— 拆分只是把它从一长条页面上搬进了独立一页，
 * 所以正文里的说明文字、按钮顺序、条件显示都照搬，不做"顺手优化"：
 * 这一版的验收标准就是"拆分前后逐页看着一样"。
 */
@Composable
internal fun SettingsLibraryPage(
    state: PlayerUiState,
    actions: SettingsActions,
    contentPadding: PaddingValues,
    onHelp: (HelpTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        item(key = "library") {
            SectionCard(title = "曲库") {
                val imported = state.songs.count { it.imported }
                SettingRow(
                    icon = MelodyIcons.MusicNote,
                    title = "曲目总数",
                    subtitle = buildString {
                        append("总时长 ${TimeFormat.durationText(state.totalDurationMs)}")
                        if (imported > 0) append(" · 手动导入 $imported 个文件")
                    },
                    trailing = {
                        Text(
                            text = "${state.songs.size} 首",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                RowDivider()
                ActionRow {
                    ChipButton(
                        text = "重新扫描",
                        icon = MelodyIcons.Refresh,
                        onClick = actions.refresh,
                        modifier = Modifier.weight(1f)
                    )
                    ChipButton(
                        text = "导入文件",
                        icon = MelodyIcons.ImportMusic,
                        onClick = actions.importAudio,
                        modifier = Modifier.weight(1f)
                    )
                }
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Folder,
                    title = "只扫描指定文件夹",
                    subtitle = state.libraryFolderName?.let { "当前限定：$it" }
                        ?: "开启后曲库里只有那个文件夹里的歌；关掉则扫描整机"
                ) {
                    Switch(
                        checked = state.libraryFolderOnly,
                        onCheckedChange = actions.setLibraryFolderOnly
                    )
                }
                RowDivider()
                ActionRow {
                    ChipButton(
                        text = if (state.libraryFolderName == null) "选择文件夹" else "换一个文件夹",
                        icon = MelodyIcons.Folder,
                        onClick = actions.pickLibraryFolder
                    )
                    ChipButton(
                        text = "取消限定",
                        icon = MelodyIcons.Close,
                        onClick = actions.clearLibraryFolder,
                        enabled = state.libraryFolderOnly || state.libraryFolderName != null
                    )
                }
                FoldableHelp(
                    HelpTopic(
                        label = "限定文件夹是怎么扫的",
                        text = "开启后不再读取整机媒体库，只用系统文件选择器授权的那一个文件夹（连子目录一起），" +
                            "所以不需要「音乐和音频」权限，只有这一个目录的读取权。\n" +
                            "逐个文件读取标签会比整机扫描慢一些（大曲库可能要走十几秒），" +
                            "扫描过程中曲库页会显示已经找到多少首。\n" +
                            "手动导入的文件和归档到 App 音乐库的副本不受这个开关影响，它们始终在曲库里。" +
                            "文件夹授权可以被系统在设置里撤销，真被撤销时这里会提示并自动退回整机扫描。"
                    )
                ) { onHelp(it) }
            }
        }
    }
}
