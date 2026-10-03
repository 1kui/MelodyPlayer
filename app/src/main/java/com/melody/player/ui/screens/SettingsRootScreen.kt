package com.melody.player.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.melody.player.core.TimeFormat
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.settings.GroupSpacer
import com.melody.player.ui.screens.settings.NoteText
import com.melody.player.ui.screens.settings.RowDivider
import com.melody.player.ui.screens.settings.SettingsPageList
import com.melody.player.ui.screens.settings.SleepCountdownText
import com.melody.player.ui.screens.settings.formatBytes
import com.melody.player.ui.screens.settings.playModeIcon
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置页首屏。
 *
 * ## 为什么改成「常用 + 分组入口」
 * 原来九个分区一段到底，滑到最底下要五六屏，而**九成时间只改那么两三样**
 * （切播放模式、关掉上下滑切歌、睡前定时）。这一段到底的结构让每次都要滚过
 * 七八张与自己无关的卡片。
 *
 * 现在首屏只有两类东西：
 *  1. **常用**：三个高频项直接内联在这里，改它们**不用点进任何页面**；
 *  2. **分组入口**：每行一句摘要，点进二级页 —— 找东西时扫一眼标题就够了。
 *
 * ## 常用项与二级页重复，是故意的
 * 「播放」二级页里也有这三项。首屏这一份是**快捷方式**（省一次点击），
 * 二级页那一份带完整说明（首屏放不下三行解释）。两边读写的是同一个状态，
 * 不会出现"这一处改了、那一处没变"。
 *
 * ## 入口按"对象"分组，不按重要性
 * 「曲库与文件 / 播放与外观 / 在线内容 / 高级 / 关于」—— 用户找不到某个设置时，
 * 心里想的是"这属于哪一摊"（曲库？还是在线内容？），不是"它有多重要"。
 */
@Composable
internal fun SettingsRootContent(
    state: PlayerUiState,
    actions: SettingsActions,
    sleepRemaining: StateFlow<Long?>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        // ================================================================ 常用
        item(key = "common") {
            SectionCard(title = "常用") {
                SettingRow(
                    icon = playModeIcon(state.playMode),
                    title = state.playMode.label,
                    subtitle = "点一下换下一种（顺序 → 列表循环 → 单曲 → 随机）",
                    onClick = actions.cyclePlayMode,
                    trailing = {
                        Text(
                            text = "切换",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.SwapVertical,
                    title = "上下滑动切换歌曲",
                    subtitle = "在播放页封面上滑下一首、下滑上一首"
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
                        "到点自动暂停，队列与进度都留着"
                    } else {
                        "已设定，到点自动暂停"
                    },
                    onClick = actions.openSleepTimer,
                    trailing = { SleepCountdownText(remaining = sleepRemaining) }
                )
            }
        }

        // ========================================================== 曲库与文件
        item(key = "gap-library") { GroupSpacer() }

        item(key = "group-library") {
            SectionCard(title = "曲库与文件") {
                SettingsEntry(
                    page = SettingsPage.LIBRARY,
                    summary = buildString {
                        append("${state.songs.size} 首 · 总时长 ")
                        append(TimeFormat.durationText(state.totalDurationMs))
                        state.libraryFolderName?.let { append(" · 限定「$it」") }
                    },
                    onNavigate = actions.navigate
                )
                RowDivider()
                SettingsEntry(
                    page = SettingsPage.ARCHIVE,
                    summary = if (state.archivedCount > 0) {
                        "${state.archivedCount} 首 · ${formatBytes(state.archivedBytes)}" +
                            "（清理工具不会动）"
                    } else {
                        "把歌复制进 App 专属目录，附歌词快照"
                    },
                    onNavigate = actions.navigate
                )
                RowDivider()
                SettingsEntry(
                    page = SettingsPage.HIDDEN,
                    summary = if (state.hiddenSongs.isEmpty()) {
                        "还没有隐藏任何曲目"
                    } else {
                        "${state.hiddenSongs.size} 首不参与列表与搜索"
                    },
                    onNavigate = actions.navigate
                )
            }
        }

        // ========================================================== 播放与外观
        item(key = "group-playback") {
            SectionCard(title = "播放与外观") {
                SettingsEntry(
                    page = SettingsPage.PLAYBACK,
                    summary = "播放模式 · 滑动切歌 · 睡眠定时",
                    onNavigate = actions.navigate
                )
                RowDivider()
                SettingsEntry(
                    page = SettingsPage.APPEARANCE,
                    summary = "主题模式 · 主题色 · 封面形状 · 歌词字号",
                    onNavigate = actions.navigate
                )
                RowDivider()
                SettingsEntry(
                    page = SettingsPage.LYRICS,
                    summary = buildString {
                        append("联网来源 ")
                        append(
                            if (state.lyricProviders.isEmpty()) {
                                "全关"
                            } else {
                                "${state.lyricProviders.size} 家"
                            }
                        )
                        append(" · 歌词副本 ")
                        append(state.lyricCopyGroups.size)
                        append(" 首")
                    },
                    onNavigate = actions.navigate
                )
            }
        }

        // ============================================================ 在线内容
        item(key = "group-online") {
            SectionCard(title = "在线内容") {
                SettingsEntry(
                    page = SettingsPage.COVER,
                    summary = if (state.autoFetchCovers) {
                        "自动补齐已开 · 缓存 ${state.coverCount} 张"
                    } else {
                        "自动补齐已关 · 缓存 ${state.coverCount} 张"
                    },
                    onNavigate = actions.navigate
                )
            }
        }

        // ================================================================ 高级
        item(key = "group-advanced") {
            SectionCard(title = "高级") {
                SettingsEntry(
                    page = SettingsPage.KWM,
                    summary = if (state.kwmFiles.isEmpty()) {
                        "酷我加密音频容器，解密后自动归档"
                    } else {
                        "待处理列表里有 ${state.kwmFiles.size} 个文件"
                    },
                    onNavigate = actions.navigate
                )
            }
        }

        // ================================================================ 关于
        item(key = "group-about") {
            SectionCard(title = "关于") {
                SettingsEntry(
                    page = SettingsPage.ABOUT,
                    summary = "版本 · 加入 QQ 群 · 开源仓库",
                    onNavigate = actions.navigate
                )
            }
        }

        item(key = "root-note") {
            NoteText(
                "设置项改完立刻生效，不需要重启 App。所有开关只写本机偏好，" +
                    "不上传、不同步；卸载即清空。"
            )
        }
    }
}

