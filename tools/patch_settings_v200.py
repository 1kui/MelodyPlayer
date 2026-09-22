# -*- coding: utf-8 -*-
"""v2.0 设置页排版整理 + 说明改写 + 版本号。

一次落盘，每处替换都断言命中次数，避免 Kotlin 同文件多处 Edit 静默丢失。
"""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
hits = []


def patch(rel, old, new, count=1):
    path = os.path.join(ROOT, rel)
    s = io.open(path, encoding="utf-8").read()
    got = s.count(old)
    assert got == count, "HIT %d != %d in %s :: %r" % (got, count, rel, old[:70])
    s = s.replace(old, new)
    io.open(path, "w", encoding="utf-8", newline="").write(s)
    hits.append((rel, old[:48]))


SET = "app/src/main/java/com/melody/player/ui/screens/SettingsScreen.kt"
VM = "app/src/main/java/com/melody/player/ui/player/PlayerViewModel.kt"
UIS = "app/src/main/java/com/melody/player/ui/player/PlayerUiState.kt"
GRADLE = "app/build.gradle.kts"

# ---------------------------------------------------------------- 1. 常量：子行缩进基线
patch(SET, r"""/** 字节数的人类可读写法，用于 App 音乐库的占用统计。 */""",
      r"""/**
 * 卡片内「图标 + 文字」行的文字左边界：16(卡片内边距) + 22(图标) + 16(间距)。
 *
 * 展开的子行（歌词副本的每一份）缩进到这条线上，与父行的文字对齐 ——
 * 子行各写各的 32dp/66dp 会看着像两套栅格。
 */
private val RowInset = 54.dp

/** 字节数的人类可读写法，用于 App 音乐库的占用统计。 */""")

# ---------------------------------------------------------------- 2. 曲库总览：去掉与独立区块重复的「已隐藏」
patch(SET, r"""                if (state.hiddenSongs.isNotEmpty()) {
                    SettingRow(
                        icon = MelodyIcons.EyeOff,
                        title = "已隐藏",
                        subtitle = "不进列表，也不参与搜索",
                        trailing = {
                            Text(
                                text = "${state.hiddenSongs.size} 首",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
                HorizontalDivider(""",
      r"""                HorizontalDivider(""")

# 「音乐库」与「App 音乐库」两个标题只差三个字，改成各说各的
patch(SET, r"""        item(key = "stats") {
            SectionCard(title = "音乐库") {""",
      r"""        item(key = "stats") {
            SectionCard(title = "曲库总览") {""")

# ---------------------------------------------------------------- 3. App 音乐库：去掉重复的「歌词副本」行
patch(SET, r"""                SettingRow(
                    icon = MelodyIcons.Lyrics,
                    title = "歌词副本",
                    subtitle = "导入/联网/归档留下的歌词都存这里，可单独管理",
                    trailing = {
                        Text(
                            text = "${state.lyricCopies.size} 份",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                if (state.archiving) {""",
      r"""                if (state.archiving) {""")

# ---------------------------------------------------------------- 4. 已归档曲目：折叠行标题统一成名词
patch(SET, r"""                    CollapseRow(
                        icon = MelodyIcons.Archive,
                        title = "管理已归档曲目",
                        subtitle = if (archivedExpanded) {
                            "点某一行的「取消归档」只删那一份副本"
                        } else {
                            "展开后可以单独取消某一首的归档"
                        },""",
      r"""                    CollapseRow(
                        icon = MelodyIcons.Archive,
                        title = "已归档曲目",
                        subtitle = if (archivedExpanded) {
                            "收起列表"
                        } else {
                            "点这一行展开：可单独取消某一首的归档"
                        },""")

patch(SET, r"""                        Text(
                            text = "「取消归档」删的是 App 库里的那份副本（以及归档时留下的歌词快照），" +
                                "原文件不会被删，取消后它会重新出现在曲库里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )""",
      r"""                        Text(
                            text = "「取消归档」只删 App 库里的那份副本；原文件不会被删，随后会重新出现在曲库里。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )""")

