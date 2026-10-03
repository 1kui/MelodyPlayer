"""v2.20 交付前核验：版本号 / 签名 / dex 双向断言。

为什么必须用脚本：
- 中文断言必须按 **utf-8** 解码。dex 字符串池是 MUTF-8，中文按 UTF-8 存；
  用 latin-1 + errors=ignore 会让所有中文断言**静默失败**，看起来像"功能没进包"。
- 正向断言能证明"新东西进包了"，但证明不了"包里不是旧的"。

本版（v2.20）与 v2.19 那种"纯增量"不同：曲库首屏做了一次结构性的重排，
**确实有用户可见文案被删掉**，所以这一次的负向断言分三层：
  1. 版本身份：versionCode/versionName 不能还是上一版，签名证书必须与既有发布同一张；
  2. 被删的文案不能还在包里（空歌单说明、榜单空态说明）；
  3. 被删的类不能还在包里（HistoryCards 整份文件已删除）。
锚点只用**整条长串**，绝不用裸词：例如 "回到全部" 是新串 "回到全部歌曲" 的子串，
拿它当负向锚点会得到一个永远失败的假警报（反过来，若写法稍变又会变成假通过）。
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
check("versionCode = 43", "versionCode='43'" in badging, badging[:200])
check("versionName = 2.20", "versionName='2.20'" in badging)
check("包名 com.melody.player", "package: name='com.melody.player'" in badging)
check("minSdk 26", "minSdkVersion:'26'" in badging)
check("targetSdk 36", "targetSdkVersion:'36'" in badging)
check("不是上一版（versionCode 42 不在包里）", "versionCode='42'" not in badging, badging[:200])
check("不是上一版（versionName 2.19 不在包里）", "versionName='2.19'" not in badging)

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
    # 歌单卡行（取代原来三层选择器的那一条）
    ("全部歌曲（默认歌单）", "全部歌曲"),
    ("新建歌单卡", "挑几首收进来"),
    ("空歌单卡的副标题", "空歌单"),
    ("歌单空态按钮", "回到全部歌曲"),
    # 浏览方式搬进 ⋮
    ("菜单段落 浏览方式", "浏览方式"),
    ("按歌曲列表浏览", "按歌曲列表浏览"),
    ("按专辑浏览", "按专辑浏览"),
    ("按歌手浏览", "按歌手浏览"),
    ("返回歌曲列表", "返回歌曲列表"),
    ("浏览说明 平铺列表", "平铺列表，可多选、可批量操作"),
    ("浏览说明 封面网格", "封面网格"),
    ("浏览说明 一位歌手一张卡", "一位歌手一张卡"),
    # 播放历史弹层
    ("弹层标题 播放历史", "播放历史"),
    ("弹层副标题", "只记在这台设备上，卸载即清空 · 点一行就从那一首开始放"),
    ("弹层尾部按钮", "播放全部（"),
    ("最近播放空态", "还没听过歌。播放过的曲目会出现在这里，方便回头再听一遍。"),
    ("最常听空态（30 秒口径）", "停够 30 秒才算数。"),
    # 睡眠定时：自定义时长
    ("自定义时长入口", "自定义时长…"),
    ("自定义输入框标题", "自定义睡眠定时"),
    ("自定义确认按钮", "开始计时"),
    ("自定义输入提示", "例如 25"),
    # 设置页滚动位置修复：状态容器本身要进包
    ("设置页滚动状态容器", "LocalSettingsListState"),
    # 上一版的能力不能被这次重排弄丢
    ("歌词偏移", "歌词偏移"),
    ("播放速度", "播放速度"),
    ("睡眠定时", "睡眠定时"),
    ("最近搜索", "最近搜索"),
    ("搜索占位符", "搜索标题、歌手或专辑"),
    ("未知专辑", "未知专辑"),
    ("未知歌手", "未知歌手"),
    ("专辑页 加入队列", "加入队列"),
    ("清理未关联副本", "清理未关联副本"),
    ("设置页分组入口", "曲库与文件"),
    ("设置页分组入口 2", "播放与外观"),
]

MUST_HAVE_CLASSES = [
    ("screens/PlaylistCards（新）", "Lcom/melody/player/ui/screens/PlaylistCardsKt;"),
    ("screens/HistorySheet（新）", "Lcom/melody/player/ui/screens/HistorySheetKt;"),
    ("screens/AlbumScreen", "Lcom/melody/player/ui/screens/AlbumScreenKt;"),
    ("screens/CoverGridCell", "Lcom/melody/player/ui/screens/CoverGridCellKt;"),
    ("screens/SettingsRootScreen", "Lcom/melody/player/ui/screens/SettingsRootScreenKt;"),
    ("screens/settings/SettingsCommon", "Lcom/melody/player/ui/screens/settings/SettingsCommonKt;"),
    ("core/SleepTimer", "Lcom/melody/player/core/SleepTimer;"),
    ("core/Playlists", "Lcom/melody/player/core/Playlists;"),
    ("core/PlayHistory", "Lcom/melody/player/core/PlayHistory;"),
    ("playback/SleepTimerHost", "Lcom/melody/player/playback/SleepTimerHost;"),
]

MUST_NOT_HAVE = [
    # 被删掉的文案：整条长串，绝不会误伤
    ("旧的空歌单说明（回到「全部」…）", "回到「全部」，在歌曲右侧"),
    ("旧的榜单空态说明", "这个榜单上的曲目现在都不在曲库里了"),
]

MUST_NOT_HAVE_CLASSES = [
    ("已删除的 HistoryCards", "Lcom/melody/player/ui/screens/HistoryCardsKt;"),
]

for label, s in MUST_HAVE:
    check("文案在包内: " + label, s in text, s)

for label, s in MUST_HAVE_CLASSES:
    check("类在包内: " + label, s in text, s)

for label, s in MUST_NOT_HAVE:
    check("旧文案不在包内: " + label, s not in text, s)

for label, s in MUST_NOT_HAVE_CLASSES:
    check("旧类不在包内: " + label, s not in text, s)

print()
if failures:
    print("核验未通过：%d / %d 项" % (len(failures), total))
    for f in failures:
        print("  - " + f)
    sys.exit(1)
print("核验全部通过（共 %d 项）" % total)
