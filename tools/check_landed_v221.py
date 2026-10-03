"""v2.21 交付前核验：版本号 / 签名 / dex 双向断言。

为什么必须用脚本：
- 中文断言必须按 **utf-8** 解码。dex 字符串池是 MUTF-8，中文按 UTF-8 存；
  用 latin-1 + errors=ignore 会让所有中文断言**静默失败**，看起来像"功能没进包"。
- 正向断言能证明"新东西进包了"，但证明不了"包里不是旧的"。

本版（v2.21）是一次**减法**：单曲菜单拆掉两组、播放页歌词区拆掉偏移小条、
播放历史砍掉一个榜单。所以负向断言这次有真对象：
  1. 版本身份：versionCode/versionName 不能还是上一版，签名证书必须与既有发布同一张；
  2. 被删的**整条长串**不能还在包里（不锚裸词：裸词"播放历史"在 KDoc 里还有，
     但 KDoc 不进 dex；真正要拦的是它作为**界面文案**的那一处）。
正向断言同时钉住"这次只是搬家，能力一个都不能少"：
  播放页 ⋮ 里的封面三项、歌词四项、偏移三项，以及曲库空态按钮、浏览方式入口。
"""

import subprocess
import sys
import zipfile

APK = "app/build/outputs/apk/release/app-release.apk"
BT = "D:/DevTools/android/sdk/build-tools/36.1.0"
AAPT2 = BT + "/aapt2.exe"
APKSIGNER = BT + "/apksigner.bat"

failures = []
total = 0


def check(label, ok, detail=""):
    global total
    total += 1
    print(("  OK   " if ok else "  FAIL ") + label + (("   <- " + detail) if detail and not ok else ""))
    if not ok:
        failures.append(label)


# ---------------------------------------------------------------- 1 版本号
print("[1] 版本号与 SDK 区间")
badging = subprocess.run([AAPT2, "dump", "badging", APK], capture_output=True).stdout.decode(
    "utf-8", errors="replace"
)
check("versionCode = 44", "versionCode='44'" in badging, badging[:200])
check("versionName = 2.21", "versionName='2.21'" in badging)
check("包名 com.melody.player", "package: name='com.melody.player'" in badging)
check("minSdk 26", "minSdkVersion:'26'" in badging)
check("targetSdk 36", "targetSdkVersion:'36'" in badging)
check("不是上一版（versionCode 43 不在包里）", "versionCode='43'" not in badging, badging[:200])
check("不是上一版（versionName 2.20 不在包里）", "versionName='2.20'" not in badging)

# ---------------------------------------------------------------- 2 签名
print("[2] 签名")
r = subprocess.run([APKSIGNER, "verify", "-v", "--print-certs", APK], capture_output=True)
out = r.stdout.decode("utf-8", errors="replace") + r.stderr.decode("utf-8", errors="replace")
check("apksigner verify 通过", r.returncode == 0, out.strip()[:200])
check(
    "v2 签名方案在位",
    "Verified using v2 scheme (APK Signature Scheme v2): true" in out,
    out.strip()[:400],
)
check("证书 SHA-256 与既有发布一致 (b2af88a6…ff1a24)", "b2af88a6" in out.lower(), out.strip()[:400])

