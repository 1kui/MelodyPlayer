"""v2.19 交付前核验：版本号 / 签名 / dex 正向断言 + 版本身份负向断言。

为什么必须用脚本：
- 中文断言必须按 **utf-8** 解码。dex 字符串池是 MUTF-8，中文按 UTF-8 存；
  用 latin-1 + errors=ignore 会让所有中文断言**静默失败**，
  看起来像"功能没进包"，其实只是解码错了。
- 正向断言能证明"新东西进包了"，但证明不了"包里不是旧的" ——
  所以负向断言锚在**版本身份**（versionCode/versionName/签名）上：
  这两样不对，说明打的分明是上一轮那个 APK。

本版（v2.19）是纯增量改动：把规划里的 P0-1..P0-4 与 P1-1/P1-2/P1-3a/P1-4/P1-5
压进一个版本，没有任何**用户可见文案被删除**（对比 HEAD 逐字校验过），
所以没有"旧串必须不在"这一类可用的负向断言。
"""
import subprocess
import sys
import zipfile

APK = "app/build/outputs/apk/release/app-release.apk"
BT = "D:/DevTools/android/sdk/build-tools/36.1.0"
AAPT2 = BT + "/aapt2.exe"
APKSIGNER = BT + "/apksigner.bat"

failures = []


def check(label, ok, detail=""):
    print(("  OK   " if ok else "  FAIL ") + label + (("   <- " + detail) if detail and not ok else ""))
    if not ok:
        failures.append(label)


# ---------------------------------------------------------------- 1 版本号
print("[1] 版本号与 SDK 区间")
badging = subprocess.run([AAPT2, "dump", "badging", APK], capture_output=True).stdout.decode(
    "utf-8", errors="replace"
)
check("versionCode = 42", "versionCode='42'" in badging, badging[:200])
check("versionName = 2.19", "versionName='2.19'" in badging)
check("包名 com.melody.player", "package: name='com.melody.player'" in badging)
check("minSdk 26", "minSdkVersion:'26'" in badging)
check("targetSdk 36", "targetSdkVersion:'36'" in badging)
check(
    "不是上一版（versionCode 41 不在包里）",
    "versionCode='41'" not in badging,
    badging[:200],
)
check(
    "不是上一版（versionName 2.18 不在包里）",
    "versionName='2.18'" not in badging,
)

# ---------------------------------------------------------------- 2 签名
print("[2] 签名")
r = subprocess.run([APKSIGNER, "verify", "-v", "--print-certs", APK], capture_output=True)
out = r.stdout.decode("utf-8", errors="replace") + r.stderr.decode("utf-8", errors="replace")
check("apksigner verify 通过", r.returncode == 0, out.strip()[:200])
check("v2 签名方案在位", "Verified using v2 scheme (APK Signature Scheme v2): true" in out, out.strip()[:400])
check("证书 SHA-256 与既有发布一致 (b2af88a6…ff1a24)", "b2af88a6" in out.lower(), out.strip()[:400])