# ---------------------------------------------------------------- 5. KWM 说明：只讲原理与找文件
patch(SET, r"""                // 说明收成一行：「原理 + 找文件的三条路 + 输出格式」都属读一次就够的内容
                FoldableHelp(
                    label = "解密原理、怎么找文件、输出什么格式",
                    text = buildString {
                        append(
                            ".kwm 是一种加密音频容器：文件头 1KB 是明文说明，后面整段音频用" +
                                "「固定口令 ⊕ 文件密钥」异或加密。解密就是把这段异或回来 —— " +
                                "无损、可逆，不联网、不上传，全程在本机完成。\n"
                        )
                        append(
                            "三条路找文件：①「扫描设备」查系统媒体库，最省事，但 .kwm 不算系统认识的" +
                                "媒体类型，Android 11 之后多半一个都扫不到；②「指定文件夹」授权音乐目录，" +
                                "递归查找子目录，授权跨重启保留 —— 新系统上这条路最可靠；" +
                                "③「选择文件」手动多选，一定可用。\n"
                        )
                        append(
                            "解密结果直接落进 App 专属目录并登记进曲库，接着就能播。" +
                                "原 .kwm 不会被删除，也不会被改动。\n"
                        )
                        append(
                            "输出统一转成 320kbps 的 MP3：原文件多是 .mgg（本质是 Ogg Vorbis/Opus），" +
                                "系统解码器认不了这个后缀。Vorbis/Opus 本身是有损格式，转 MP3 属于" +
                                "二次有损，音质不会比原文件更好，换来的是「什么设备都能放」。"
                        )
                        if (!state.kwmEncoderReady) {
                            append(
                                "这台设备上的 MP3 编码器不可用，本次只能保留原格式归档" +
                                    "（播放需要设备支持该编码）。"
                            )
                        }
                    }
                )""",
      r"""                // 只留「原理 + 怎么找文件」两件真正影响使用的事，一次读完
                FoldableHelp(
                    label = "解密原理、怎么找文件",
                    text = ".kwm 是一种加密音频容器：文件头 1KB 是明文说明，之后的音频整段用" +
                        "「固定口令 ⊕ 文件密钥」异或加密。解密就是把这段异或回来 —— " +
                        "无损、可逆，不联网、不上传，全程在本机完成。\n" +
                        "三条路找文件：「扫描设备」查系统媒体库最省事，但 .kwm 不是系统认识的" +
                        "媒体类型，Android 11 之后多半扫不到；「指定文件夹」授权音乐目录后递归查找，" +
                        "授权跨重启保留，新系统上最可靠；「选择文件」手动多选，一定可用。\n" +
                        "解密结果落进 App 专属目录并登记进曲库，接着就能播；原 .kwm 不会被删除，" +
                        "也不会被改动。"
                )""")

