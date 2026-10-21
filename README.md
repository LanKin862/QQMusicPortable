# 轻音 · QQMusicPortable

一个轻量的第三方 QQ 音乐 Android 客户端。原生 Kotlin + XML View，直接对接 QQ 音乐 Web / 桌面端使用的 HTTP 接口，支持在线播放、逐字歌词、歌单管理与离线缓存。

| 项目 | 值 |
| --- | --- |
| 应用名 | 轻音 |
| 包名 / applicationId | `com.portable.qqmusic` |
| 版本 | 1.1.0 (versionCode 2) |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| 语言 / UI | Kotlin 1.9.24，XML View |
| 构建 | AGP 8.1.4，Gradle 8.6，JDK 17 |
| 模块 | 单模块 `:app` |

> 本项目只做协议调用与播放器实现，不包含任何绕过付费、破解版权或解密受保护音频的行为。接口来自 QQ 音乐公开的 Web / 桌面端，**没有官方文档**，随时可能变更。请仅在符合 QQ 音乐用户协议的前提下用于个人学习与研究。

---

## 目录

- [1. 功能](#1-功能)
- [2. 安装与构建](#2-安装与构建)
- [3. 工程结构](#3-工程结构)
- [4. 存储与设置](#4-存储与设置)
- [5. API 接口文档](#5-api-接口文档)
- [6. 数据模型](#6-数据模型)
- [7. 已知限制](#7-已知限制)

---

## 1. 功能

### 1.1 页面

| 页面 | 主要能力 |
| --- | --- |
| 主界面 | 「推荐 / 我的」两个 Tab，底部常驻迷你播放条 |
| 推荐页 | 每日推荐、猜你喜欢、推荐歌单；下拉刷新；单块失败不影响其余 |
| 我的页 | 搜索入口、设置入口；收藏 / 本地 / 下载三张卡片；自建歌单与收藏歌单双 Tab；FAB 新建歌单 |
| 播放详情 | 封面页与歌词页左右滑动切换；配色跟随封面主色并自动压暗背景保证可读性 |
| 歌单详情 | 歌单、收藏、本地、下载共用一套界面；支持搜索过滤、排序、分页；多选后可批量播放、加入歌单、下载、删除、取消收藏或移出歌单 |
| 搜索 | 单曲 / 歌手 / 歌单三个 Tab，各自维护页码与「已到底」状态；记录最近搜索并可一键清除 |
| 歌手页 | 封面 + 信息流折叠结构，背景随封面主色变化；热门 / 最新排序；歌曲与专辑分页 |
| 设置 | 账号、在线音质、下载音质、播放过渡、音量平衡、响度适应、缓存上限与分层配额、下载位置、关于 |
| 登录 | 扫描二维码登录，登录态持久化 |

### 1.2 播放

- 顺序播放、单曲循环、列表循环、随机播放。
- 无缝切歌，或按设置使用淡入淡出过渡。
- 后台播放，支持锁屏与耳机线控。
- 音频焦点管理：来电等永久失去焦点时暂停，耳机拔出时暂停。
- 播放中边下边播，网络中断时该曲目暂停等待，不崩溃。
- 播放进度条仅在播放页可见时刷新。

### 1.3 歌词

- 支持 LRC 歌词与官方翻译。
- 支持逐字歌词（QRC），逐字高亮当前字。
- 歌词页可切换字号、开关翻译。
- 当前行扫色、上下行按距离缩放与淡出，滚动过渡平滑。

### 1.4 离线与缓存

- 离线下载串行执行，Android 10+ 下载到公共目录并注册到系统音乐库。
- 音频、封面、歌单数据、歌词四层缓存，各自独立配额。
- 本地音乐读取系统媒体库，不递归遍历文件系统；缺元信息时按「歌名 + 歌手」联网补一次封面与歌词。
- 切换账号时清空歌单与推荐缓存，避免显示上一个账号的数据。

---

## 2. 安装与构建

### 2.1 环境

| 项 | 要求 |
| --- | --- |
| JDK | 17 |
| Android SDK | `platforms;android-34`、`build-tools;34.0.0` |
| Gradle | 8.6（`gradle/wrapper` 已固定，无需本机安装） |
| `local.properties` | 需包含 `sdk.dir=<Android SDK 路径>`，该文件不入库 |

### 2.2 构建

```bash
# Debug
./gradlew assembleDebug --no-daemon          # Windows: .\gradlew.bat assembleDebug --no-daemon

# Release（已开启 R8 + shrinkResources）
./gradlew assembleRelease --no-daemon

# 单元测试 / Lint
./gradlew testDebugUnitTest
./gradlew lintDebug
```

产物：

| 变体 | 路径 |
| --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release.apk` |

### 2.3 签名

`app/build.gradle.kts` 中的 `signingConfigs.shared` 复用 `~/.android/debug.keystore`（口令均为 `android`），开启 v1 与 v2 签名，因此 `assembleRelease` 的产物可直接安装。正式发布前请替换为自己的 keystore，或删除 release 的 `signingConfig`。

### 2.4 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以把 APK 传到手机后手动安装。首次启动无需登录即可浏览与播放标准 / HQ 音质。

---

## 3. 工程结构

```
QQMusicPortable/
├── build.gradle.kts                 # 插件版本：AGP 8.1.4 / Kotlin 1.9.24
├── settings.gradle.kts              # rootProject.name、include(":app")
├── gradle.properties                # -Xmx2048m、parallel、caching、AndroidX
├── gradlew / gradlew.bat
└── app/
    ├── build.gradle.kts             # 签名、构建类型、依赖
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/portable/qqmusic/
        │   ├── App.kt               # 应用入口
        │   ├── core/                # 基础设施
        │   │   ├── Config.kt        # 端点、图片模板、UA、文件前缀
        │   │   ├── Http.kt          # HttpURLConnection 封装、全局 Cookie 池、Range 探测
        │   │   ├── Task.kt          # IO 线程池 / 串行池 / 主线程调度
        │   │   ├── Store.kt         # SharedPreferences 封装与变更监听
        │   │   ├── Settings.kt      # 设置项读写
        │   │   ├── Storage.kt       # 存储位置枚举与目录解析
        │   │   ├── Quality.kt       # 音质档位、播放模式、队列来源
        │   │   ├── Crypto.kt        # hash33 / g_tk / zzcSign / zzaSign / MD5
        │   │   ├── CookieUtil.kt    # Set-Cookie 解析与合并
        │   │   ├── J.kt             # org.json 空安全读取
        │   │   ├── Log.kt           # 日志（仅 Debug）
        │   │   ├── Diag.kt          # 启动诊断落盘
        │   │   ├── Contrast.kt      # WCAG 对比度与背景压暗
        │   │   └── AudioOutput.kt   # 当前输出通路与采样率
        │   ├── data/
        │   │   ├── api/             # QQ 音乐接口适配（见第 5 章）
        │   │   │   ├── MusicU.kt    # 模块化 CGI 网关客户端
        │   │   │   ├── Api.kt       # 搜索 / 歌曲详情 / 歌词 / 发现 / 歌单 / 专辑 / 榜单
        │   │   │   ├── PlayUrlApi.kt# vkey 获取、降级、Range 探测与下载
        │   │   │   ├── UserApi.kt   # 用户库、收藏、歌单增删改
        │   │   │   ├── Login.kt     # 登录态、cookie 归一化、g_tk
        │   │   │   ├── QrLogin.kt   # 二维码登录与 OAuth 换票
        │   │   │   ├── Lyrics.kt    # LRC 解析与翻译对齐
        │   │   │   ├── Qrc.kt / QrcDecrypt.kt / QrcDes.kt / QrcCache.kt / qrc/QrcSource.kt
        │   │   │   ├── SingerApi.kt # 歌手歌曲 / 专辑
        │   │   │   └── Models.kt    # Song / Playlist / Singer / UserProfile / SongUrl
        │   │   ├── CacheManager.kt  # 音频缓存、分层配额与清理
        │   │   ├── DownloadManager.kt# 离线下载（串行 + MediaStore）
        │   │   ├── MetaCache.kt     # 歌单 / 歌词等元数据落盘
        │   │   ├── RecommendRepository.kt
        │   │   ├── LibraryRepository.kt
        │   │   ├── LyricPrefetcher.kt
        │   │   ├── LocalMusic.kt    # 本地媒体库扫描与删除
        │   │   └── LocalMeta.kt     # 本地歌曲联网补充元信息
        │   ├── player/
        │   │   ├── PlayerController.kt   # 播放状态中枢（队列、模式、进度）
        │   │   ├── PlayerService.kt      # 前台服务、MediaSession、音频焦点
        │   │   ├── AudioEngine.kt        # MediaPlayer 生命周期与音质
        │   │   ├── GrowingFileDataSource.kt # 边下边播数据源
        │   │   ├── HttpMediaDataSource.kt   # HTTP 数据源
        │   │   ├── VolumeFade.kt
        │   │   ├── PlayerNotification.kt
        │   │   └── MediaButtonReceiver.kt
        │   └── ui/                  # Activity / Fragment / Adapter / 自定义 View
        └── res/                     # layout / values / values-en / xml / drawable / mipmap
```

---

## 4. 存储与设置

### 4.1 目录布局

```
app 私有目录/
├── files/diag/boot.log          # 启动诊断日志
├── cache/
│   ├── audio/                   # 音频缓存（key 为服务端返回的文件名）
│   ├── image/                   # 封面
│   ├── meta/                    # 歌单 / 推荐等列表数据
│   └── lyric/                   # 歌词原文 / 译文 / 逐字 QRC
└── shared_prefs/qqm_store       # 全部设置与登录态
```

缓存过期规则：歌单与推荐列表 12 小时，逐字歌词 30 天，音频不自动过期（只受配额限制）。

### 4.2 缓存配额

| 层 | 目录 | 默认配额 | 清理优先级 |
| --- | --- | --- | --- |
| 音频 | `cache/audio/` | 总上限 1 GB（可选 256 / 512 / 1024 / 2048 / 4096 MB / 不限） | 1（最先） |
| 封面 | `cache/image/` | 100 MB | 2 |
| 歌单数据 | `cache/meta/` | 100 MB | 3 |
| 歌词 | `cache/lyric/` | 100 MB | 4（最后） |

总容量超限时只清音频层；三层元数据仅在超过各自配额时清理。

### 4.3 下载位置

| 设置项 | 实际路径 |
| --- | --- |
| 应用内部存储（推荐） | 应用私有目录 `QQMusicPortable` |
| 应用外部存储（默认） | 外部私有目录 `QQMusicPortable` |
| 系统音乐目录 | `Music/QQMusicPortable` |
| 系统下载目录 | `Download/QQMusicPortable` |

选择后两项时，Android 10+ 通过 MediaStore 写入，Android 9 及以下直接写外部存储。

### 4.4 设置项

设置保存在单个 `qqm_store` SharedPreferences 中，主要键：

| 键 | 含义 |
| --- | --- |
| `cookie` / `uin` / `nick` / `avatar` / `vip_type` / `login_time` | 登录态 |
| `quality_online` / `quality_download` | 在线 / 下载音质 |
| `play_fade` / `volume_balance` / `loudness_adapt` | 播放过渡、音量平衡、响度适应 |
| `cache_limit_mb` / `cache_image_mb` / `cache_meta_mb` / `cache_lyric_mb` | 各层缓存配额 |
| `download_dir` | 下载位置 |
| `last_song` / `last_index` / `play_mode` / `queue_kind` | 播放队列恢复 |
| `tab_index` / `sort_<scope>` | 界面状态与歌单排序 |

---

## 5. API 接口文档

### 5.1 通用约定

**网关**

```
POST https://u.y.qq.com/cgi-bin/musicu.fcg
Content-Type: application/json
User-Agent: <桌面客户端 UA>
Referer:    https://y.qq.com/
```

UA 必须带桌面标识才能拿到 Hi-Res / 母带档位的 vkey：

```
Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko)
Chrome/98.0.4758.102 Safari/537.36 QQMusic/19.51.0
```

**请求体**

```json
{
  "comm": { "ct": 19, "cv": 1845, "uin": "o0123456789", "format": "json", "g_tk": 1234567890 },
  "req_0": {
    "module": "music.search.SearchCgiService",
    "method": "DoSearchForQQMusicDesktop",
    "param": { }
  }
}
```

- 键名可自定义为 `req_0` / `req_1` …，**响应按同名键返回**。
- 数组形式 `{"request": {...}}` 会返回 HTTP 200 但取不到模块数据，必须使用上面的结构。
- `ct` / `cv` 影响同一模块的返回内容：`ct=19`（桌面网页版）只返回 LRC，`ct=24`（App 端）才返回 QRC 逐字歌词。
- 登录后 `comm` 追加 `g_tk` 与 `g_tk_new_20200303`，值为 `gtk(skey)`。

**响应**

```json
{ "code": 0, "req_0": { "code": 0, "data": { } } }
```

判定：顶层 `code == 0` 且模块 `code == 0` 且 `data != null`。`data` 缺失时退回整个模块对象。

**签名算法**

```text
hash33(text):  e = 0
               for ch in text: e = (e + (e << 5) + ch.code) & 0x7FFFFFFF
               return e
gtk(skey)       = hash33(skey)              # 不要取模
zzcSign(data)   = md5(data + md5(data + "zzb@qq.com"))
zzaSign(query)  = md5("zza" + query)        # 旧版 fcgi
```

`hash33` 每一步都必须截断到 31 位，否则会溢出（JS 语义为 `2147483647 & e`）。

**Cookie**

- 一个 `Set-Cookie` 头里可能包含多个 cookie，按 `,(?=\s*[A-Za-z0-9_-]+=)` 拆分。
- 必须忽略「空值 + `Expires=Thu, 01 Jan 1970`」的删除标记：`check_sig` 会同时下发有效的 `p_skey` 与同名删除标记，空值会覆盖有效票据，表现为「扫码成功却登录不上」。
- 从网页复制的 cookie 通常只有 `qm_keyst` 与 `uin`，写接口还认 `loginUin`、`qqmusic_uin`、`qqmusic_key`，缺失会回 `40000`。`Login.normalizeCookie()` 负责补齐派生字段。

### 5.2 接口总表

| 用途 | 模块 | 方法 | 需登录 |
| --- | --- | --- | --- |
| 搜索（单曲/歌手/专辑/歌单/歌词/用户） | `music.search.SearchCgiService` | `DoSearchForQQMusicDesktop` | 否 |
| 歌曲详情（批量补全） | `music.pf_song_detail_svr` | `get_song_detail_yqq` | 否 |
| 歌词（LRC + 翻译） | `music.musichallSong.PlayLyricInfo` | `GetPlayLyricInfo` | 否 |
| 逐字歌词（QRC） | `music.musichallSong.PlayLyricInfo` | `GetPlayLyricInfo`（`crypt=1`） | 否 |
| 播放地址 | `vkey.GetVkeyServer` | `CgiGetVkey` | 否（会员档位需登录） |
| 推荐货架 / 每日30首定位 | `music.recommend.RecommendFeed` | `get_recommend_feed` | 否 |
| 雷达（个性化推荐） | `music.recommend.RecommendFeed` | `get_recommend_feed` | 是 |
| 猜你喜欢 / 电台 | `music.radioProxy.MbTrackRadioSvr` | `get_radio_track` | 否 |
| 排行榜列表 / 详情 | `musicToplist.ToplistInfoServer` | `GetAll` / `GetDetail` | 否 |
| 歌单详情 | `music.srfDissInfo.DissInfo` | `CgiGetDiss` | 部分 |
| 推荐歌单（歌单广场） | legacy `c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg` | GET | 否 |
| 歌单详情（legacy 兜底 / 收藏歌单） | legacy `c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg` | GET | 部分 |
| 专辑信息 / 歌曲 | `music.musichallAlbum.AlbumInfoServer` / `AlbumSongList` | `GetAlbumDetail` / `GetAlbumSongList` | 否 |
| 歌手歌曲 | `musichall.song_list_server`（无 `music.` 前缀） | `GetSingerSongList` | 否 |
| 歌手专辑 | `music.musichallAlbum.AlbumListServer` | `GetAlbumList` | 否 |
| 用户资料 | `music.UserInfo.userInfoServer` | `GetLoginUserInfo` | 是 |
| 自建歌单列表 | legacy `c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss` | GET | 是 |
| 收藏歌单 id 列表 | `music.musicasset.PlaylistFavRead` | `GetPlaylistFavList` | 是 |
| 收藏 / 取消收藏歌曲 | `music.musicasset.PlaylistDetailWrite` | `AddSonglist` / `DelSonglist` | 是 |
| 是否已收藏 | `music.musicasset.SongFavRead` | `IsSongFanByMid` | 是 |
| 新建 / 删除歌单 | `music.musicasset.PlaylistBaseWrite` | `AddPlaylist` / `DelPlaylist` | 是 |
| 收藏 / 取消收藏歌单 | `music.musicasset.PlaylistFavWrite` | `FavPlaylist` / `CancelFavPlaylist` | 是 |
| 二维码申请 / 轮询 | `ssl.ptlogin2.qq.com/ptqrshow` / `ptqrlogin` | GET | 否 |
| 用 OAuth code 换音乐票据 | `QQConnectLogin.LoginServer` | `QQLogin` | 是（持 code） |

### 5.3 搜索

```json
{
  "module": "music.search.SearchCgiService",
  "method": "DoSearchForQQMusicDesktop",
  "param": {
    "num_per_page": 20,
    "page_num": 1,
    "query": "关键词",
    "search_type": 0
  }
}
```

- `param` 必须**平铺**，包在 `body` / `data` 两层里会取不到数据。
- `search_type`：`0` 单曲、`1` 歌手、`2` 专辑、`3` 歌单、`7` 歌词、`8` 用户；`5` / `6` / `9` 无数据。
- 响应路径：`req_0.data.body.{song|singer|album|songlist}.list`。
- 服务端九个键都在，但只有对应类型有数据，所以三个 Tab 必须各发一次请求。
- 旧版歌单搜索 `client_music_search_songlist` 仍可用（`remoteplace=txt.yqq.playlist`，`page_no` 从 0 开始）。

### 5.4 歌曲详情（批量）

```json
{
  "module": "music.pf_song_detail_svr",
  "method": "get_song_detail_yqq",
  "param": {
    "songinfo": [
      { "songmid": "001JOLTf4UlH6q", "songid": 123456, "songtype": 0,
        "filename": "C400001JOLTf4UlH6q001JOLTf4UlH6q.m4a" }
    ],
    "comm": { "ct": 24, "cv": 0 }
  }
}
```

- 单次最多 100 首。
- 响应取 `data.track_info.songinfo`（兼容 `trackInfo` / `songinfo`）。
- 列表接口常缺少时长、专辑名与封面 `albumMid`，可用此接口批量补全。

### 5.5 歌词（LRC + 翻译）

```json
{
  "module": "music.musichallSong.PlayLyricInfo",
  "method": "GetPlayLyricInfo",
  "param": {
    "songmid": "001JOLTf4UlH6q",
    "songtype": 0,
    "format": "json",
    "nobase64": 1,
    "trans": 1,
    "trans_t": 0
  }
}
```

- **必须传 `trans=1`** 才有官方翻译。
- **不能加 `qrc=1` / `qrc_t=0`**，否则 `lyric` 整体变成二进制密文，界面表现为「暂无歌词」，且不会报错。
- 返回的 `lyric` / `trans` 即使 `nobase64=1` 仍是 base64；以「是否含 `[` `]`」判断明文，否则按 base64 解。

### 5.6 逐字歌词（QRC）

同一 module，参数追加：

```json
{ "songmid": "…", "songtype": 0, "format": "json", "nobase64": 1,
  "crypt": 1, "qrc": 1, "qrc_t": 0, "trans": 1, "trans_t": 0 }
```

- 密文可能在 `qrc` 字段，也可能在 `lyric` 字段，两处都要尝试。
- 解密方式：定制 3DES + zlib。
- 解出的 XML 按 `<line>` 解析，包含逐字 `startMs` / `durationMs`。
- 失败时回退 LRC。

### 5.7 播放地址（vkey）

```json
{
  "module": "vkey.GetVkeyServer",
  "method": "CgiGetVkey",
  "param": {
    "guid": "10000",
    "songmid": ["001JOLTf4UlH6q"],
    "songtype": [0],
    "uin": "0123456789",
    "loginflag": 1,
    "platform": "20",
    "filename": ["F000001JOLTf4UlH6q001JOLTf4UlH6q.flac"]
  }
}
```

- 文件名规则：`<前缀><songmid><songmid><扩展名>`。
- 响应：`req_0.data.midurlinfo[0]` 的 `purl` 与 `result`；`purl` 为空或 `result != 0` 视为该档失败。
- 域名：`req_0.data.sip[]` 取首个非空项（补 `/`，兜底 `https://dl.stream.qqmusic.qq.com/`），`purl` 不以 `http` 开头时拼接。
- `isTrial = (pneed == 1 && pneedbuy == 1)`，表示当前只拿到试听片段。

**音质与降级**

| 档位 | 前缀 | 扩展名 | 权限 |
| --- | --- | --- | --- |
| 标准品质 | `M500` | `.mp3` | 普通用户 |
| HQ 高品质 | `M800` | `.mp3` | 普通用户 |
| SQ 无损 | `F000` | `.flac` | 需绿钻 |
| Hi-Res 无损 | `RS01` | `.flac` | 需 SVIP |

降级顺序 `Hi-Res → SQ → HQ → 标准`，全失败后再试一次标准档。下载只尝试目标档位。

**坏链探测**：`purl` 非空且 `result == 0` 仍可能返回 HTTP 404，因此每档需用 Range `bytes=0-0` 探测一次真实可用性，并记住上次成功的档位。

### 5.8 发现页

**每日 30 首**——不是雷达接口，而是一个真实歌单：

1. `RecommendFeed / get_recommend_feed`（空 `param`）取 `data.v_shelf[].v_niche[].v_card[]`；
2. 找 `title` 含「每日30首」的卡片，取 `id`（每天变化）；
3. 再用 `CgiGetDiss { disstid: <id>, dirid: 0, song_begin: 0, song_num: 50, tag: 1 }` 取 `songlist`。

**猜你喜欢**

```json
{ "module": "music.radioProxy.MbTrackRadioSvr", "method": "get_radio_track",
  "param": { "id": 99, "num": 30 } }
```

取 `data.tracks`；失败回退新歌榜 `topId = 27`。

**雷达（需登录）**

```json
{ "PageSize": 20, "RequestTime": 1730000000, "Uin": "…", "GetTaste": 1,
  "Scene": 1, "Data": "2026-10-19" }
```

`key = "feed"`，取 `v_list[]`（兼容 `vecList`），每项可能是 `{track}` 或曲目本身。

**排行榜**

- `GetAll` → `data.group[].toplist[]`（`topId` / `title` / `frontPicUrl` / `picUrl` / `songCnt` / `listenNum`）。
- `GetDetail { topListId, offset: 0, num }`，`key = "top"`，歌曲在 `songInfoList`。
- `TOP_HOT = 26`（热歌榜，**不是 4**）、`TOP_NEW = 27`。

**推荐歌单（歌单广场，legacy）**

```
GET https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg
    ?picmid=1&rnd=0.1&g_tk=5381&loginUin=0&hostUin=0&format=json
    &inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0
    &categoryId=10000000&sortId=5&sin=0&ein=11
Referer: https://y.qq.com/
```

取 `data.list[]`，未登录也可用。musicu 的 `DissInfo { id, num, page, order }` 会回 `param error(-100008)`。

### 5.9 歌单

**`CgiGetDiss`**

```json
{ "module": "music.srfDissInfo.DissInfo", "method": "CgiGetDiss",
  "param": { "disstid": 123456, "dirid": 0, "song_begin": 0, "song_num": 500, "tag": 1 } }
```

- 普通歌单用 `disstid`，`dirid = 0`。
- **用户目录（我喜欢 / 自建）必须 `disstid = 0`**，用 `dirid` 定位；传 `disstid = 201` 回 `code = 10004`。
- 单次最多返回 500 首，超过必须用 `song_begin` 翻页；**页码要按「原始条目数」推进**，用有效歌曲数会漏歌并误判最后一页（表现为歌单卡在 499 首）。
- 收藏的别人歌单不认 `tid`（回 `param error`），必须走下面的 legacy。

**legacy 歌单详情 / 收藏歌单**

```
GET https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg
    ?type=1&json=1&utf8=1&onlysong=0&disstid=<tid>&format=json&g_tk=5381
    &loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0
    &platform=yqq.json&needNewCode=0
Referer: https://y.qq.com/
```

取 `cdlist[0].songlist`。**认 `tid`，不认真实 `dissid`**——两个 id 语义不同，须分别保存，混用会导致「收藏歌单点进去没有歌曲」。

**歌单元信息的三套命名**

| 来源 | 字段 |
| --- | --- |
| legacy 用户目录 | `diss_name` / `diss_cover` / `song_cnt` / `listen_num` / `dirid`（`dissid`、`dissname` 为 null） |
| musicu | `dirName` / `songNum` / `dirId` |
| 普通歌单 | `dissname` / `songnum` / `dissid` |

**专辑**

- `AlbumInfoServer / GetAlbumDetail`，参数是 **`albumMId`（大写 `I`）**；写 `albumMid` 会回 200 但 `data` 为空。取 `basicInfo.albumName`。
- `AlbumSongList / GetAlbumSongList { albumMid, begin: 0, num: 200 }` → `songList[].songInfo`。

### 5.10 歌手

**歌曲**（模块名**没有** `music.` 前缀，参数是 `number` 不是 `num`，每页上限 30）

```json
{ "module": "musichall.song_list_server", "method": "GetSingerSongList",
  "param": { "singerMid": "0025NhlN2yWrP4", "order": 1, "number": 30, "begin": 0 } }
```

`order`：`1` 热门、`0` 最新。取 `songList[].songInfo` 与 `totalNum`。

**专辑**

```json
{ "module": "music.musichallAlbum.AlbumListServer", "method": "GetAlbumList",
  "param": { "singerMid": "…", "order": 1, "number": 30, "begin": 0 } }
```

取 `albumList[]`（`albumMid` / `albumName` / `publishDate` / `albumType`）。

**歌手头像**：使用搜索结果自带的 `avatar` 完整 URL，不能自行拼接 `T001R300x300M000{mid}.jpg`（mid 常不一致，会 404）。多人歌手取 `/` 前的第一个名字。

### 5.11 用户库（需登录）

**用户资料**：`GetLoginUserInfo`，昵称取 `data.info.nick`。

**我喜欢 / 用户目录取歌**

```json
{ "module": "music.srfDissInfo.DissInfo", "method": "CgiGetDiss",
  "param": { "disstid": 0, "dirid": 201, "song_begin": 0, "song_num": 300, "tag": 1 } }
```

`dirid = 201` 即「我喜欢」。`dirid = 205`（QZone 背景音乐）、`206`（本地上传）属于系统目录，展示层过滤。

**自建歌单（legacy）**

```
GET https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss
    ?hostUin=0&hostuin=<uin>&sin=0&size=200&g_tk=<gtk>&loginUin=0
    &format=json&inCharset=utf8&outCharset=utf-8&notice=0
    &platform=yqq.json&needNewCode=0
Referer: https://y.qq.com/portal/profile.html
```

取 `data.disslist[]`（兼容 `data.list`），字段见 5.9 的下划线命名。

**收藏歌单（两步）**

1. `PlaylistFavRead / GetPlaylistFavList { uin, offset: 0, size: 60 }` → `data.v_tids[]`（纯 tid）；
2. 每个 tid 调 legacy `fcg_ucc_getcdinfo_byids_cp.fcg?disstid=<tid>` 取 `cdlist[0]`。

**是否已收藏单曲**

```json
{ "module": "music.musicasset.SongFavRead", "method": "IsSongFanByMid",
  "param": { "v_songMid": ["001JOLTf4UlH6q"] } }
```

取 `data.m_fan[mid]`。也可翻页收集「我喜欢」全部 mid 做整表缓存。

### 5.12 写操作（需登录 + 正确 `g_tk`）

**收藏 / 取消收藏歌曲**

```json
{ "module": "music.musicasset.PlaylistDetailWrite", "method": "AddSonglist",
  "param": { "dirId": 201, "v_songInfo": [{ "songType": 0, "songId": 123456 }] } }
```

`DelSonglist` 参数相同。`g_tk` 必须用 `qm_keyst` 计算且不得取模，否则恒回 `80105`。

> 某些账号下该写入会被服务端单独拦截（恒回 `80105`），此时应用会用隐形 WebView 加载网页版歌曲页完成收藏，签名交给网页计算。

**新建歌单**

```json
{ "module": "music.musicasset.PlaylistBaseWrite", "method": "AddPlaylist",
  "param": { "dirName": "歌单名", "dirDesc": "", "dirPicUrl": "", "taglist": [] } }
```

**删除歌单**

```json
{ "module": "music.musicasset.PlaylistBaseWrite", "method": "DelPlaylist",
  "param": { "dirId": 15 } }
```

参数是 **`dirId`**，不是 `disstid` / `tid`。

**收藏 / 取消收藏歌单**

```json
{ "module": "music.musicasset.PlaylistFavWrite", "method": "FavPlaylist",
  "param": { "uin": "…", "v_playlistId": [123456] } }
```

取消用 `CancelFavPlaylist`。响应 `{ code: 0, data: { result: 0, v_failedPlaylistId: [] } }`，成功条件为模块 `code == 0` 且失败列表为空。

**歌单内是否已有某首歌**：服务端没有查询接口，只能翻页比对 mid；`AddSonglist` 对重复项未必报错。

### 5.13 登录

1. **申请二维码**（只发一次请求，PNG 与 `qrsig` 同时取）

   ```
   GET https://ssl.ptlogin2.qq.com/ptqrshow
       ?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=0.<ms>&daid=383&pt_3rd_aid=100497308
   Referer: https://xui.ptlogin2.qq.com/
   ```

   `ptqrtoken = hash33(qrsig)`。分两次请求会拿到两张不同的码，扫码后服务端永远回 `66`。

2. **轮询**

   ```
   GET https://ssl.ptlogin2.qq.com/ptqrlogin
       ?u1=<enc https://graph.qq.com/oauth2.0/login_jump>&ptqrtoken=<hash33(qrsig)>
       &ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-<ms>
       &js_ver=20102616&js_type=1&login_sig=&pt_uistyle=40
       &aid=716027609&daid=383&pt_3rd_aid=100497308&has_onekey=1
   ```

   响应形如 `ptuiCB('66','0','','0','二维码未失效。','')`：

   | code | 含义 |
   | --- | --- |
   | `65` | 二维码已失效 |
   | `66` | 未扫描 |
   | `67` | 已扫描，待确认 |
   | `0` | 成功，第 3 个参数是跳转 URL |

   解析跳转 URL 必须用正则 `https?://[^'"\s]+`。

3. **手动逐跳跟随 302**，每跳的 `Set-Cookie` 立即并入 Cookie 池。自动跟随只会保留最终响应的 cookie，会丢掉 `p_skey` / `uin`。

4. **换 OAuth code**：先 GET `graph.qq.com/oauth2.0/show`（`client_id=100497308`、`redirect_uri=https://y.qq.com/portal/wx_redirect.html?login_type=1`），若 302 或 body 带 code 直接返回；否则 POST `graph.qq.com/oauth2.0/authorize`，表单带 `g_tk`，从 Location 取 `code=([^&\s"'<>]+)`。`g_tk` 依次尝试 `hash33(p_skey) % 1e6`、`hash33(p_skey) & 0x7FFFFFFF`、同法 `skey`、`5381`；`error=100046` 时只有 `5381` 能通过。

5. **换音乐票据**

   ```json
   { "comm": { "g_tk": 5381, "platform": "yqq", "ct": 24, "cv": 0,
               "uin": "…", "format": "json" },
     "req": { "module": "QQConnectLogin.LoginServer", "method": "QQLogin",
              "param": { "code": "<OAuth code>" } } }
   ```

   **要的是 OAuth `code`，不是 `p_skey`**（传 `p_skey` 会回 `1000` / `10006`）。取 `data` 里的 `musickey` / `musicid` / `openid` / `access_token` / `refresh_key`。

6. **构造域 cookie 并校验**：`uin=o<uin>`、`loginUin`、`qqmusic_uin`、`qqmusic_key` / `qm_keyst` / `musickey`、`openid`、`access_token`、`refresh_key`，写入本地后调用 `refreshProfile()` 验证。

### 5.14 错误码与常见陷阱

| 现象 | 原因 | 正确做法 |
| --- | --- | --- |
| HTTP 200 但所有模块无数据 | 请求体用了 `{"request": …}` 数组格式 | `{"comm":…, "req_N":{…}}`，响应取同名键 |
| `param error(-100008)` | 推荐歌单用了 `DissInfo{id,…}` | legacy `fcg_get_diss_by_tag.fcg` |
| `code = 10004` | 用户目录传了 `disstid` | `disstid = 0`，用 `dirid` |
| `code = 40000` | 缺 `loginUin` / `qqmusic_uin` / `qqmusic_key`，或 `g_tk` 用错票据 | 补齐 cookie，`g_tk` 用 `qm_keyst` |
| `code = 80105` | 写「我喜欢」的 CSRF 校验不通过 | 修正 `g_tk`；仍失败走 WebView 兜底 |
| `error = 100046` | `authorize` 的 `g_tk` 基于票据 | 使用 `5381` |
| 扫码成功但登录不上 | cookie 删除标记覆盖有效 `p_skey` | 忽略空值 + 1970 过期 |
| 收藏歌单无歌曲 | 用 `dissid` 去问 legacy | legacy 认 `tid` |
| 歌单只加载到 499 首 | 用有效歌曲数推进 `song_begin` | 用原始条目数推进 |
| 专辑 `data` 为空 | 参数写 `albumMid` | 参数名是 `albumMId` |
| 歌手歌曲只有十几首 | 按歌手名搜单曲再过滤 | 改用 `GetSingerSongList`，每页 30 |
| 歌手头像 404 | 用 mid 拼 `T001…` | 使用搜索结果自带的完整 `avatar` |
| 歌词乱码 / 「暂无歌词」 | 普通歌词请求带了 `qrc=1` | 普通歌词与 QRC 分开发 |
| 没有官方翻译 | 缺 `trans=1` | 固定带 `trans=1&trans_t=0` |
| 播放报 `onError(-38)` | 直链交给播放器或未预先获知总长 | 边下边播 + 提前 Range 探测 |
| 页面局部白屏 | 迷你播放条传了根布局 | 传 `R.id.mini_player` |
| 缓存永不命中 | 缓存 key 含 query | `substringAfterLast('/').substringBefore('?')` |

---

## 6. 数据模型

`data/api/Models.kt` 统一解析：QQ 音乐在不同接口里用 `songmid` / `mid` / `songMid` 三种写法指同一字段，一律通过 `Song.fromJson()` 转换。

| 类型 | 关键字段 |
| --- | --- |
| `Song` | `mid`、`name`、`singer`、`singerMids`、`albumMid`、`albumName`、`durationSec`、`songId`、`vipOnly`、`payPlay`、`fileSizeFlac`、`fileSizeMp3`、`index`、`type`、`localPath`、`localCover` |
| `Playlist` | `id`、`dirid`、`disstid`、`tid`、`title`、`cover`、`songCount`、`playCount`、`creator`、`creatorUin`、`desc`、`isSelfCreated`、`type` |
| `Singer` | `mid`、`name`、`avatar`、`songCount`、`albumCount`、`mvCount` |
| `UserProfile` | 昵称、头像、`vipType` |
| `SongUrl` | `url`、`quality`、`fileName`、`fileSize`、`isTrial` |
| `RecommendCard` | `id`、`title`、`subtitle`、`cover`、`playlistId`、`songs` |
| `Lyrics.Document` | `lines`（`timeMs` / `text` / `translation` / 逐字偏移）、`indexAt(positionMs)` |
| `Qrc.Line` | `words: List<Word>`（`text`、`startMs`、`durationMs`）、`startMs`、`endMs` |

`Song` 的字段语义：

- `isLocal`（`type == TYPE_LOCAL`）**不能**用来判断「是否有在线 mid」——下载的歌曲是本地文件但保留原曲 mid，应使用 `hasRealMid`（排除 `local_` / `file_` 前缀）。
- 本地音乐必须显式保存文件路径 `localPath`：`MediaPlayer` 只接受文件路径，传 `content://` URI 会被当成不存在的路径。
- 本地封面用 MediaStore 的 `ALBUM_ID` 拼 `content://media/external/audio/albumart/<albumId>`。

---

## 7. 已知限制

1. **接口无官方文档**，服务端改字段、加签名或限频都会导致功能失效。
2. **收藏单曲存在账号级拦截**：明文 musicu 写入可能恒回 `80105`，此时依赖 WebView 兜底，速度较慢且需要网络良好。
3. **歌单 id 语义分裂**：`dirid`（用户目录）、`disstid`（普通歌单）、`tid`（收藏关系）三者不可混用。
4. **边下边播依赖本地文件增长**：极端慢网下会停顿；缓存未命中且网络中断时该曲目不可播。
5. **必须允许明文 HTTP**，否则头像、部分封面与播放直链全部失败。
6. 未登录可播放标准 / HQ；SQ 与 Hi-Res 需要对应会员，否则自动降级或提示无版权。
7. 只在 Android 7.0 与少量模拟器上完整测试过，更高版本已按 MediaStore 适配，但未做全机型覆盖。

---

## 许可

本项目仅用于个人学习与技术研究，与腾讯 / QQ 音乐官方无关。接口、曲库与商标归其各自权利人所有。
