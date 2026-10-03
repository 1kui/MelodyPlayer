package com.melody.player.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melody.player.core.ArtworkShape
import com.melody.player.core.LyricTextSize
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.SettingsActions
import com.melody.player.ui.theme.AccentTheme
import com.melody.player.ui.theme.ThemeMode

/**
 * 「外观」二级页：主题模式 / 主题色 / 封面形状 / 歌词字号。
 *
 * 四项都是"选一个"的离散档位，所以都用同一套 [ChipFlow]。歌词字号放在这里而不是
 * 单独一页：它和封面形状是同一类东西（都是"界面上某个元素长什么样"），
 * 而"歌词"那页讲的是**内容从哪来**，两件事分开用户才找得到。
 */
@Composable
internal fun SettingsAppearancePage(
    state: PlayerUiState,
    actions: SettingsActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        item(key = "appearance") {
            SectionCard(title = "外观") {
                SettingRow(
                    icon = MelodyIcons.Contrast,
                    title = "主题模式",
                    subtitle = "深色模式会随系统或按此处选择切换"
                )
                ChipFlow(
                    options = ThemeMode.entries.toList(),
                    selected = actions.themeMode,
                    labelOf = { it.label },
                    onSelect = actions.setThemeMode
                )
                Spacer(Modifier.height(10.dp))
                SettingRow(
                    icon = MelodyIcons.Palette,
                    title = "主题色",
                    subtitle = "仅替换主色家族，中性色板保持一致"
                )
                ChipFlow(
                    options = AccentTheme.entries.toList(),
                    selected = actions.accent,
                    labelOf = { it.label },
                    onSelect = actions.setAccent
                )
                Spacer(Modifier.height(10.dp))
                SettingRow(
                    icon = MelodyIcons.ArtworkShape,
                    title = "封面形状",
                    subtitle = "列表、迷你条、播放页的封面统一按这个形状显示"
                )
                ChipFlow(
                    options = ArtworkShape.entries.toList(),
                    selected = state.artworkShape,
                    labelOf = { it.label },
                    onSelect = actions.setArtworkShape
                )
                Spacer(Modifier.height(10.dp))
                SettingRow(
                    icon = MelodyIcons.TextSize,
                    title = "歌词字号",
                    subtitle = "改完歌词页立刻生效；入口统一在这里（播放页不再放 A− / A＋）"
                )
                ChipFlow(
                    options = LyricTextSize.entries.toList(),
                    selected = state.lyricTextSize,
                    labelOf = { it.label },
                    onSelect = actions.setLyricTextSize
                )
            }
        }
    }
}