# ---------------------------------------------------------------- 6. 歌词副本：去嵌套、去重复文案、对齐栅格
patch(SET, r"""        item(key = "lyric-copies") {
            SectionCard(title = "歌词副本管理") {
                // 默认收起：副本一多这一节能把整页设置撑出好几屏，而管理副本是低频操作。
                // 与「已隐藏的曲目」同一套折叠交互（CollapseRow）
                var lyricCopiesExpanded by rememberSaveable { mutableStateOf(false) }
                CollapseRow(
                    icon = MelodyIcons.Lyrics,
                    title = "已保存的歌词",
                    subtitle = if (lyricCopiesExpanded) {
                        "收起列表"
                    } else {
                        "存在 App 私有目录，与歌曲文件无关；点这一行展开管理"
                    },
                    trailing = if (state.lyricCopies.isEmpty()) {
                        null
                    } else {
                        "${state.lyricCopies.size} 份 · ${formatBytes(state.lyricCopiesBytes)}"
                    },
                    expanded = lyricCopiesExpanded,
                    onToggle = { lyricCopiesExpanded = !lyricCopiesExpanded }
                )
                if (lyricCopiesExpanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                if (state.lyricCopies.isEmpty()) {
                    Text(
                        text = "导入 .lrc、联网获取歌词、以及归档到 App 库时，都会在这里留下" +
                            "一份歌词副本 —— 好处是原文件被删、授权失效、歌词标签没写进去，歌词都还在。" +
                            "目前没有副本，也就没有可管理的东西。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                } else {
                    Text(
                        text = "共 ${state.lyricCopies.size} 份，分属 ${state.lyricCopyGroups.size} 首歌。" +
                            "点歌名展开这一首的副本：可以看全文、删单份，也能一次删掉这首歌的全部副本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    val groups = state.lyricCopyGroups.take(LYRIC_COPY_GROUP_LIMIT)
                    groups.forEach { group ->
                        val key = group.expandKey
                        LyricCopyGroupRow(
                            group = group,
                            expanded = key in expandedCopyGroups,
                            onToggle = {
                                expandedCopyGroups = if (key in expandedCopyGroups) {
                                    expandedCopyGroups - key
                                } else {
                                    expandedCopyGroups + key
                                }
                            },
                            onPreview = onPreviewLyricCopy,
                            onDelete = { pendingDeleteLyric = it },
                            onDeleteGroup = { pendingDeleteLyricGroup = group }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    if (state.lyricCopyGroups.size > LYRIC_COPY_GROUP_LIMIT) {
                        Text(
                            text = "只列出最近保存的 $LYRIC_COPY_GROUP_LIMIT 首歌，共 ${state.lyricCopyGroups.size} 首；" +
                                "其余的在下面的「清空全部」里可以一并清掉。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        ChipButton(
                            text = "清空全部副本（${state.lyricCopies.size}）",
                            icon = MelodyIcons.Delete,
                            onClick = { confirmClearLyrics = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = "删副本不会动你的音乐文件，也不会动已经写进音频文件的歌词。" +
                            "删掉之后，这首歌退回使用文件内嵌歌词或同名 .lrc；" +
                            "什么都没有时，播放页仍可重新导入或联网获取。" +
                            "「联网自动匹配」的那几份删掉后，下次播到那首歌、且「自动联网获取歌词」" +
                            "开着的话，会重新匹配一次（想彻底关掉，把上面那个开关关了就成）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                }
            }
        }""",
      r"""        item(key = "lyric-copies") {
            SectionCard(title = "歌词副本") {
                // 默认收起：副本一多这一节能撑出好几屏，而管理副本是低频操作。
                // 与「已归档曲目」「已隐藏曲目」同一套折叠交互（CollapseRow）——
                // 区块内不再套第二层折叠，一行标题就是这个区块的全部入口
                var lyricCopiesExpanded by rememberSaveable { mutableStateOf(false) }
                CollapseRow(
                    icon = MelodyIcons.Lyrics,
                    title = if (state.lyricCopies.isEmpty()) {
                        "已保存的歌词"
                    } else {
                        "共 ${state.lyricCopies.size} 份，分属 ${state.lyricCopyGroups.size} 首歌"
                    },
                    subtitle = when {
                        lyricCopiesExpanded -> "收起列表"
                        state.lyricCopies.isEmpty() ->
                            "导入 .lrc、联网获取、归档时都会在这里留一份"
                        else -> "点这一行展开：看全文、删单份，或删掉一首歌的全部副本"
                    },
                    trailing = if (state.lyricCopies.isEmpty()) {
                        null
                    } else {
                        formatBytes(state.lyricCopiesBytes)
                    },
                    expanded = lyricCopiesExpanded,
                    onToggle = { lyricCopiesExpanded = !lyricCopiesExpanded }
                )

                if (lyricCopiesExpanded) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    if (state.lyricCopies.isEmpty()) {
                        Text(
                            text = "还没有副本。原文件被删、授权失效、歌词标签没写进去时，" +
                                "存在这里的副本仍然能把歌词找回来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    } else {
                        state.lyricCopyGroups.take(LYRIC_COPY_GROUP_LIMIT).forEach { group ->
                            val key = group.expandKey
                            LyricCopyGroupRow(
                                group = group,
                                expanded = key in expandedCopyGroups,
                                onToggle = {
                                    expandedCopyGroups = if (key in expandedCopyGroups) {
                                        expandedCopyGroups - key
                                    } else {
                                        expandedCopyGroups + key
                                    }
                                },
                                onPreview = onPreviewLyricCopy,
                                onDelete = { pendingDeleteLyric = it },
                                onDeleteGroup = { pendingDeleteLyricGroup = group }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = RowInset),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        if (state.lyricCopyGroups.size > LYRIC_COPY_GROUP_LIMIT) {
                            Text(
                                text = "只列出最近保存的 $LYRIC_COPY_GROUP_LIMIT 首歌，" +
                                    "共 ${state.lyricCopyGroups.size} 首；其余的在下面的「清空全部」里可一并清掉。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            ChipButton(
                                text = "清空全部副本（${state.lyricCopies.size}）",
                                icon = MelodyIcons.Delete,
                                onClick = { confirmClearLyrics = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 删副本的后果只留一句；逐条细节在确认弹窗里说
                        Text(
                            text = "删副本不会动音乐文件，也不会动已经写进音频文件的歌词。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }""")

