package com.melody.player.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.SettingsActions
import kotlinx.coroutines.flow.StateFlow

/**
 * 「播放」二级页：播放模式 / 滑动切歌 / 睡眠定时。
 *
 * 这三项同时也是首屏「常用」卡上的快捷行 —— 首屏那三行是**故意**重复的：
 * 它们属于"躺着就能改"的那一类，多一层点击就是多一次抬手。
 * 二级页这一份则带上完整说明（首屏放不下三行解释），两边改的是同一个状态，
 * 所以永远不会出现"这一页改了、那一页没变"。
 *
 * 倒计时读数直接读流（见 [SleepCountdownText]），不把秒数放进 `PlayerUiState`：
 * 放进去的话整页每秒重组一次，而这里只有一个 `Text` 真的在变。
 */
@Composable
internal fun SettingsPlaybackPage(
    state: PlayerUiState,
    actions: SettingsActions,
    sleepRemaining: StateFlow<Long?>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        item(key = "playback-mode") {
            SectionCard(title = "播放模式") {
                SettingRow(
                    icon = playModeIcon(state.playMode),
                    title = state.playMode.label,
                    subtitle = "顺序播放 → 列表循环 → 单曲循环 → 随机播放，点一下换下一种",
                    onClick = actions.cyclePlayMode,
                    trailing = {
                        Text(
                            text = "切换",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
        }

        item(key = "playback") {
            SectionCard(title = "播放") {
                SettingRow(
                    icon = MelodyIcons.SwapVertical,
                    title = "上下滑动切换歌曲",
                    subtitle = "在播放页封面上下滑动：上滑下一首，下滑上一首"
                ) {
                    Switch(
                        checked = state.swipeSwitchSong,
                        onCheckedChange = actions.setSwipeSwitchSong
                    )
                }
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Moon,
                    title = "睡眠定时",
                    subtitle = if (state.sleepDeadlineMs == null) {
                        "15 分钟～1.5 小时，到点自动暂停；播放队列与进度都留着"
                    } else {
                        "已设定，到点自动暂停（点这里改时长或取消）"
                    },
                    onClick = actions.openSleepTimer,
                    trailing = {
                        SleepCountdownText(remaining = sleepRemaining)
                    }
                )
                NoteText(
                    "倒计时由播放服务看着，不是 App 界面：把 App 划掉、锁屏都不影响它。" +
                        "到点只是**暂停**，通知与队列还在，睁眼点一下就能接着听。" +
                        "定时本身不落盘 —— 重开 App 后它自然失效，不会出现" +
                        "「重开 App 五分钟后莫名暂停」。"
                )
            }
        }
    }
}
