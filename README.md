# 轻音 · QQMusicPortable

一个轻量的第三方 QQ 音乐 Android 客户端。原生 Kotlin + XML View，不依赖 Compose、OkHttp、Retrofit 与协程，全部接口直连 QQ 音乐 WEB / 桌面客户端使用的 HTTP 接口。

| 项目 | 值 |
| --- | --- |
| 应用名 | 轻音 |
| 包名 / applicationId | `com.portable.qqmusic` |
| 版本 | 1.1.0 (versionCode 2) |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| 语言 / UI | Kotlin 1.9.24，XML View + findViewById |
| 构建 | AGP 8.1.4，Gradle 8.6，JDK 17 |
| 产物体积 | Debug ≈ 6.5 MB，Release ≈ 2.25 MB（R8 + 资源压缩） |
| 模块 | 单模块 `:app` |

> 本项目只做协议调用与播放器实现，不包含任何绕过付费、破解版权或解密受保护音频的行为。接口来自 QQ 音乐公开的 Web / 桌面端，**没有官方文档**，随时可能变更。请仅在符合 QQ 音乐用户协议的前提下用于个人学习与研究。

---

## 目录

- [1. 功能](#1-功能)
- [2. 技术选型与设计取舍](#2-技术选型与设计取舍)
- [3. 工程结构](#3-工程结构)
- [4. 构建与运行](#4-构建与运行)
- [5. 架构设计](#5-架构设计)
- [6. API 接口文档](#6-api-接口文档)
- [7. 数据模型](#7-数据模型)
- [8. 本地存储与缓存](#8-本地存储与缓存)
- [9. 调试与诊断](#9-调试与诊断)
- [10. 已知限制](#10-已知限制)
- [11. 开发日志](#11-开发日志)

---

## 1. 功能

### 1.1 页面

| 页面 | 实现 | 主要能力 |
| --- | --- | --- |
| 主界面 | `MainActivity` + `BottomNavigationView` | 「推荐 / 我的」两个 Fragment，用 `show/hide` 切换，保留滚动位置与已加载数据；底部常驻迷你播放条 |
| 推荐页 | `RecommendFragment` | 每日推荐、猜你喜欢、推荐歌单三块；任意一块失败不影响其余；下拉刷新；每块最多 6 行 |
| 我的页 | `MineFragment` | 顶部搜索框 + 设置入口；「收藏 / 本地 / 下载」三张卡片；自建歌单与收藏歌单双 Tab；FAB 新建歌单 |
| 播放详情 | `NowPlayingActivity` | ViewPager2 双页：封面页（封面 + 歌名 + 歌手 + 功能键）与歌词页；配色跟随封面主色，并按 WCAG 2.1 压暗背景保证可读性 |
| 歌单 / 收藏 / 本地 | `PlaylistActivity` | 同一套 UI，用 `Extras.EXTRA_SOURCE` 区分数据来源；支持搜索过滤、排序、分页加载、批量下载、删除 |
| 搜索 | `SearchActivity` | 单曲 / 歌手 / 歌单三个 Tab，对应同一接口的三种 `search_type`，各自记页码与「已到底」 |
| 歌手 | `SingerActivity` | 上半屏封面 + 下方信息流折叠结构；热门 / 最新两个排序；歌曲与专辑分页 |
| 设置 | `SettingsActivity` | Preference 实现：账号、音质、播放过渡、音量平衡/响度、缓存上限与分层配额、下载位置、关于 |
| 登录 | `LoginActivity` | 二维码登录，2 秒轮询，成功后后台换取 `qm_keyst` 并持久化 |

### 1.2 播放

- 单个 `MediaPlayer` 实例复用，无缝播放使用平台 `setNextMediaPlayer`（硬解接力，无二次解码）。
- 过渡效果两档：无缝 / 淡入淡出。API 26+ 用 `VolumeShaper`，低版本用 400ms / 8 步音量爬坡。
- 后台播放：前台服务 + MediaStyle 通知 + `MediaSessionCompat`，支持锁屏与耳机线控。
- 音频焦点与耳机拔出处理；来电等永久失去焦点时暂停。
- 播放中边下边播：下载线程写 `.part` 文件，`GrowingFileDataSource.readAt()` 只读本地文件，避免解码线程碰网络。
- 播放进度轮询仅在播放页可见时以 500ms 运行。
- 通知使用固定 id + 复用 Builder，`setOnlyAlertOnce`，避免每次刷新重建 RemoteViews。

### 1.3 歌词

- LRC 解析（含翻译对齐）、逐字歌词 QRC（定制 3DES + zlib 解密）。
- 自绘 `LyricsView`：只画当前行上下各三行，按距离衰减缩放与透明度，当前行扫色，换行用临界阻尼弹簧过渡；每帧只维护三个标量，不在帧循环里分配对象。
- 起播时预取「当前 + 后几首」歌词，独立单线程低优先级执行，不占用 IO 池。

### 1.4 离线与缓存

- 下载串行执行，Android 10+ 通过 MediaStore 注册到公共目录，系统音乐库可扫描到。
- 缓存以「服务端返回的文件名」为 key（`M500/M800/F000/RS01` 前缀），不同音质互不覆盖。
- 四层缓存配额：歌曲 / 封面 / 歌单数据 / 歌词，总量超限时**先清歌曲缓存**，其余三层只在超过自身配额时清理。
- 本地音乐只读 MediaStore，不递归遍历文件系统；本地文件缺少 mid 时按「歌名 + 歌手」向接口借一次元信息（封面 / 歌词），结果持久化。
- 换账号登录时清空歌单与推荐缓存，避免闪出上一个账号的数据。

---

## 2. 技术选型与设计取舍

| 方面 | 选择 | 替代方案与取舍 |
| --- | --- | --- |
| UI | Kotlin + XML View，`findViewById` | 不用 Compose：冷启动更快、APK 更小、无需额外编译器插件 |
| 网络 | `HttpURLConnection`（`core/Http.kt`） | 不用 OkHttp/Retrofit：省约 800 KB 依赖与连接池/拦截器对象；连接复用交给 JVM `KeepAliveCache` |
| 异步 | 自建 4 线程 IO 池 + 单线程串行池 + 主线程 Handler（`core/Task.kt`） | 不用协程：省约 1.5 MB 依赖与类加载开销 |
| JSON | 平台内置 `org.json`，外包一层 `core/J.kt` | 不用 Gson/Moshi/kotlinx.serialization：零反射、零额外依赖 |
| 图片 | Glide 4.16.0 | 限制内存缓存比例，磁盘缓存复用系统缓存目录 |
| 二维码 | ZXing `core`（约 500 KB） | 不引入 `android-embedded`，避免 Activity 注入 |
| 封面取色 | `androidx.palette` + 自研 `core/Contrast.kt` | 固定三档白色文字色板，反过来把背景压暗到满足对比度 |
| 资源 | `resourceConfigurations += ["zh", "en"]` | 只保留中英文，减小 APK 与资源解析开销 |
| 通知 | 固定通知 id + 复用 Builder | 避免频繁重建 RemoteViews 造成闪烁与 GC |
| 播放 | 单 `MediaPlayer` + `setNextMediaPlayer` | 不用 ExoPlayer：体积与解码开销更大，本项目只需要顺序播放与无缝接力 |

其他约定：

- `minSdk 24`；API 29+ 写入公共目录走 MediaStore，API 28- 直接写外部存储（`requestLegacyExternalStorage="true"`）。
- 明文 HTTP 必须允许：头像 `http://thirdqq.qlogo.cn`、CDN `http://y.gtimg.cn`、部分播放直链都是 http，见 `res/xml/network_security_config.xml`。
- 不启用 Compose / ViewBinding / DataBinding，`buildConfig = true`。

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
        │   ├── App.kt               # 入口：Diag → Store → Login → 恢复播放队列
        │   ├── core/                # 与业务无关的基础设施
        │   │   ├── Config.kt        # 端点、图片模板、UA、文件前缀
        │   │   ├── Http.kt          # HttpURLConnection 封装、全局 Cookie 池、Range 探测
        │   │   ├── Task.kt          # IO 池 / 串行池 / 主线程
        │   │   ├── Store.kt         # 单一 SharedPreferences + 变更监听
        │   │   ├── Settings.kt      # 设置门面（类型安全读写）
        │   │   ├── Storage.kt       # 存储位置枚举与目录解析
        │   │   ├── Quality.kt       # 音质档位、播放模式、队列来源
        │   │   ├── Crypto.kt        # hash33 / g_tk / zzcSign / zzaSign / MD5
        │   │   ├── CookieUtil.kt    # Set-Cookie 解析与合并
        │   │   ├── J.kt             # org.json 空安全读取
        │   │   ├── Log.kt           # 仅 Debug 输出
        │   │   ├── Diag.kt          # 启动诊断落盘（应对 logcat 被 ROM 关闭）
        │   │   ├── Contrast.kt      # WCAG 对比度与背景压暗
        │   │   └── AudioOutput.kt   # 当前输出通路与采样率（是否为「直通」）
        │   ├── data/                # 数据仓库与本地能力
        │   │   ├── api/             # 所有 QQ 音乐接口适配（见第 6 章）
        │   │   │   ├── MusicU.kt    # 模块化 CGI 网关客户端
        │   │   │   ├── Api.kt       # 搜索 / 歌曲详情 / 歌词 / 发现 / 歌单 / 专辑 / 榜单
        │   │   │   ├── PlayUrlApi.kt# vkey 获取、降级、Range 探测、Range 下载
        │   │   │   ├── UserApi.kt   # 用户库、收藏、歌单增删改
        │   │   │   ├── Login.kt     # 登录态、cookie 归一化、g_tk 票据
        │   │   │   ├── QrLogin.kt   # 二维码登录与 OAuth 换票
        │   │   │   ├── Lyrics.kt    # LRC 解析与翻译对齐
        │   │   │   ├── Qrc.kt / QrcDecrypt.kt / QrcDes.kt / QrcCache.kt / qrc/QrcSource.kt
        │   │   │   ├── SingerApi.kt # 歌手歌曲 / 专辑
        │   │   │   └── Models.kt    # Song / Playlist / Singer / UserProfile / SongUrl
        │   │   ├── CacheManager.kt  # 音频缓存、分层配额与清理
        │   │   ├── DownloadManager.kt# 离线下载（串行 + MediaStore）
        │   │   ├── MetaCache.kt     # 歌单/歌词等元数据落盘（文件 mtime 判过期）
        │   │   ├── RecommendRepository.kt
        │   │   ├── LibraryRepository.kt
        │   │   ├── LyricPrefetcher.kt
        │   │   ├── LocalMusic.kt    # MediaStore 扫描与删除
        │   │   └── LocalMeta.kt     # 本地歌曲「借」在线元信息
        │   ├── player/
        │   │   ├── PlayerController.kt   # 进程级播放状态中枢（队列、模式、进度）
        │   │   ├── PlayerService.kt      # 前台服务、MediaSession、音频焦点
        │   │   ├── AudioEngine.kt        # MediaPlayer 生命周期与音质
        │   │   ├── GrowingFileDataSource.kt # 边下边播数据源
        │   │   ├── HttpMediaDataSource.kt   # 自建 HTTP 数据源（真机兼容）
        │   │   ├── VolumeFade.kt / PlayerNotification.kt / MediaButtonReceiver.kt
        │   └── ui/                  # Activity / Fragment / Adapter / 自定义 View
        └── res/                     # layout / values / values-en / xml
```

---

## 4. 构建与运行

### 4.1 环境

| 项 | 要求 |
| --- | --- |
| JDK | 17（实测 Microsoft OpenJDK 17.0.13） |
| Android SDK | `platforms;android-34`、`build-tools;34.0.0` |
| Gradle | 8.6（`gradle/wrapper` 已固定，无需本机安装） |
| `local.properties` | 需包含 `sdk.dir=<Android SDK 路径>`，该文件不入库 |

### 4.2 命令

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

| 变体 | 路径 | 体积 |
| --- | --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | ≈ 6.5 MB |
| Release | `app/build/outputs/apk/release/app-release.apk` | ≈ 2.25 MB |

### 4.3 签名

`app/build.gradle.kts` 中的 `signingConfigs.shared` 默认复用 `~/.android/debug.keystore`（口令均为 `android`），同时开启 v1 与 v2 签名，保证 `assembleRelease` 的产物可直接安装。正式发布前请替换为自己的 keystore，或删除 release 的 `signingConfig`。

### 4.4 入库前清理

仓库根目录下的以下内容属于本地调试产物，**不要提交**：

```
_tools/            # 调试用 Chrome 用户目录
_scripts/ _shots/
ref/ tools/
_screen*.png _framestats.txt _logcat-raw.txt _diag_boot.log app-log.txt
fx.xml i.xml rec.xml
```

现有 `.gitignore` 已覆盖 `build/`、`.gradle/`、`local.properties`、`.idea/`、`*.apk`、`*.aab`、`*.keystore`、`*.log`、`captures/`、`*.iml`；建议再补充：

```gitignore
_tools/
_scripts/
_shots/
ref/
tools/
_screen*.png
_framestats.txt
_logcat-raw.txt
_diag_boot.log
app-log.txt
*.xml.bak
```

---

## 5. 架构设计

### 5.1 分层

```
UI (Activity / Fragment / Adapter / View)
        │  只与 PlayerController 和 Repository 交互
        ▼
PlayerController（进程级单例，队列 + 状态 + 事件）
        │  attach / detach
        ▼
PlayerService（前台服务）── AudioEngine（MediaPlayer）── DataSource
        │
        ▼
Repository（RecommendRepository / LibraryRepository / LocalMeta …）
        │
        ▼
data/api（MusicU / Api / UserApi / PlayUrlApi / QrLogin …）
        │
        ▼
core（Http / Task / Store / Crypto / Diag）
```

关键点：**队列与播放状态放在进程级单例 `PlayerController` 里，不依赖 Service 是否存活**。界面只与它交互，Service 负责真正的解码播放；Service 被系统回收后恢复时队列仍在。

### 5.2 起播链路

1. UI 调 `PlayerController.playAt(context, position)`。
2. 从队列取 `Song`，按设置音质调 `PlayUrlApi.getUrl()`，逐档降级直到拿到 `purl`。
3. 用 `Http.contentLength()` 做一次 Range 探测，拿到真实总长。
4. 若该曲目已有缓存文件，直接本地播放；否则启动后台下载写入 `cache/audio/<fileName>.part`。
5. `AudioEngine` 用 `GrowingFileDataSource`（包装增长中的文件 + 已知总长）`setDataSource`，`prepareAsync`。
6. `onPrepared` 后 `start()`，淡入由 `VolumeFade` 处理；`pollProgress` 只在播放页可见时以 500ms 轮询。
7. 预取后几首的歌词与封面；必要时预热下一首的 vkey。

### 5.3 为什么不用 `setDataSource(http url)`

真机（Meizu PRO 5 / Flyme 8 / Android 7.0）上平台 `MediaHTTPConnection` 会在约 140ms 内回调 `onError(what = -38)`；自建 `HttpMediaDataSource` 同样失败，因为设备到 CDN 的 RTT 约 600ms，native 解码线程等不到数据。因此改成「下载线程写文件 + `readAt` 只读本地」：数据没到就短暂等待，到了立即返回。

由此带来两个约束：

- `MediaDataSource.getSize()` 在 `prepareAsync()` 前被同步调用，拿不到长度部分 ROM 的 NuPlayer 会 `onError(-38)`，所以总长必须**提前在 IO 线程**用 Range 问好（`AudioEngine.play()` 在主线程，不能发网络）。
- 边下边播的文件必须保留 `.mp3/.flac` 后缀，加 `.part` 会让 `MediaPlayer` 报 `-38`。

### 5.4 缓存分层

| 层 | 目录 | 默认配额 | 清理顺序 |
| --- | --- | --- | --- |
| 音频 | `cache/audio/` | 总上限默认 1 GB（`CACHE_OPTIONS`：256/512/1024/2048/4096/0=不限） | 1（最先） |
| 封面 | `cache/image/` | 100 MB | 2 |
| 歌单元数据 | `cache/meta/` | 100 MB | 3 |
| 歌词 / 译文 / QRC | `cache/lyric/` | 100 MB | 4（最后） |

超出总上限时只清音频层；三层元数据只在超过各自配额时清理——重新拉取代价最高。

### 5.5 登录态

- `Login.init()` 从 SharedPreferences 读 cookie，经 `normalizeCookie()` 补齐派生字段后灌入 `Http` 的全局 Cookie 池。
- 每次请求返回后 `Login.refreshCookies(setCookies)` 增量合并并写回磁盘（`qm_keyst` / `skey` 会定期轮换）。
- `Login.skey()` 的优先级必须是 `qm_keyst → qqmusic_key → p_skey → skey`；用 `p_skey` 算 `g_tk` 会给写接口送出对不上的 CSRF token，写「我喜欢」恒回 `80105`。
- Cookie 池不区分域；第三方请求用 `withCookies = false` 显式跳过，避免把登录态发给别人的服务器。

### 5.6 存储位置

`StorageLocation` 提供四种下载位置：

| 枚举 | 展示名 | 相对路径 |
| --- | --- | --- |
| `APP_PRIVATE` | 应用内部存储（推荐） | `QQMusicPortable` |
| `APP_EXTERNAL` | 应用外部存储（默认） | `QQMusicPortable` |
| `MUSIC_PUBLIC` | 系统音乐目录 | `Music/QQMusicPortable` |
| `DOWNLOADS_PUBLIC` | 系统下载目录 | `Download/QQMusicPortable` |

### 5.7 诊断

部分国产 ROM（如 Flyme）会关闭 logcat 缓冲区，`adb logcat` 一行都读不到。`core/Diag.kt` 把关键步骤与未捕获异常直接写入：

```
/sdcard/Android/data/com.portable.qqmusic/files/diag/boot.log   # 外部，便于 adb pull
/data/data/com.portable.qqmusic/files/diag/boot.log             # 内部兜底
```

启动路径上每一步都有 `Diag` 记录；网络层只对非 2xx 或耗时 > 8s 的请求写诊断（只记 host + path，不把 query 里的票据写进日志）。

---

## 6. API 接口文档

### 6.1 通用约定

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

- 键名 `req_0` / `req_1` … 可自定义，**响应按同名键返回**，`MusicU.call()` 据此收集。
- 早期使用 `{"request": {...}}` 数组格式会得到 HTTP 200 但取不到任何模块数据——这是「所有接口都没数据」的根因。
- `ct` / `cv` 会影响同一模块的返回内容：`ct=19`（桌面网页版）只给 LRC，`ct=24`（App 端）才会返回 QRC 逐字歌词。
- 登录后 `comm` 追加 `g_tk` 与 `g_tk_new_20200303`，值为 `Crypto.gtk(Login.skey())`。

**响应**

```json
{ "code": 0, "req_0": { "code": 0, "data": { } } }
```

判定：顶层 `code == 0` 且模块 `code == 0` 且 `data != null`。`MusicU` 会在 `data` 缺失时退回整个模块对象。

**签名算法**（`core/Crypto.kt`）

```text
hash33(text):  e = 0
               for ch in text: e = (e + (e << 5) + ch.code) & 0x7FFFFFFF
               return e
gtk(skey)       = hash33(skey)              # 不要取模
zzcSign(data)   = md5(data + md5(data + "zzb@qq.com"))
zzaSign(query)  = md5("zza" + query)        # 旧版 fcgi，本仓库未使用
```

`hash33` 每一步都必须截断到 31 位，否则 Kotlin `Long` 会溢出（JS 语义为 `2147483647 & e`）。

**Cookie**（`core/Http.kt`）

- `Set-Cookie` 一个头里可能塞多个 cookie，按 `,(?=\s*[A-Za-z0-9_-]+=)` 拆分。
- 必须忽略「空值 + `Expires=Thu, 01 Jan 1970`」的删除标记：`check_sig` 会同时下发有效的 `p_skey` 与同名的删除标记，后到的空值会覆盖有效票据，表现为「扫码成功却登录不上」。
- 从网页复制 cookie 时通常只有 `qm_keyst` 与 `uin`，收藏/歌单接口还认 `loginUin`、`qqmusic_uin`、`qqmusic_key`，缺失会回 `40000`。`Login.normalizeCookie()` 负责补齐，幂等。

### 6.2 接口总表

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

### 6.3 搜索

`SearchApi.search(keyword, page = 1, pageSize = 20, type = 0)`

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
- `search_type`：`0` 单曲、`1` 歌手、`2` 专辑、`3` 歌单、`7` 歌词、`8` 用户；`5`/`6`/`9` 无数据。
- 响应路径：`req_0.data.body.{song|singer|album|songlist}.list`。
- 服务端九个键都在，但只有对应类型有数据，所以三个 Tab 必须各发一次请求。
- 旧版歌单搜索 `client_music_search_songlist` 仍可用（`remoteplace=txt.yqq.playlist`，注意 `page_no` 从 0 开始）。

### 6.4 歌曲详情（批量）

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
- 用途：列表接口常缺少时长、专辑名、封面 albumMid，用此接口补全后再写缓存。

### 6.5 歌词（LRC + 翻译）

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
- **绝对不能加 `qrc=1` / `qrc_t=0`**，否则 `lyric` 整体变成二进制密文，长度 4000+ 却是乱码，界面表现为「暂无歌词」——这种失败是静默的。
- 返回的 `lyric` / `trans` 即使 `nobase64=1` 仍是 base64；`Api.decodeLyric()` 用「是否含 `[` `]`」判断明文，否则按 base64 解。

### 6.6 逐字歌词（QRC）

独立入口 `data/api/qrc/QrcSource.kt`，同一 module，参数追加：

```json
{ "songmid": "…", "songtype": 0, "format": "json", "nobase64": 1,
  "crypt": 1, "qrc": 1, "qrc_t": 0, "trans": 1, "trans_t": 0 }
```

- 密文可能在 `qrc` 字段，也可能在 `lyric` 字段（不同歌曲不同），两处都要尝试。
- 解密：定制版 3DES（`QrcDes.kt`，含 S 盒位变换）+ zlib inflate（`QrcDecrypt.kt`）。
- 解出的 XML 按 `<line>` 解析为 `Qrc.Line`（含逐字 `Word.startMs` / `durationMs`），写 `lyric_qrc_<mid>` 缓存，TTL 30 天。
- 所有失败吞在 `QrcSource` 内返回空表，调用方回退 LRC。

### 6.7 播放地址（vkey）

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
- `songUrl.isTrial = (pneed == 1 && pneedbuy == 1)`，用于标记试听。
- 缓存文件名必须与 `PlayerService.urlFileName()` 一致：`purl.substringAfterLast('/').substringBefore('?')`，否则缓存永不命中。

**音质与降级**

| 档位 | `Quality` | 前缀 | 扩展名 | 权限 |
| --- | --- | --- | --- | --- |
| 标准品质 | `STANDARD` | `M500` | `.mp3` | 普通用户 |
| HQ 高品质 | `HQ` | `M800` | `.mp3` | 普通用户 |
| SQ 无损 | `SQ` | `F000` | `.flac` | 需绿钻 |
| Hi-Res 无损 | `HIRES` | `RS01` | `.flac` | 需 SVIP |

降级方向 `Hi-Res → SQ → HQ → 标准`，全失败后再试一次标准档。下载走 `forceQuality = true`，只试目标档。

**坏链探测**：`purl` 非空且 `result == 0` 仍可能 HTTP 404（实测《如春 (Run)》`mid=001JOLTf4UlH6q` 的 `RS01` 直链 404，`M500` 为 200 / 3,569,159 字节）。所以 `resolvePlayable()` 对每档做一次 Range `bytes=0-0` 探测，并用 `ConcurrentHashMap<mid, Quality>` 记住上次成功的档位，下次从该档开始试。

### 6.8 发现页

**每日 30 首**——不是雷达接口，而是一个**真实歌单**：

1. `RecommendFeed / get_recommend_feed`（空 param）取 `data.v_shelf[].v_niche[].v_card[]`；
2. 找 `title` 含「每日30首」的卡片，取 `id`（实测 `4797214348`，每天变化）；
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

取 `data.list[]`，未登录也可用。早期改用 `DissInfo { id, num, page, order }` 回 `param error(-100008)`。

### 6.9 歌单

**`CgiGetDiss`**

```json
{ "module": "music.srfDissInfo.DissInfo", "method": "CgiGetDiss",
  "param": { "disstid": 123456, "dirid": 0, "song_begin": 0, "song_num": 500, "tag": 1 } }
```

- 普通歌单用 `disstid`，`dirid = 0`。
- **用户目录（我喜欢 / 自建）必须 `disstid = 0`**，用 `dirid` 定位；传 `disstid = 201` 回 `code = 10004`。
- 服务端单次最多返回 500 首，超过必须用 `song_begin` 翻页；**页码要用「原始条目数」推进**，用有效歌曲数会漏歌并误判最后一页（表现为歌单卡在 499 首）。
- 收藏的别人歌单 `CgiGetDiss` 不认 `tid`（回 `param error`），必须走下面的 legacy。

**legacy 歌单详情 / 收藏歌单**

```
GET https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg
    ?type=1&json=1&utf8=1&onlysong=0&disstid=<tid>&format=json&g_tk=5381
    &loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0
    &platform=yqq.json&needNewCode=0
Referer: https://y.qq.com/
```

取 `cdlist[0].songlist`。**认 `tid`，不认真实 `dissid`**——两个 id 语义不同，`Playlist` 同时保留 `tid` 与 `disstid` 两个字段；混用正是「收藏歌单点进去没有歌曲」的根因。

**歌单元信息三套命名都要认**

| 来源 | 字段 |
| --- | --- |
| legacy 用户目录 | `diss_name` / `diss_cover` / `song_cnt` / `listen_num` / `dirid`（`dissid`、`dissname` 为 null） |
| musicu | `dirName` / `songNum` / `dirId` |
| 普通歌单 | `dissname` / `songnum` / `dissid` |

**专辑**

- `AlbumInfoServer / GetAlbumDetail`，参数是 **`albumMId`（大写 `I`）**；写 `albumMid` 会回 200 但 `data` 为空。取 `basicInfo.albumName`。
- `AlbumSongList / GetAlbumSongList { albumMid, begin: 0, num: 200 }` → `songList[].songInfo`。

### 6.10 歌手

**歌曲**（注意模块名**没有** `music.` 前缀，参数是 `number` 不是 `num`，每页上限 30）

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

**歌手头像**：必须用搜索结果自带的 `avatar` 完整 URL，不能拼 `T001R300x300M000{mid}.jpg`（mid 常不一致，实测 404）。多人歌手取 `/` 前的第一个名字。

已废弃：legacy `v8/fcg-bin/fcg_v8_singer_track_cp.fcg`（404）、`music.musichallSong.SingerInfoInter` / `music.musichallSinger.SingerInfo`（`500003`）、「按歌手名搜单曲再按 mid 过滤」（只能拿十几首）。

### 6.11 用户库（需登录）

**用户资料**：`GetLoginUserInfo`，昵称取 `data.info.nick`（不是 `data.creator.nick`）。

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

取 `data.disslist[]`（兼容 `data.list`），字段见 6.9 的下划线命名，解析后标记 `isSelfCreated = true` 并写缓存。

**收藏歌单（两步）**

1. `PlaylistFavRead / GetPlaylistFavList { uin, offset: 0, size: 60 }` → `data.v_tids[]`（纯 tid）；
2. 每个 tid 用 4 线程并发调 legacy `fcg_ucc_getcdinfo_byids_cp.fcg?disstid=<tid>` 取 `cdlist[0]`，`copy(isSelfCreated = false, tid = tid)`。

**是否已收藏单曲**

```json
{ "module": "music.musicasset.SongFavRead", "method": "IsSongFanByMid",
  "param": { "v_songMid": ["001JOLTf4UlH6q"] } }
```

取 `data.m_fan[mid]`。另有 `favoriteMids(force)` 翻最多 8 页 × 500 收集「我喜欢」全部 mid，进程内缓存，写操作后 `invalidateFavoriteMids()`。

### 6.12 写操作（需登录 + 正确 `g_tk`）

**收藏 / 取消收藏歌曲**

```json
{ "module": "music.musicasset.PlaylistDetailWrite", "method": "AddSonglist",
  "param": { "dirId": 201, "v_songInfo": [{ "songType": 0, "songId": 123456 }] } }
```

`DelSonglist` 同参。曾经恒回 `80105`，根因是 `g_tk` 用错了票据且多算了 `% 1000000`；修正后 `code = 0`，`IsSongFanByMid` 复查为 `true`。

> 注意：某些场景下明文 musicu 的这条写入会被服务端单独拦掉（恒回 `80105`，换任何参数形态、`ct`/`cv`、网关与 `g_tk` 算法都一样）。此时 `ui/util/WebFavorite.kt` 会起一个 1×1 隐形 WebView，灌入登录态加载歌曲详情页并点页面上的「收藏」，把签名交给网页自己算。网页走的是 `u6.y.qq.com/cgi-bin/musics.fcg?encoding=ag-1&sign=…`（TmeWebSec 加密，VMP 混淆），无法在原生层复刻。

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

参数是 **`dirId`**，不是 `disstid` / `tid`（实测某歌单 `dirid = 15` 而 `disstid = 2236337901`）。

**收藏 / 取消收藏歌单**

```json
{ "module": "music.musicasset.PlaylistFavWrite", "method": "FavPlaylist",
  "param": { "uin": "…", "v_playlistId": [123456] } }
```

取消用 `CancelFavPlaylist`。响应 `{ code: 0, data: { result: 0, v_failedPlaylistId: [] } }`，`ok = 模块 code == 0 && 失败列表为空`。早期猜测的 `DelPlaylistFav` + `v_tids` 回 `40000`（方法名与参数名都不对）。

**歌单内是否已有某首歌**：服务端没有查询接口，只能翻页比 mid（`playlistHasSong`，最多 8 页）。`AddSonglist` 对重复项未必报错。

### 6.13 登录

完整链路见 `data/api/QrLogin.kt`，六步：

1. **申请二维码**（必须只发一次请求，PNG 与 `qrsig` 同时取）

   ```
   GET https://ssl.ptlogin2.qq.com/ptqrshow
       ?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=0.<ms>&daid=383&pt_3rd_aid=100497308
   Referer: https://xui.ptlogin2.qq.com/
   ```

   `ptqrtoken = hash33(qrsig)`。曾经分两次请求（一次取图、一次取 cookie），第二次带着旧 `qrsig` 又生成一张码，扫码后服务端永远回 `66`。

2. **轮询**

   ```
   GET https://ssl.ptlogin2.qq.com/ptqrlogin
       ?u1=<enc https://graph.qq.com/oauth2.0/login_jump>&ptqrtoken=<hash33(qrsig)>
       &ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-<ms>
       &js_ver=20102616&js_type=1&login_sig=&pt_uistyle=40
       &aid=716027609&daid=383&pt_3rd_aid=100497308&has_onekey=1
   ```

   响应 `ptuiCB('66','0','','0','二维码未失效。','')`：

   | code | 含义 |
   | --- | --- |
   | `65` | 二维码已失效 |
   | `66` | 未扫描 |
   | `67` | 已扫描，待确认 |
   | `0` | 成功，第 3 个参数是跳转 URL |

   解析跳转 URL 必须用正则 `https?://[^'"\s]+`；按单引号计数会取到垃圾串，导致 `MalformedURLException: no protocol:`。

3. **手动逐跳跟随 302**，每跳的 `Set-Cookie` 立即并入池。`HttpURLConnection` 自动跟随只保留最终响应 cookie，会丢掉 `p_skey` / `uin`。

4. **换 OAuth code**：先 GET `graph.qq.com/oauth2.0/show`（`client_id=100497308`、`redirect_uri=https://y.qq.com/portal/wx_redirect.html?login_type=1`），若 302/body 带 code 直接返回；否则 POST `graph.qq.com/oauth2.0/authorize`，表单带 `g_tk`，从 Location 取 `code=([^&\s"'<>]+)`。`g_tk` 依次试 `hash33(p_skey) % 1e6`、`hash33(p_skey) & 0x7FFFFFFF`、同法 `skey`、`5381`——`error=100046` 时只有 `5381` 能过。

5. **换音乐票据**

   ```json
   { "comm": { "g_tk": 5381, "platform": "yqq", "ct": 24, "cv": 0,
               "uin": "…", "format": "json" },
     "req": { "module": "QQConnectLogin.LoginServer", "method": "QQLogin",
              "param": { "code": "<OAuth code>" } } }
   ```

   **要的是 OAuth `code`，不是 `p_skey`**（直接传 `p_skey` 回 `1000/10006`，`musicid`/`openid` 全空）。取 `data` 里的 `musickey` / `musicid` / `openid` / `access_token` / `refresh_key`。

6. **构造域 cookie 并校验**：`uin=o<uin>`、`loginUin`、`qqmusic_uin`、`qqmusic_key` / `qm_keyst` / `musickey`、`openid`、`access_token`、`refresh_key`，写入 `Login.save()`，再 `refreshProfile()` 验证。

### 6.14 错误码与常见陷阱

| 现象 | 原因 | 正确做法 |
| --- | --- | --- |
| HTTP 200 但所有模块无数据 | 请求体用了 `{"request": …}` 数组格式 | `{"comm":…, "req_N":{…}}`，响应取同名键 |
| `param error(-100008)` | 推荐歌单用了 `DissInfo{id,…}` | legacy `fcg_get_diss_by_tag.fcg` |
| `code = 10004` | 用户目录传了 `disstid` | `disstid = 0`，用 `dirid` |
| `code = 40000` | 缺 `loginUin` / `qqmusic_uin` / `qqmusic_key`，或 `g_tk` 用错票据 | `normalizeCookie` + `Login.skey()` 优先 `qm_keyst` |
| `code = 80105` | 写「我喜欢」的 CSRF 校验不过 | 修正 `g_tk`；仍失败走 `WebFavorite` 兜底 |
| `error = 100046` | `authorize` 的 `g_tk` 基于票据 | 用 `5381` |
| 扫码成功但登录不上 | cookie 删除标记覆盖有效 `p_skey` | 忽略空值 + 1970 过期 |
| 收藏歌单无歌曲 | 用 `dissid` 去问 legacy | legacy 认 `tid`，两个字段分开存 |
| 歌单只加载到 499 首 | 用有效歌曲数推进 `song_begin` | 用原始条目数推进 |
| 专辑 `data` 为空 | 参数写 `albumMid` | 参数名是 `albumMId` |
| 歌手页只有十几首 | 旧方案按名字搜单曲过滤 | `GetSingerSongList`，`number` 上限 30 |
| 歌手头像 404 | 用 mid 拼 `T001…` | 用搜索结果自带的完整 `avatar` |
| 歌词整体乱码 / 「暂无歌词」 | 普通歌词请求带了 `qrc=1` | 普通歌词与 QRC 分开发 |
| 没有官方翻译 | 缺 `trans=1` | 固定带 `trans=1&trans_t=0` |
| 播放 `onError(-38)` | 直链交给播放器 / 未预知总长 | `GrowingFileDataSource` + 提前 Range 探测 |
| 白屏（页面局部） | `MiniPlayerController` 传了根布局 | 传 `R.id.mini_player` |
| 缓存永不命中 | 缓存 key 含 query | `substringAfterLast('/').substringBefore('?')` |

---

## 7. 数据模型

`data/api/Models.kt` 统一入口：**QQ 音乐在不同接口里用 `songmid` / `mid` / `songMid` 三种写法指同一字段**，解析一律走 `Song.fromJson()`。

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

`Song` 上几个容易踩的语义：

- `isLocal`（`type == TYPE_LOCAL`）**不能**用来判断「是否有在线 mid」——下载的歌是本地文件但带着原曲 mid；应使用 `hasRealMid`（排除 `local_` / `file_` 前缀）。这正是「下载后既没封面也没歌词」的成因。
- 本地音乐必须显式保存文件路径 `localPath`：`MediaPlayer.setDataSource(String)` 只接受文件路径，传 `content://` URI 会被当成不存在的路径。
- 本地封面用 MediaStore 的 `ALBUM_ID` 拼 `content://media/external/audio/albumart/<albumId>`。

---

## 8. 本地存储与缓存

```
app 私有目录/
├── files/diag/boot.log          # 启动诊断
├── cache/
│   ├── audio/                   # 音频缓存（fileName 即缓存 key）
│   ├── image/                   # 封面
│   ├── meta/                    # 歌单 / 推荐等列表数据
│   └── lyric/                   # 歌词原文 / 译文 / 逐字 QRC
└── shared_prefs/qqm_store       # 单一 SharedPreferences
```

`SharedPreferences`（`core/Store.kt`）主要键：

| 键 | 含义 |
| --- | --- |
| `cookie` / `uin` / `nick` / `avatar` / `vip_type` / `login_time` | 登录态 |
| `quality_online` / `quality_download` | 在线 / 下载音质 |
| `play_fade` / `volume_balance` / `loudness_adapt` | 播放过渡与音量处理 |
| `cache_limit_mb` / `cache_image_mb` / `cache_meta_mb` / `cache_lyric_mb` | 缓存配额 |
| `download_dir` | 下载位置（`StorageLocation` 名） |
| `last_song` / `last_index` / `play_mode` / `queue_kind` | 播放队列恢复 |
| `tab_index` / `sort_<scope>` | 界面状态、歌单排序 |

`MetaCache` 用文件 mtime 判过期（默认 12 小时；QRC 30 天），不额外写元数据文件。

---

## 9. 调试与诊断

```bash
# 拉取启动诊断
adb pull /sdcard/Android/data/com.portable.qqmusic/files/diag/boot.log

# 观察网络与播放
adb logcat -s QQM:* AndroidRuntime:E
```

- `Log.enabled = BuildConfig.DEBUG`，Release 不输出。
- `Http` 只对非 2xx 或 > 8s 的请求写 `Diag`，且 `hostOf()` 只保留 host + path，避免票据进日志。
- 关闭 logcat 的 ROM 上，`boot.log` 是唯一可用的现场信息。

---

## 10. 已知限制

1. **接口无公开文档**，服务端改字段、加签名或限频都会导致失效；适配集中在 `data/api/`，便于单点修改。
2. **收藏单曲存在账号级拦截**：明文 musicu 写入可能恒回 `80105`，此时依赖 WebView 兜底，速度较慢且需要网络良好。
3. **歌单 id 语义分裂**：`dirid`（用户目录）、`disstid`（普通歌单）、`tid`（收藏关系）三者不可混用，新增功能时务必按 6.9 / 6.11 的规则选。
4. **边下边播依赖本地文件增长**：极端慢网下会出现等待，不会崩溃但会停顿；缓存未命中且网络中断时该曲目不可播。
5. **必须允许明文 HTTP**，否则头像、部分封面与直链全部失败。
6. 未登录可播放标准 / HQ；SQ 与 Hi-Res 需要相应会员，否则自动降级或提示无版权。
7. 仅测试过 Android 7.0（Meizu PRO 5）与少量模拟器；更高版本的分区存储路径已按 MediaStore 适配，但未做全机型覆盖。

---

## 11. 开发日志

逐条排错与迭代记录（起播延迟优化、QRC 接入、翻译方案取舍、界面白屏排查等）保存在本地 `docs/DEVLOG.md`，未随仓库发布。README 只保留对当前实现仍然成立的结论；如需追溯某项决定的来由，可向维护者索取该日志。

---

## 参考实现

以下开源项目在接口字段、请求格式与若干「200 但数据为空」的问题上提供了对拍依据：

- [Hjdd14/Mconnect-Music_connect](https://github.com/Hjdd14/Mconnect-Music_connect) — musicu 请求/响应格式、`_buildMusicLoginCookie` 的派生字段补齐
- [baoozak/qqmusic-mcp](https://github.com/baoozak/qqmusic-mcp) — `DelPlaylist` 使用 `dirId` 等写接口细节

---

## 许可

本项目仅用于个人学习与技术研究，与腾讯 / QQ 音乐官方无关。接口、曲库与商标归其各自权利人所有。