# ---------------------------------------------------------------- 7. 已隐藏曲目：折叠行标题统一
patch(SET, r"""                    title = if (hidden.isEmpty()) {
                        "还没有隐藏任何曲目"
                    } else {
                        "共 ${hidden.size} 首不参与列表与搜索"
                    },
                    subtitle = if (hiddenExpanded) {
                        "收起列表"
                    } else {
                        "点这一行展开管理（只影响显示，不删文件）"
                    },""",
      r"""                    title = "已隐藏曲目",
                    subtitle = if (hiddenExpanded) {
                        "收起列表"
                    } else {
                        "点这一行展开：可单独恢复（只影响显示，不删文件）"
                    },""")

# ---------------------------------------------------------------- 8. iTunes 说明：短，且与代码里的打分一致
patch(SET, r"""                // 说明收敛成一行：默认只留一句最有用的，
                // 全文（来源、限流、隐私）点开才看 —— 平时不再占一大屏
                FoldableHelp(
                    label = "给单曲挑封面、批量补齐的规则",
                    text = "给一首歌挑封面：在曲库长按（或播放页 ⋮）→「选择专辑封面」。" +
                        "在线搜索把 iTunes 上能找到的版本列成候选，你自己挑哪一张；" +
                        "都不像还可以从相册选一张自定义封面，或直接移除封面退回占位图案。\n" +
                        "「自动联网获取封面」只作用于自动场景（本地没有封面时按歌名、歌手、时长打分挑一张，" +
                        "分不够就宁可不要）；手动选择永远以你选的为准，并覆盖自动结果。\n" +
                        "「为曲库补齐封面」是无人值守的批量：每首歌自动取搜索结果里的第一张" +
                        "（相关性排序的头名），不满意的可以再单曲自选覆盖。\n" +
                        "来源是 Apple 的 iTunes Search API（公开接口，不需要密钥）：只发送" +
                        "「歌名 + 歌手」当关键词，不上传本地文件。取回的图压缩到 512×512 存在 App 私有目录。" +
                        "地区按中国台湾 → 中国香港 → 美国依次尝试：Apple 的音乐目录在中国大陆区是空的，" +
                        "所以不能直接用 CN。接口有限流（约每分钟 20 次），批量补齐时排队慢慢来，随时可停。"
                )""",
      r"""                FoldableHelp(
                    label = "封面从哪来、怎么匹配",
                    text = "来源是 Apple 的 iTunes Search API（公开接口，不需要密钥）：只用" +
                        "「歌名 + 歌手」做关键词，不上传本地文件。中国大陆区没有音乐目录，" +
                        "所以按中国台湾 → 中国香港 → 美国依次搜。\n" +
                        "自动匹配按分数挑最像的一条（满分 170）：歌名完全一致 100 分、只是包含 55 分、" +
                        "字面重合度低 20 分，对不上倒扣 60；歌手对得上加 40、对不上扣 20；" +
                        "时长差 3 秒内加 30、10 秒内加 15、超过 25 秒扣 40；候选带 Live／伴奏／翻唱／" +
                        "混音这类版本词、而你的标题里没有，再扣 8–70 分。总分不到 90 就不给封面 —— " +
                        "贴错封面比暂时没有更麻烦。\n" +
                        "「为曲库补齐封面」不走打分：直接取搜索结果的第一条（接口本身按相关性排序），" +
                        "几百首时才用；不满意的可以单曲自选覆盖。"
                )""")