/**
 * 首屏的一条分组入口。
 *
 * 三件套：**图标**（这一摊是关于什么的）、**页名**（点进去叫什么）、
 * **一句摘要**（里面现在是什么状态）—— 摘要不是装饰：它让"我到底要不要进去"
 * 变成一眼能答的问题（例如「已隐藏的曲目 · 12 首」），
 * 而不是"进去看看有没有"。
 */
@Composable
private fun SettingsEntry(
    page: SettingsPage,
    summary: String,
    onNavigate: (SettingsPage) -> Unit
) {
    SettingRow(
        icon = settingsPageIcon(page),
        title = page.title,
        subtitle = summary,
        onClick = { onNavigate(page) },
        trailing = {
            Icon(
                imageVector = MelodyIcons.ChevronDown,
                contentDescription = "进入${page.title}",
                // ChevronDown 转过 -90° 就是向右的箭头（同一个几何只维护一份）
                modifier = Modifier
                    .size(18.dp)
                    .rotate(-90f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

/** 分组入口的图标。`ROOT` 不会出现在入口列表里，给一个中性图标兜住。 */
private fun settingsPageIcon(page: SettingsPage) = when (page) {
    SettingsPage.LIBRARY -> MelodyIcons.MusicNote
    SettingsPage.ARCHIVE -> MelodyIcons.Archive
    SettingsPage.HIDDEN -> MelodyIcons.EyeOff
    SettingsPage.PLAYBACK -> MelodyIcons.Play
    SettingsPage.APPEARANCE -> MelodyIcons.Palette
    SettingsPage.LYRICS -> MelodyIcons.Lyrics
    SettingsPage.COVER -> MelodyIcons.AlbumArt
    SettingsPage.KWM -> MelodyIcons.LockOpen
    SettingsPage.ABOUT -> MelodyIcons.Info
    SettingsPage.ROOT -> MelodyIcons.Settings
}
