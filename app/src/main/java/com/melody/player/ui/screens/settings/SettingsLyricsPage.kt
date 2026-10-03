package com.melody.player.ui.screens.settings

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
import com.melody.player.core.online.LyricProvider
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.SettingsActions

/**
 * 「歌词」二级页：当前曲目来源 / 自动联网 / 两家来源开关 / **歌词副本全局入口**。
 *
 * ## 副本入口为什么落在这里、而不是单开一页
 * 规划里写的是"设置页新分区「歌词副本」"，但真正去数的话：那个分区里**只有一行**
 * （"查看全部副本（含未关联）→"）。为一行开一页，用户要付出的是多一次点击、
 * 以及首屏上多一个说不清里面有什么的入口；而"副本"本来就是歌词的一部分，
 * 放在讲歌词的这一页里，用户想找它时会先想到这儿。
 */
@Composable
internal fun SettingsLyricsPage(
    state: PlayerUiState,
    actions: SettingsActions,
    contentPadding: PaddingValues,
    onHelp: (HelpTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        item(key = "lyrics") {
            SectionCard(title = "歌词") {
                SettingRow(
                    icon = MelodyIcons.Lyrics,
                    title = "当前曲目歌词来源",
                    subtitle = buildString {
                        append(state.currentSong?.title ?: "尚未选择曲目")
                        state.lyricsImportedName?.let { append(" · $it") }
                    },
                    trailing = {
                        Text(
                            text = when {
                                state.lyricsLoading -> "解析中"
                                !state.lyrics.isUsable -> "暂无"
                                state.lyrics.estimated -> "自动对齐"
                                else -> "精确同步"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.CloudDownload,
                    title = "自动联网获取歌词",
                    subtitle = "本地完全没有歌词时，自动去网上匹配一份"
                ) {
                    Switch(
                        checked = state.autoFetchLyrics,
                        onCheckedChange = actions.setAutoFetchLyrics
                    )
                }
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.CloudMusic,
                    title = "网易云获取",
                    subtitle = "按关键词搜索；有官方翻译时合成中英对照"
                ) {
                    Switch(
                        checked = LyricProvider.NETEASE in state.lyricProviders,
                        onCheckedChange = { actions.setLyricProvider(LyricProvider.NETEASE, it) }
                    )
                }
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.CloudLyrics,
                    title = "LRCLIB 获取",
                    subtitle = "按歌名 + 歌手 + 时长签名查询；多数记录带时间轴"
                ) {
                    Switch(
                        checked = LyricProvider.LRCLIB in state.lyricProviders,
                        onCheckedChange = { actions.setLyricProvider(LyricProvider.LRCLIB, it) }
                    )
                }
                if (state.lyricProviders.isEmpty()) {
                    NoteText(
                        "两个来源都关掉了：自动匹配和「联网获取歌词」都已停用。" +
                            "要联网取词，至少打开一个。"
                    )
                }
                RowDivider()
                // 曲库行 ⋮ / 播放页 ⋮ / 多选批量条那三处都是"看这几首的副本"，
                // 而认不回歌曲的副本没有别的入口 —— 这一行是它们唯一的去处。
                SettingRow(
                    icon = MelodyIcons.Lyrics,
                    title = "歌词副本",
                    subtitle = if (state.lyricCopyGroups.isEmpty()) {
                        "App 里还没有存过歌词副本"
                    } else {
                        "共 ${state.lyricCopyGroups.size} 首 · " +
                            "可预览、可删；也能清掉认不回歌曲的那些"
                    },
                    onClick = actions.openLyricCopiesAll,
                    trailing = {
                        Icon(
                            imageVector = MelodyIcons.ChevronDown,
                            contentDescription = "查看全部歌词副本",
                            // ChevronDown 转过 -90° 就是向右的箭头（同一个几何只维护一份）
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(-90f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                FoldableHelp(
                    HelpTopic(
                        label = "歌词从哪来、联网匹配的规则",
                        text = "歌词读取顺序：用户选定的那份（App 内导入的 .lrc 或联网获取的版本）→ " +
                            "文件内嵌歌词（ID3v2 的 USLT/SYLT、FLAC 与 Ogg 的 LYRICS 注释、MP4 的 ©lyr 原子）→ " +
                            "音频同目录同名 .lrc → 自动联网匹配的缓存。\n" +
                            "用播放页右上角 ⋮ →「联网获取歌词」可以联网搜候选，两个来源一起搜、" +
                            "列表里标明各自出处：LRCLIB 按歌名 + 歌手 + 时长锁定同一个录音版本，" +
                            "库里多数记录带时间轴；网易云按关键词搜索，有官方翻译时会自动合成中英对照的双语歌词。" +
                            "候选列表里自己挑一条，被选中的那份会连来源一起记下来。" +
                            "选定的结果和导入的 .lrc 一样会存进 App 私有目录，退出重进、原文件被删都不会丢；" +
                            "归档到 App 库时还可以把歌词直接写进音频文件（MP3 写 USLT 帧，FLAC 写 Vorbis Comment），" +
                            "歌词与歌曲从此合并成一个文件。内嵌歌词若没有时间戳，会按总时长自动均匀对齐并如实标注。"
                    )
                ) { onHelp(it) }
            }
        }
    }
}