# ---------------------------------------------------------------- 3 dex
print("[3] dex 正向断言（新功能是否真的进包）")
z = zipfile.ZipFile(APK)
dex_names = [n for n in z.namelist() if n.endswith(".dex")]
blob = b"".join(z.read(n) for n in dex_names)
text = blob.decode("utf-8", errors="replace")
print("  " + ", ".join("%s %dKB" % (n, z.getinfo(n).file_size // 1024) for n in dex_names))

MUST_HAVE = [
    # P0-1 歌词偏移
    ("歌词偏移小条", "歌词偏移"),
    ("未校正", "未校正"),
    ("延后 N 秒", "延后 "),
    ("提前 N 秒", "提前 "),
    ("歌词延后 0.5 秒（无障碍）", "歌词延后 0.5 秒"),
    # P1-4 播放速度
    ("播放速度", "播放速度"),
    ("变速不变调说明", "变速不变调"),
    # P1-1 睡眠定时
    ("睡眠定时", "睡眠定时"),
    ("15 分钟", "15 分钟"),
    ("1.5 小时", "1.5 小时"),
    ("不到 1 分钟", "不到 1 分钟"),
    ("到点保留队列说明", "到点自动暂停并保留队列"),
    # P0-3 回访榜单
    ("最近播放", "最近播放"),
    ("最常听", "最常听"),
    ("全部播放", "全部播放"),
    # P0-4 设置页 IA
    ("首屏「常用」卡", "常用"),
    ("分组入口 曲库与文件", "曲库与文件"),
    ("分组入口 播放与外观", "播放与外观"),
    ("分组入口 在线内容", "在线内容"),
    ("分组入口 高级", "高级"),
    ("分组入口 关于", "关于"),
    # P0-2 歌词副本
    ("清理未关联副本", "清理未关联副本"),
    ("删除以上全部副本", "删除以上全部副本"),
    ("孤儿说明", "认不回歌曲的副本排在最后"),
    ("设置页全库口径", "首歌 · 共"),
    # P1-2 专辑 / 歌手
    ("未知专辑", "未知专辑"),
    ("未知歌手", "未知歌手"),
    ("专辑页 加入队列", "加入队列"),
    ("专辑页 返回曲库", "返回曲库"),
    # P1-5 搜索
    ("搜索占位符", "搜索标题、歌手或专辑"),
    ("最近搜索标签", "最近搜索"),
    ("忘掉某条历史词（无障碍）", "忘掉「"),
]

MUST_HAVE_CLASSES = [
    ("core/Albums（专辑聚合）", "Lcom/melody/player/core/Albums;"),
    ("core/Artists（歌手聚合）", "Lcom/melody/player/core/Artists;"),
    ("core/AlbumGroup", "Lcom/melody/player/core/AlbumGroup;"),
    ("core/ArtistGroup", "Lcom/melody/player/core/ArtistGroup;"),
    ("core/LyricOffset", "Lcom/melody/player/core/LyricOffset;"),
    ("core/PlaybackSpeed", "Lcom/melody/player/core/PlaybackSpeed;"),
    ("core/SleepTimer", "Lcom/melody/player/core/SleepTimer;"),
    ("core/PlayHistory", "Lcom/melody/player/core/PlayHistory;"),
    ("core/RecentSearches", "Lcom/melody/player/core/RecentSearches;"),
    ("core/TextHighlight", "Lcom/melody/player/core/TextHighlight;"),
    ("playback/SleepTimerHost", "Lcom/melody/player/playback/SleepTimerHost;"),
    ("screens/SettingsRootScreen", "Lcom/melody/player/ui/screens/SettingsRootScreenKt;"),
    ("screens/settings/SettingsCommon", "Lcom/melody/player/ui/screens/settings/SettingsCommonKt;"),
    ("screens/settings/SettingsLyricsPage", "Lcom/melody/player/ui/screens/settings/SettingsLyricsPageKt;"),
    ("screens/AlbumScreen", "Lcom/melody/player/ui/screens/AlbumScreenKt;"),
    ("screens/CoverGridCell", "Lcom/melody/player/ui/screens/CoverGridCellKt;"),
    ("screens/HistoryCards", "Lcom/melody/player/ui/screens/HistoryCardsKt;"),
]

for label, s in MUST_HAVE:
    check("文案在包内: " + label, s in text, s)

for label, s in MUST_HAVE_CLASSES:
    check("类在包内: " + label, s in text, s)

print()
if failures:
    print("核验未通过：%d / %d 项" % (len(failures), len(MUST_HAVE) + len(MUST_HAVE_CLASSES) + 10))
    for f in failures:
        print("  - " + f)
    sys.exit(1)
print("核验全部通过（正向 %d 条 + 版本/签名 10 条）" % (len(MUST_HAVE) + len(MUST_HAVE_CLASSES)))