# ---------------------------------------------------------------- 3 dex
print("[3] dex 双向断言")
z = zipfile.ZipFile(APK)
dex_names = [n for n in z.namelist() if n.endswith(".dex")]
blob = b"".join(z.read(n) for n in dex_names)
text = blob.decode("utf-8", errors="replace")
print("  " + ", ".join("%s %dKB" % (n, z.getinfo(n).file_size // 1024) for n in dex_names))

MUST_HAVE = [
    # ---- 播放历史 → 最近常听
    ("弹层标题 最近常听", "最近常听"),
    ("弹层副标题（30 秒口径）", "听满 30 秒才算一笔 · 只记在这台设备上，卸载即清空"),
    ("空态说明", "还没有听满 30 秒的曲目。一路快切不会把整张曲库刷成「常听」——"),
    ("弹层尾部按钮", "播放全部（"),
    # ---- 歌词偏移搬进播放页 ⋮
    ("偏移：提前", "歌词提前 0.5 秒"),
    ("偏移：延后", "歌词延后 0.5 秒"),
    ("偏移：复位", "恢复未校正"),
    ("偏移：当前值前缀", "当前："),
    ("偏移：延后说明", "歌词与声音差半秒时按几下就对上，最长 ±30 秒"),
    ("偏移：复位说明", "清回文件里的原始时间轴（只影响这里的显示，不改文件）"),
    ("偏移：未校正文案（LyricOffset.label）", "未校正"),
    ("偏移：状态读法 延后 N 秒", "延后 "),
    ("偏移：状态读法 提前 N 秒", "提前 "),
    # ---- 播放页 ⋮ 里「封面」三项（从列表页搬过来后必须一个不少）
    ("封面：选择专辑封面", "选择专辑封面"),
    ("封面：重新解析内嵌封面", "重新解析内嵌封面"),
    ("封面：写入文件标签", "写入文件标签…"),
    ("封面：按对象分组", "封面"),
    # ---- 播放页 ⋮ 里「歌词」四项
    ("歌词：联网获取", "联网获取歌词"),
    ("歌词：导入 .lrc", "导入 .lrc 歌词文件"),
    ("歌词：重新解析内嵌歌词", "重新解析内嵌歌词"),
    ("歌词：歌词副本", "歌词副本…"),
    # ---- 列表页仍然要有的东西
    ("曲库空态按钮", "回到全部歌曲"),
    ("歌单卡 全部歌曲", "全部歌曲"),
    ("歌单卡 新建", "新建歌单"),
    ("浏览方式入口", "浏览方式"),
    ("按专辑浏览", "按专辑浏览"),
    ("歌曲信息（保留在列表页）", "编辑歌曲信息"),
    ("曲库管理分组（保留在列表页）", "曲库管理"),
    ("批量条 写进文件", "写进文件"),
    ("批量条 重读内嵌封面", "重读内嵌封面"),
    ("批量条 歌词副本", "歌词副本"),
    # ---- 上一版能力回归
    ("播放速度", "播放速度"),
    ("睡眠定时", "睡眠定时"),
    ("自定义时长", "自定义时长…"),
    ("最近搜索", "最近搜索"),
    ("搜索占位符", "搜索标题、歌手或专辑"),
    ("未知专辑", "未知专辑"),
    ("设置页分组入口", "曲库与文件"),
]

MUST_HAVE_CLASSES = [
    ("screens/HistorySheet", "Lcom/melody/player/ui/screens/HistorySheetKt;"),
    ("screens/PlaylistCards", "Lcom/melody/player/ui/screens/PlaylistCardsKt;"),
    ("screens/PlayerScreen", "Lcom/melody/player/ui/screens/PlayerScreenKt;"),
    ("components/SongRow", "Lcom/melody/player/ui/components/SongRowKt;"),
    ("components/ActionSheet", "Lcom/melody/player/ui/components/ActionSheetKt;"),
    ("core/LyricOffset", "Lcom/melody/player/core/LyricOffset;"),
    ("core/PlayHistory", "Lcom/melody/player/core/PlayHistory;"),
]

MUST_NOT_HAVE = [
    # 顶栏 ⋮ 里那两个"对当前列表"的批量项（v2.21 撤掉）
    ("旧的顶栏批量写标签项", "把标签写进音频文件…"),
    # 列表页单曲菜单里的「封面与标签」/「歌词」两组（v2.21 撤掉）
    ("旧的分组标题 封面与标签", "封面与标签"),
    # 新建歌单卡的副标题（六个字在 88dp 卡宽下会被截成"挑几首收…"）
    ("旧的新建歌单卡副标题", "挑几首收进来"),
    # 播放历史这个名字本身（入口已改成"最近常听"）
    ("旧的入口名 播放历史", "播放历史"),
    ("旧的榜单名 最近播放", "最近播放"),
    ("旧的弹层副标题", "只记在这台设备上，卸载即清空 · 点一行就从那一首开始放"),
    ("旧的最近播放空态", "还没听过歌。播放过的曲目会出现在这里，方便回头再听一遍。"),
]


for label, s in MUST_HAVE:
    check("文案在包内: " + label, s in text, s)

for label, s in MUST_HAVE_CLASSES:
    check("类在包内: " + label, s in text, s)

for label, s in MUST_NOT_HAVE:
    check("旧文案不在包内: " + label, s not in text, s)

print()
if failures:
    print("核验未通过：%d / %d 项" % (len(failures), total))
    for f in failures:
        print("  - " + f)
    sys.exit(1)
print("核验全部通过（共 %d 项）" % total)
