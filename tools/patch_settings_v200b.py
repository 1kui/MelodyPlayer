# -*- coding: utf-8 -*-
"""v2.0 第二轮：折叠行的「标题/副标题/右侧」三件套统一。

规则（与已归档曲目一致）：
  - 区块标题比行标题宽（App 音乐库 / 歌词副本）→ 行标题用名词，数量放右侧；
  - 区块标题与行标题同义（已隐藏的曲目）→ 行标题直接说状态，右侧不再重复数量。
"""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SET = os.path.join(ROOT, "app/src/main/java/com/melody/player/ui/screens/SettingsScreen.kt")
s = io.open(SET, encoding="utf-8").read()


def patch(old, new):
    assert s.count(old) == 1, "HIT %d :: %r" % (s.count(old), old[:70])
    return s.replace(old, new)


s = patch(r"""                    title = if (state.lyricCopies.isEmpty()) {
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
                    },""",
             r"""                    title = "已保存的歌词",
                    subtitle = when {
                        lyricCopiesExpanded -> "收起列表"
                        state.lyricCopies.isEmpty() ->
                            "导入 .lrc、联网获取、归档时都会在这里留一份"
                        else -> "点这一行展开：看全文、删单份，或删掉一首歌的全部副本"
                    },
                    trailing = if (state.lyricCopies.isEmpty()) {
                        null
                    } else {
                        "${state.lyricCopies.size} 份 · ${formatBytes(state.lyricCopiesBytes)}"
                    },""")

s = patch(r"""                    title = "已隐藏曲目",
                    subtitle = if (hiddenExpanded) {
                        "收起列表"
                    } else {
                        "点这一行展开：可单独恢复（只影响显示，不删文件）"
                    },
                    trailing = if (hidden.isEmpty()) null else "${hidden.size} 首",""",
             r"""                    title = if (hidden.isEmpty()) {
                        "还没有隐藏任何曲目"
                    } else {
                        "共 ${hidden.size} 首不参与列表与搜索"
                    },
                    subtitle = if (hiddenExpanded) {
                        "收起列表"
                    } else {
                        "点这一行展开：可单独恢复（只影响显示，不删文件）"
                    },""")

io.open(SET, "w", encoding="utf-8", newline="").write(s)
print("patched 2 sites")