# ---------------------------------------------------------------- 9. 关于：去掉已不成立的描述，保留许可声明
patch(SET, r"""                Text(
                    text = "界面基于 Jetpack Compose + Material 3。所有图标均由代码绘制的矢量路径生成" +
                        "（ImageVector 路径指令与矢量 drawable），工程内不含任何位图资源；" +
                        "专辑封面也是按歌曲信息现场生成的渐变图案。在线歌词来自网易云音乐公开接口，" +
                        "只发送歌曲名与歌手用于检索，不会上传任何本地文件。\n" +
                        "KWM 解密与 MP3 转码全部在本机完成，不联网、不上传。MP3 编码器用的是 " +
                        "LAME 3.100（libmp3lame，LGPL 许可），以源码形式编进 APK 里的 " +
                        "libmelody_mp3.so —— Android 平台自身不提供 MP3 编码器，这是为它单独带的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )""",
      r"""                Text(
                    text = "界面基于 Jetpack Compose + Material 3。所有图标均由代码绘制的矢量路径" +
                        "生成（ImageVector 路径指令与矢量 drawable），工程内不含任何位图资源。\n" +
                        "在线歌词与封面只发送歌名、歌手用于检索，不会上传任何本地文件；" +
                        "KWM 解密全程在本机完成，不联网、不上传。\n" +
                        "APK 内含 LAME 3.100（libmp3lame，LGPL 许可，以源码形式编进 libmelody_mp3.so），" +
                        "用于把解密出来的音频落成通用格式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )""")

# ---------------------------------------------------------------- 10. 歌词副本两行：图标/间距对齐栅格
patch(SET, r"""        Icon(
            imageVector = MelodyIcons.Lyrics,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (group.songKey == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Spacer(Modifier.width(14.dp))""",
      r"""        Icon(
            imageVector = MelodyIcons.Lyrics,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (group.songKey == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Spacer(Modifier.width(16.dp))""")

patch(SET, r"""            modifier = Modifier.padding(start = 66.dp),""",
      r"""            modifier = Modifier.padding(start = RowInset),""")

patch(SET, r"""    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
    ) {""",
      r"""    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {""")

patch(SET, r"""            .padding(start = 32.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),""",
      r"""            .padding(start = RowInset, end = 6.dp, top = 10.dp, bottom = 10.dp),""")

patch(SET, r"""        Icon(
            imageVector = MelodyIcons.Lyrics,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(14.dp))""",
      r"""        Icon(
            imageVector = MelodyIcons.Lyrics,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))""")

# ---------------------------------------------------------------- 11. 去掉已无人读取的编码器状态
patch(UIS, r"""    /** MP3 编码器（LAME）是否可用；不可用时只能保留原格式。 */
    val kwmEncoderReady: Boolean = false,
""", "")

patch(VM, r"""            kwmFolderName = prefs.kwmFolderName,
            kwmEncoderReady = LameEncoder.available
""", r"""            kwmFolderName = prefs.kwmFolderName
""")

patch(VM, r"""import com.melody.player.data.mp3.LameEncoder
""", "")

# ---------------------------------------------------------------- 12. 版本 2.0
patch(GRADLE, "versionCode = 20", "versionCode = 21")
patch(GRADLE, 'versionName = "1.9.10"', 'versionName = "2.0"')

print("patched %d sites" % len(hits))
for rel, head in hits:
    print("  -", rel.split("/")[-1], "|", head)
