package com.melody.player.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.ITunesApi
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.SettingsActions

/**
 * 「专辑封面」二级页：自动联网 / 补齐与清理 / 重读内嵌 / 获取地区 / 匹配评分下限。
 *
 * 这一页比别的页都长，但它**不能**再往下拆：这几项是一条链上的前后环节
 * （地区决定去哪搜、评分下限决定搜到的要不要），分开之后用户得在两页之间来回对
 * "我现在设的地区到底影响哪一步"。所以宁可长，也别拆。
 */
@Composable
internal fun SettingsCoverPage(
    state: PlayerUiState,
    actions: SettingsActions,
    contentPadding: PaddingValues,
    onHelp: (HelpTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        item(key = "covers") {
            SectionCard(title = "专辑封面") {
                SettingRow(
                    icon = MelodyIcons.AlbumArt,
                    title = "自动联网获取封面",
                    subtitle = "本地标签没有封面时，按歌名 + 歌手去 iTunes 搜一张"
                ) {
                    Switch(
                        checked = state.autoFetchCovers,
                        onCheckedChange = actions.setAutoFetchCovers
                    )
                }
                RowDivider()
                ActionRow {
                    if (state.coverWorking) {
                        ChipButton(
                            text = "停止补齐",
                            icon = MelodyIcons.Close,
                            onClick = actions.cancelBackfillCovers,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        ChipButton(
                            text = "为曲库补齐封面",
                            icon = MelodyIcons.AlbumArt,
                            onClick = actions.backfillCovers,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    ChipButton(
                        text = "清除封面缓存",
                        icon = MelodyIcons.Delete,
                        onClick = actions.clearCoverCache,
                        modifier = Modifier.weight(1f)
                    )
                }
                // 「重读内嵌封面」与上面两个是**不同层的事**，所以单独一行：
                // 上面清的是 App 自己下的缓存，这里丢的是"文件里那张"的解析结果。
                // 用户在别的播放器里换过图之后，只有这个能让他在 App 里看到新图。
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Refresh,
                    title = "重新解析内嵌封面",
                    subtitle = "丢掉缓存、从音频文件里重读一遍封面。" +
                        "在别的播放器里换过图时用这个；扫完会告诉你有几首真的带封面",
                    onClick = actions.reparseEmbedded
                )

                if (state.coverWorking) {
                    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 6.dp)) {
                        // 进度是**确定式**的：待处理首数一开始就算出来了，百分比是真实的。
                        // 原来那条滚动条写着"已处理 N 首"，而那个 N 是**缓存总数**、
                        // 不是本轮的进度 —— 数字与进度条说的不是同一件事，越看越不对
                        LinearProgressIndicator(
                            progress = { state.coverProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 10.dp)
                        )
                        Text(
                            text = buildString {
                                append("第 ")
                                append((state.coverDone + 1).coerceAtMost(state.coverTotal))
                                append(" / ")
                                append(state.coverTotal)
                                append(" 首")
                                state.coverStage.takeIf { it.isNotBlank() }
                                    ?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = state.coverLabel.ifBlank { "准备中…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "已获取 ${state.coverFetched} 张 · 没找到 ${state.coverNoMatch} 首 · " +
                                "失败 ${state.coverFailed} 首",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                        )
                    }
                } else {
                    SettingRow(
                        icon = MelodyIcons.Folder,
                        title = "已缓存 ${state.coverCount} 张封面",
                        subtitle = "占用 ${formatBytes(state.coverBytes)}"
                    )
                }

                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Globe,
                    title = "自定义获取地区",
                    subtitle = if (state.coverRegionCustom) {
                        "按下面勾选的顺序依次搜；中国大陆区没有音乐目录，因此不提供"
                    } else {
                        "关着时用默认顺序：中国台湾 → 中国香港 → 美国"
                    }
                ) {
                    Switch(
                        checked = state.coverRegionCustom,
                        onCheckedChange = actions.setCoverRegionCustom
                    )
                }
                if (state.coverRegionCustom) {
                    // 进这一栏就自动测一轮延迟。用户打开开关的动机就是"想挑几个地区"，
                    // 而挑的依据只有实测量出来的数字 —— 让他先点一下"测速"才知道有这回事，
                    // 等于把这个判据藏起来了。测过就不再自动重测（结果还在这一屏上）。
                    LaunchedEffect(state.coverRegionCustom, state.coverRegionPings.isEmpty()) {
                        if (state.coverRegionCustom &&
                            state.coverRegionPings.isEmpty() &&
                            !state.coverRegionPinging
                        ) {
                            actions.probeCoverRegions()
                        }
                    }

                    MultiChipFlow(
                        options = CoverRegion.entries.toList(),
                        selected = state.coverRegions.toSet(),
                        labelOf = { it.label },
                        onToggle = actions.toggleCoverRegion,
                        // 延迟直接标在**每个地区自己身上**：哪一行对应哪个数字不用来回找，
                        // 也省掉了原来那条与选项隔了半屏的摘要行
                        badgeOf = { regionPingBadge(state.coverRegionPings[it.code]) }
                    )
                    NoteText(
                        "最多同时选 ${CoverRegion.MAX_SELECTED} 个：每多一个地区就多一次接口调用，" +
                            "而 iTunes 这个接口有限流，选太多会让「补齐封面」慢得离谱。"
                    )
                    RowDivider()
                    SettingRow(
                        icon = MelodyIcons.Clock,
                        title = "连接延迟",
                        subtitle = if (state.coverRegionPinging) {
                            "正在依次测量各地…（每测一个刷新一次，结果标在各地区上）"
                        } else {
                            "实测一次搜索请求的往返耗时；「无法连接」表示这个地区现在取不到数据"
                        },
                        trailing = {
                            TextButton(
                                onClick = actions.probeCoverRegions,
                                enabled = !state.coverRegionPinging
                            ) {
                                Text(if (state.coverRegionPinging) "测速中…" else "重新测速")
                            }
                        }
                    )
                    NoteText(
                        "每个地区后面标的就是它的实测延迟（没标上的是还没测到）。" +
                            "延迟只是「哪个区排在前面」的参考：它高不代表搜不到，只是每次搜索要多等一会儿。" +
                            "先按延迟从低到高勾，再用实际搜索验一验。"
                    )
                }

                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Gauge,
                    title = "匹配评分下限",
                    subtitle = "低于这个分数就不贴封面：调低更容易匹配上，也更容易贴错",
                    trailing = {
                        Text(
                            text = "${state.coverMinScore} 分",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                Slider(
                    value = state.coverMinScore.toFloat(),
                    onValueChange = { actions.setCoverMinScore(it.toInt()) },
                    valueRange = ITunesApi.MIN_SCORE_FLOOR.toFloat()..ITunesApi.MAX_SCORE.toFloat(),
                    // 每 10 分一挡：评分本身是粗粒度判断，给到 1 分的精度只会让人纠结
                    steps = (ITunesApi.MAX_SCORE - ITunesApi.MIN_SCORE_FLOOR) / 10 - 1,
                    modifier = Modifier.padding(horizontal = CardPad)
                )
                NoteText(
                    when {
                        state.coverMinScore <= 60 ->
                            "现在很宽松：能匹配上的更多，但可能给某首歌贴上别人的封面。"
                        state.coverMinScore >= 130 ->
                            "现在很严格：只收歌名、歌手、时长都对得上的候选，宁可没有封面。"
                        else -> "默认 90 分 ≈「歌名对得上，且歌手或时长至少对上一个」。"
                    }
                )
                FoldableHelp(
                    HelpTopic(
                        label = "封面从哪来、怎么匹配",
                        text = "来源是 Apple 的 iTunes Search API（公开接口，不需要密钥）：只用" +
                            "「歌名 + 歌手」做关键词，不上传本地文件。中国大陆区没有音乐目录，" +
                            "因此默认按中国台湾 → 中国香港 → 美国依次搜，也可以在「自定义获取地区」里改。\n" +
                            "自动匹配按分数挑最像的一条（满分 170）：歌名完全一致 100 分、只是包含 55 分、" +
                            "字面重合度低 20 分，对不上倒扣 60；歌手对得上加 40、对不上扣 20；" +
                            "时长差 3 秒内加 30、10 秒内加 15、超过 25 秒扣 40；候选带 Live／伴奏／翻唱／" +
                            "混音这类版本词、而你的标题里没有，再扣 8–70 分。总分不到「匹配评分下限」" +
                            "（默认 90 分）就不给封面 —— 贴错封面比暂时没有更麻烦。\n" +
                            "「为曲库补齐封面」不走打分：直接取搜索结果的第一条（接口本身按相关性排序），" +
                            "几百首时才用；不满意的可以单曲自选覆盖。它同样受「自定义获取地区」影响；" +
                            "取一张封面要过三步（搜索候选 → 下载封面 → 写入缓存），进度里会写出当前在哪一步。"
                    )
                ) { onHelp(it) }
            }
        }
    }
}
