"""v2.0（KWM 区块改名 + 说明折叠 + 去掉转码开关）落盘自证。

改法说明：这个项目里同文件多处 Edit 曾静默丢失，所以每次批量改完都跑一遍
「改了没有 / 旧写法还在不在」的双向断言，别只看编辑工具返回的 Success。
"""
import io
import os

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SETTINGS = os.path.join(BASE, "app/src/main/java/com/melody/player/ui/screens/SettingsScreen.kt")
VM = os.path.join(BASE, "app/src/main/java/com/melody/player/ui/player/PlayerViewModel.kt")
STATE = os.path.join(BASE, "app/src/main/java/com/melody/player/ui/player/PlayerUiState.kt")
PREFS = os.path.join(BASE, "app/src/main/java/com/melody/player/data/Prefs.kt")
ROOT = os.path.join(BASE, "app/src/main/java/com/melody/player/ui/MelodyRoot.kt")

CHECKS = {
    SETTINGS: [
        # 标题改名 + 去品牌
        ('SectionCard(title = "KWM音乐解密")', True),
        ('酷我加密音乐（KWM）', False),
        ('酷我', False),
        # 转码开关整行删除
        ('"解密后转成 MP3"', False),
        ('onKwmToMp3Change', False),
        ('state.kwmToMp3', False),
        # 共用的折叠组件 + 四处使用
        ('private fun FoldableHelp(', True),
        ('FoldableHelp(\n                    label = "解密原理、怎么找文件、输出什么格式"', True),
        ('FoldableHelp(\n                    label = "归档是怎么回事、原文件会被删吗"', True),
        ('FoldableHelp(\n                    label = "歌词从哪来、联网匹配的规则"', True),
        ('FoldableHelp(\n                    label = "给单曲挑封面、批量补齐的规则"', True),
        # 封面那套手写折叠应该已经被复用掉
        ('var coverHelpExpanded by rememberSaveable', False),
        # 编码器不可用时的说明仍在（kwmEncoderReady 不作废）
        ('if (!state.kwmEncoderReady) {', True),
    ],
    VM: [
        ('fun setKwmToMp3', False),
        ('kwmToMp3 = prefs.kwmToMp3', False),
        ('val toMp3 = prefs.kwmToMp3', False),
        ('val toMp3 = true', True),
    ],
    STATE: [
        ('val kwmToMp3: Boolean = true', False),
        ('val kwmEncoderReady: Boolean = false', True),
    ],
    PREFS: [
        ('var kwmToMp3: Boolean', False),
        ('KEY_KWM_TO_MP3', False),
    ],
    ROOT: [
        ('onKwmToMp3Change = vm::setKwmToMp3', False),
    ],
}

failed = 0
for path, items in CHECKS.items():
    src = io.open(path, encoding="utf-8").read()
    for needle, want in items:
        got = needle in src
        if got != want:
            failed += 1
            print("FAIL %-12s want=%-5s got=%-5s %r" % (os.path.basename(path), want, got, needle[:56]))
print("ALL OK" if failed == 0 else "HAS FAILURES: %d" % failed)
