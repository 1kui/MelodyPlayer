# 旋律 · MelodyPlayer

一个纯本地优先的 Android 音乐播放器。Kotlin + Jetpack Compose + Media3 写的，没有账号、没有广告、没有后台统计，联网只发生在你主动点击「联网获取歌词 / 封面」的时候。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)](#环境要求)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF.svg)](https://developer.android.com/jetpack/compose)

**最新版本：[v2.7](https://github.com/1kui/MelodyPlayer/releases/latest)** · 下载 APK 直接安装（arm64-v8a / armeabi-v7a）

---

## 功能

### 播放

- Media3（ExoPlayer）播放内核，`MediaSessionService` 托管后台播放
- 通知栏 / 锁屏媒体卡片：封面、歌名、播放暂停、上下首
- 四种播放模式：顺序播放、列表循环、单曲循环、随机播放（随机与列表循环绑定，放完不会停）
- 全屏播放页支持**上下滑动切歌**（可在设置里关闭）
- 迷你播放条常驻底部，点开进全屏

### 曲库

- 扫描设备音频（MediaStore），另支持 SAF 导入外部文件
- 搜索、按多种字段排序
- **隐藏曲目**：不想要的歌从列表里拿掉，可随时恢复
- **归档**：把曲目复制进 App 专属目录统一管理（副本到手后原曲自动让位，不会留空库）
- 编辑歌曲信息（歌名 / 歌手 / 专辑），改动只作用于 App 内视图，不动原文件

### 歌词

支持多种来源，界面上会如实标注当前歌词来自哪里：

| 来源 | 说明 |
| --- | --- |
| 内嵌时间戳歌词 | ID3v2 `SYLT` / `USLT`、FLAC `LYRICS`，带时间轴则精确同步 |
| 内嵌纯文本 | 无时间轴时按歌曲时长自动均分对齐 |
| 同名 `.lrc` | 音频旁边放同名歌词文件即可 |
| 手动导入 | 从文件选择器导入 `.lrc` |
| **网易云** | 按歌名 + 歌手搜索，可选官方翻译合成双语 |
| **LRCLIB** | 按歌名 + 歌手 + 时长签名匹配 |

- 两个联网来源都可以在设置里**分别开关**
- 歌词可**写回音频文件标签**（ID3v2 / FLAC），换播放器也带着走
- 歌词副本管理：联网获取的歌词落在 App 内部，可查看 / 清理

### 专辑封面

- 优先用音频文件内嵌封面
- 内嵌没有时走 **iTunes Search API** 联网匹配，命中即缓存
- 可配置：**搜索地区链**（默认 中国台湾 → 中国香港 → 美国，最多选 4 个）、**匹配评分下限**（20–170，默认 90）
- 批量补封面时显示**确定式进度**：第 N / 总数 + 当前阶段（搜索候选 / 下载封面 / 写入缓存）+ 成功·无匹配·失败计数

### KWM 音乐解密

酷我客户端的加密文件（`.kwm`）可以直接导入：App 在本地解密后按**原格式**归档进曲库，不重新编码。

设置页有「KWM 音乐解密」分组，说明解密原理、扫描位置与文件命名规则。

### 外观

- 主题色可选（多种强调色）
- 封面形状可选（圆角 / 圆形 / 方形等）
- 歌词字号四档，行高与行距一起缩放
- Android 12+ 上支持真实毛玻璃（`RenderEffect`），低版本自动退化为半透明

### 设置页

曲库 · App 音乐库 · 已隐藏的曲目 · 歌词副本 · 播放 · 外观 · 歌词 · 专辑封面 · KWM 音乐解密 · 关于

---

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin，Jetpack Compose（Material 3） |
| 播放 | Media3 1.8.0（ExoPlayer + MediaSession） |
| 异步 | Kotlin Coroutines / Flow |
| 存储 | SharedPreferences（`Prefs`）+ 内部目录文件索引 |
| 标签解析 | 自研 ID3v2 / FLAC 解析器（`core/tags`），不引第三方标签库 |
| JSON | 自研极简解析器（`core/online/MiniJson`），不引 Gson/Moshi |
| 网络 | 标准 `HttpURLConnection` 封装（`SimpleHttp`），不引 OkHttp/Retrofit |
| 图标 | 全部手写 `ImageVector`，不引图标库 |
| 构建 | Gradle 8.9 + AGP 8.7.3，compileSdk 36 / minSdk 26 / targetSdk 36 |

依赖列表刻意保持极短——完整清单见 [`app/build.gradle.kts`](app/build.gradle.kts)，算上 Compose BOM 一共 15 条。

**没有任何原生代码**：工程里没有 C/C++，也没有任何自带的 `.so`（只保留 `abiFilters` 过滤依赖里带进来的那一个）。

---

## 环境要求

- JDK 17 或更高（验证过 Temurin 21）
- Android SDK：`compileSdk 36` 平台 + build-tools
- 构建时需设置 `JAVA_HOME` / `ANDROID_HOME`（或用 `local.properties` 指定 `sdk.dir`）

## 构建

```bash
# 编译并跑单测
./gradlew :app:testReleaseUnitTest

# 出包（产物在 app/build/outputs/apk/release/）
./gradlew :app:assembleRelease
```

### 关于签名

**仓库里不含任何密钥**。签名信息从下面两处按顺序读取：

1. 仓库根目录的 `keystore.properties`（已 gitignore，本地文件）
2. 环境变量 `MELODY_STORE_FILE` / `MELODY_STORE_PASSWORD` / `MELODY_KEY_ALIAS` / `MELODY_KEY_PASSWORD`

```properties
# keystore.properties
storeFile=keystore/melody.jks
storePassword=你的口令
keyAlias=你的别名
keyPassword=你的口令
```

**两处都没有也能构建**——只是产出未签名 APK，不会构建失败。想自己出包就用自己的密钥，别用别人的。

---

## 项目结构

```
app/src/main/java/com/melody/player/
├── MainActivity.kt          入口
├── MelodyApp.kt             Application，通知渠道等初始化
├── core/                    纯逻辑，无 Android UI 依赖（单测覆盖的主要区域）
│   ├── tags/                ID3v2 / FLAC 解析与写入、内嵌歌词提取
│   ├── kwm/                 KWM 容器解析与解密
│   ├── online/              网易云 / LRCLIB / iTunes 的 API 封装与匹配打分
│   ├── LrcParser.kt         LRC 解析与时间轴
│   └── PlayMode.kt 等
├── data/                    仓储层：曲库扫描、归档、歌词、封面、偏好
├── playback/
│   └── PlaybackService.kt   MediaSessionService
└── ui/
    ├── MelodyRoot.kt        根骨架 + 全局布局
    ├── player/              PlayerViewModel 与界面状态
    ├── screens/             曲库 / 播放页 / 队列 / 设置
    ├── components/          封面、进度条、迷你条、弹窗等
    └── icons/               手写矢量图标
```

`core/` 与 `data/` 不依赖 Compose，逻辑可以在 JVM 单测里直接跑——目前 **233 条单测**，覆盖 LRC 解析、标签读写、KWM 解码、歌词匹配、封面匹配打分、曲库查询与隐藏/归档规则等。

---

## 数据与隐私

- **不收集、不上报任何数据**，没有统计 SDK、没有崩溃上报、没有账号体系
- 联网只发生在你**主动触发**时：获取歌词、获取封面、获取翻译
- 请求直接发往对应服务：网易云音乐、[LRCLIB](https://lrclib.net)、iTunes Search API
- 全库扫描、歌词、封面缓存都在本机，卸载即清除
- 需要的权限：读取音频（`READ_MEDIA_AUDIO`）、前台服务（播放）、通知

## 已知限制

- 只做了真机在用的两种 ABI（`arm64-v8a` / `armeabi-v7a`），x86 模拟器需要自行加 `abiFilters`
- 发版未开启代码混淆（`isMinifyEnabled = false`），便于排查问题
- 顺序播放在底层映射为 `REPEAT_MODE_ALL`——这是为了绕开「队列走到最后一首时系统撤掉下一首按钮」的坑，代价是顺序播放放完一圈会从头继续，与列表循环的差别只剩界面图标
- 未做平板 / 横屏专门适配

## 免责声明

- 本项目仅供**个人学习与自用**。请支持正版音乐。
- KWM 解密功能用于在**你自己的设备上播放你已合法获取的本地文件**，不提供任何内容下载能力，也不绕过任何付费或订阅校验。
- 联网歌词与封面来自第三方公开接口，其内容的准确性与可用性不受本项目控制；请遵守对应服务的使用条款。
- 使用本项目产生的任何后果由使用者自行承担。

## 开源协议

[MIT](LICENSE) © 2026 1kui

## 致谢

- 网易云音乐、[LRCLIB](https://lrclib.net)、iTunes Search API 提供的公开接口
- [AndroidX Media3](https://github.com/androidx/media)、[Jetpack Compose](https://developer.android.com/jetpack/compose)
