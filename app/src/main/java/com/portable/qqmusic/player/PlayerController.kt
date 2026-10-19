package com.portable.qqmusic.player

import android.content.Context
import android.content.Intent
import android.os.Build
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.PlayFade
import com.portable.qqmusic.core.PlayMode
import com.portable.qqmusic.core.Quality
import com.portable.qqmusic.core.QueueKind
import com.portable.qqmusic.core.Settings
import com.portable.qqmusic.core.Store
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.CacheManager
import com.portable.qqmusic.data.LyricPrefetcher
import com.portable.qqmusic.data.RecommendRepository
import com.portable.qqmusic.data.api.PlayUrlApi
import com.portable.qqmusic.data.api.Song
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * 播放状态中枢。
 *
 * 队列与播放状态放在进程级的单例里 (不依赖 Service 是否存活),
 * 界面只与它交互, Service 负责真正的解码播放。
 * 这样即使 Service 被系统回收, 恢复时队列仍在。
 */
object PlayerController {

    // ---------------- 事件 ----------------

    interface Listener {
        fun onSongChanged(song: Song?)
        fun onPlayStateChanged(playing: Boolean)
        fun onProgress(positionMs: Int, durationMs: Int)
        fun onQueueChanged()
        fun onBuffering(buffering: Boolean)
        fun onError(message: String)
    }

    private val listeners = Collections.synchronizedList(ArrayList<Listener>())

    fun addListener(l: Listener) {
        synchronized(listeners) { if (!listeners.contains(l)) listeners.add(l) }
        // 新加入的监听者立即拿到当前状态
        Task.main {
            l.onSongChanged(current())
            l.onPlayStateChanged(isPlaying)
            l.onQueueChanged()
        }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun notifySong() {
        val s = current()
        Task.main { snapshot().forEach { it.onSongChanged(s) } }
    }

    private fun notifyPlay() {
        val p = isPlaying
        Task.main { snapshot().forEach { it.onPlayStateChanged(p) } }
    }

    private fun notifyQueue() {
        Task.main { snapshot().forEach { it.onQueueChanged() } }
    }

    private fun notifyProgress(pos: Int, dur: Int) {
        Task.main { snapshot().forEach { it.onProgress(pos, dur) } }
    }

    private fun notifyBuffering(b: Boolean) {
        Task.main { snapshot().forEach { it.onBuffering(b) } }
    }

    private fun notifyError(msg: String) {
        Task.main { snapshot().forEach { it.onError(msg) } }
    }

    private fun snapshot(): List<Listener> = synchronized(listeners) { listeners.toList() }

    // ---------------- 状?----------------

    private val queue = ArrayList<Song>(64)

    /** 随机播放时的乱序索引 */
    private val shuffleOrder = ArrayList<Int>(64)

    private var index = -1

    /**
     * 当前队列的来源。
     *
     * 队列是「猜你喜欢」时不许随机、并且播到最后一首要自动续一批 (见 [scheduleGuessExtension])。
     * 它跟着队列一起持久化: 否则进程被回收后恢复出来的猜你喜欢队列会退化成普通队列,
     * 随机又能开了, 播完也不会再续。
     */
    @Volatile
    private var queueKind: QueueKind = QueueKind.NORMAL

    /** 已经为「队列长度是这么多」的那一版最后一首发过续接请求, 防止同一首反复请求 */
    private var guessExtensionRequestedFor = -1

    @Volatile
    private var guessExtensionLoading = false

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var isBuffering: Boolean = false
        private set

    /** 当前解析到的播放直链, ?Service 使用 */
    @Volatile
    var currentUrl: String = ""

    /**
     * 当前这首在磁盘缓存里的文件名。
     *
     * 歌词扫色要拿它去找完整缓存文件 —— 只有完整文件才解得动, 半截的
     * [CacheManager.growingFile] 解到一半就 EOS 了。
     */
    @Volatile
    private var lastCacheName: String = ""

    /**
     * [lastCacheName] 属于哪一首。
     *
     * 换歌时解析还没回来, 只清缓存名会让界面拿**上一首**的缓存文件去读采样率,
     * 输出徽章于是显示上一首的「直通/重采样」。带上 mid 后, mid 对不上就当作还不知道。
     */
    @Volatile
    private var lastCacheMid: String = ""

    /**
     * 当前这首歌在磁盘上的完整缓存文件, 没有就返回 null。
     */
    fun audioFileForEnvelope(context: Context): java.io.File? {
        // 本地歌没有缓存名, 直接返回它自己的路径 —— 输出通路的源采样率要靠它读
        current()?.localPath?.takeIf { it.isNotEmpty() }?.let { p ->
            val f = java.io.File(p)
            if (f.isFile) return f
        }
        if (lastCacheName.isEmpty()) return null
        // 换歌后解析还没落地: 这份缓存名属于上一首, 返回 null 让徽章先不显示源采样率,
        // 而不是拿错文件算出一个假的「直通」。
        val cur = current() ?: return null
        if (lastCacheMid != cur.mid) return null
        // 优先完整文件。没有就拿正在下载的半截 —— MediaExtractor 读到哪儿算哪儿,
        // 早期的几句照样能校准, 只是末尾几行会算不出来(返回 0 就保持原样)。
        CacheManager.cachedFile(context, lastCacheName)?.let { return it }
        val part = CacheManager.partFile(context, lastCacheName)
        return if (part.isFile && part.length() > 1L shl 20) part else null
    }

    /**
     * 当前这首**实际**在用的音质。
     *
     * 和 `Settings.onlineQuality()` (用户预设) 是两回事: 预设 Hi-Res 而那首歌只有
     * 标准档时, [PlayUrlApi.resolvePlayable] 会一路降档到真能读到字节的那一档
     * (服务端会给 purl 非空、result=0 但实际 404 的坏链接)。界面必须显示真正在放
     * 的那档, 否则用户看着「Hi-Res」在听 128k —— 那是个会误导人的谎。
     *
     * 连同歌曲 mid 一起记: 换歌的瞬间解析还没开始, 只清值会让界面短暂显示
     * **上一首**的档位。带上 mid 后, [actualQualityOf] 对新歌一律返回 null。
     */
    @Volatile
    private var actualQuality: Quality? = null

    @Volatile
    private var actualQualityMid: String = ""

    /**
     * [song] 实际在用的音质。
     *
     * @return 还没解析出来、或 [song] 不是记录里的那一首时返回 null;
     *         本地文件同样返回 null (本地文件没有"档位"这个概念)
     */
    fun actualQualityOf(song: Song?): Quality? {
        val s = song ?: return null
        if (s.isLocal) return null
        return if (s.mid == actualQualityMid) actualQuality else null
    }

    @Volatile
    private var service: PlayerService? = null

    @Volatile
    private var pendingAutoPlay = true

    /**
     * 服务还没起来时挂起的播放请求。
     *
     * ⚠️ 之前 `service == null` 时只设了 [pendingAutoPlay] **没有把服务拉起来**,
     * 于是首次播放永远停在「显示暂停图标、进度 0、时长 --:--」——
     * 界面以为在播, 实际没有任何解码器。用户反馈的「歌曲无法正确播放」就是这个。
     * 现在同时记下直链, 服务 [attach] 后直接加载, 不再重复解析一次直链。
     */
    @Volatile
    private var pendingSong: Song? = null

    @Volatile
    private var pendingUrl: String = ""

    /** 与 [pendingUrl] 配套: 在 IO 线程探好的远程文件总长, 供 MediaDataSource 的 getSize() 用 */
    private var pendingSize: Long = -1L

    /** 与 [pendingUrl] 配套: 已经落在本地的缓存文件路径, 非空时直接播本地 */
    private var pendingLocalPath: String = ""

    /** [pendingLocalPath] 是正在下载中的 .part 文件时置 true, 走边下边播 */
    private var pendingGrowing: Boolean = false

    private var lastNotifiedSecond = -1

    // ---------------- 生命周期 ----------------

    fun attach(s: PlayerService) {
        service = s
        Log.d("PlayerController", "service attached")
        // 服务就绪后立即续播挂起的请求
        val song = pendingSong ?: current() ?: return
        val url = pendingUrl
        if (!pendingAutoPlay) return
        if (url.isNotEmpty()) {
            // 直链已经解析过了, 直接用, 不再打一次接口
            pendingSong = null
            pendingUrl = ""
            currentUrl = url
            s.load(
                song,
                url,
                autoPlay = true,
                knownSize = pendingSize,
                knownLocal = pendingLocalPath,
                growing = pendingGrowing
            )
            pendingSize = -1L
            pendingLocalPath = ""
            pendingGrowing = false
            return
        }
        if (!isPlaying) {
            startPlayback(song, autoPlay = true)
        }
    }

    fun detach() {
        service = null
    }

    private fun withService(context: Context, action: (PlayerService) -> Unit) {
        val s = service
        if (s != null) {
            action(s)
            return
        }
        // 服务未启? 先拉? ?attach() 续上
        val intent = Intent(context.applicationContext, PlayerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.applicationContext.startForegroundService(intent)
            else context.applicationContext.startService(intent)
        } catch (t: Throwable) {
            Log.w("PlayerController.svc", t)
        }
    }

    // ---------------- 队列操作 ----------------

    fun current(): Song? = queue.getOrNull(index)

    fun queueSnapshot(): List<Song> = ArrayList(queue)

    fun currentIndex(): Int = index

    fun setQueue(
        context: Context,
        songs: List<Song>,
        startIndex: Int,
        autoPlay: Boolean = true,
        kind: QueueKind = QueueKind.NORMAL
    ) {
        if (songs.isEmpty()) return
        val targetIndex = startIndex.coerceIn(0, songs.size - 1)
        // 点的就是当前正在放 (或暂停中) 的这一首: 只把播放列表换掉, 播放不中断。
        // 之前无条件走 startPlayback, 于是「在别处重新点一下正在放的歌」会从头重播一遍。
        val sameSong = isLiveCurrent(songs[targetIndex])
        queue.clear()
        queue.addAll(songs)
        rebuildShuffle()
        index = targetIndex
        queueKind = kind
        Settings.setQueueKind(kind)
        guessExtensionRequestedFor = -1
        guessExtensionLoading = false
        // 「猜你喜欢」不提供随机: 内容本来就是按口味现推的, 再随机不但没有意义,
        // 还会让「播到最后一首」这件事无从判断 (见 scheduleGuessExtension)
        if (kind == QueueKind.GUESS && Settings.playMode() == PlayMode.SHUFFLE) {
            Settings.setPlayMode(PlayMode.LIST_LOOP)
            Diag.i("PlayerController", "猜你喜欢队列不支持随机, 已切回列表循环")
        }
        Diag.i(
            "PlayerController",
            "setQueue size=${queue.size} 请求下标=$startIndex 实际下标=$index " +
                "来源=${kind.name} 选中=${queue.getOrNull(index)?.name} / ${queue.getOrNull(index)?.singer}"
        )
        pendingAutoPlay = autoPlay
        notifyQueue()
        notifySong()
        saveState()
        if (sameSong) {
            // 解码器/直链/播放进度全部保持不动, 只是播放列表换成了新的一份
            Diag.i(
                "PlayerController",
                "setQueue 命中当前曲目《${songs[targetIndex].name}》, 只更新播放列表不重播"
            )
            if (autoPlay && !isPlaying) resume()
            return
        }
        val song = current() ?: return
        startPlayback(song, autoPlay)
    }

    /** 插入到当前曲目之后 (「下一首播放」) */
    fun appendNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val insertAt = if (index >= 0) index + 1 else queue.size
        queue.addAll(insertAt, songs)
        rebuildShuffle()
        notifyQueue()
        saveState()
    }

    fun appendToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        queue.addAll(songs)
        rebuildShuffle()
        notifyQueue()
        saveState()
    }

    fun removeAt(position: Int) {
        if (position !in queue.indices) return
        queue.removeAt(position)
        if (position < index) index--
        else if (position == index) {
            if (index >= queue.size) index = queue.size - 1
        }
        rebuildShuffle()
        notifyQueue()
        saveState()
    }

    /**
     * 磁盘上的文件被删掉之后, 把这首歌从播放队列里一并拿走。
     *
     * 只把界面那行抹掉是不够的: 队列里还留着它, 下次「下一首」或续播会去解一个
     * 已经不存在的文件, 直接报错中止。删掉的正好是当前这首就顺延到下一首,
     * 队列空了则清空并停播。
     */
    fun removeByKey(key: String) {
        val i = queue.indexOfFirst { it.key() == key }
        if (i < 0) return
        val wasCurrent = i == index
        queue.removeAt(i)
        if (queue.isEmpty()) {
            Diag.i("PlayerController", "队列里最后一首被删除, 清空队列")
            clearQueue()
            return
        }
        if (i < index) {
            index--
        } else if (i == index && index >= queue.size) {
            index = queue.size - 1
        }
        rebuildShuffle()
        notifyQueue()
        saveState()
        if (wasCurrent) {
            Diag.i(
                "PlayerController",
                "当前曲目被删除, 顺延到《${queue.getOrNull(index)?.name}》"
            )
            notifySong()
            queue.getOrNull(index)?.let { startPlayback(it, autoPlay = true) }
        }
    }

    fun clearQueue() {
        queue.clear()
        index = -1
        queueKind = QueueKind.NORMAL
        guessExtensionRequestedFor = -1
        guessExtensionLoading = false
        isPlaying = false
        currentUrl = ""
        service?.pause()
        notifyQueue()
        notifySong()
        notifyPlay()
        saveState()
    }

    fun hasNext(): Boolean = queue.size > 1 && index >= 0

    fun hasPrevious(): Boolean = queue.size > 1 && index >= 0

    /** 预取用: 下一首歌 (无缝切换时提前解析) */
    fun peekNext(): Song? {
        if (queue.isEmpty()) return null
        val next = nextIndex(consume = false) ?: return null
        return queue.getOrNull(next)
    }

    // ---------------- 播放控制 ----------------

    fun resume() {
        val song = current()
        if (song == null) return
        // ⚠️ service ?null 时也必须走完整解析流? 只调 service?.start() 是空操作,
        // 表现为「点了播放键毫无反应」
        val s = service
        if (s == null || !s.isPrepared() || currentUrl.isEmpty()) {
            startPlayback(song, autoPlay = true)
            return
        }
        isPlaying = true
        pendingAutoPlay = true
        s.start()
        notifyPlay()
        saveState()
    }

    fun pause() {
        isPlaying = false
        pendingAutoPlay = false
        service?.pause()
        notifyPlay()
        saveState()
    }

    fun toggle() {
        if (isPlaying) pause() else resume()
    }

    /** 焦点丢失导致的暂?(区别于用户主动暂? */
    fun pauseFromFocus() {
        isPlaying = false
        service?.pause()
        notifyPlay()
    }

    fun next(context: Context? = null, userTriggered: Boolean = true) {
        if (queue.isEmpty()) return
        val next = nextIndex(consume = true) ?: return
        index = next
        val song = queue[index]
        notifySong()
        saveState()
        startPlayback(song, autoPlay = true)
    }

    fun previous() {
        if (queue.isEmpty()) return
        // 不管播了多久都直接切上一首: 之前「播过 3 秒就 seek(0)」会和进度条/迷你条的
        // 左右划手势打架 —— 用户想切歌, 结果只把当前这首重放了一遍。
        val prev = if (Settings.playMode() == PlayMode.SHUFFLE) {
            shuffleOrder.getOrNull((shuffleOrder.indexOf(index) - 1 + shuffleOrder.size) % shuffleOrder.size) ?: index
        } else {
            if (index - 1 < 0) queue.size - 1 else index - 1
        }
        index = prev.coerceIn(0, queue.size - 1)
        notifySong()
        saveState()
        startPlayback(queue[index], autoPlay = true)
    }

    fun seekTo(ms: Int) {
        service?.seekTo(ms)
        notifyProgress(ms, duration())
    }

    fun duration(): Int = service?.duration() ?: 0

    fun position(): Int = service?.position() ?: 0

    fun playAt(context: Context, position: Int, forceRestart: Boolean = false) {
        if (position !in queue.indices) return
        val song = queue[position]
        if (!forceRestart && position == index && isLiveCurrent(song)) {
            // 队列里点到的就是正在放的那一首: 继续放, 不从头再来
            Diag.i("PlayerController", "playAt 命中当前曲目《${song.name}》, 不重播")
            if (!isPlaying) resume()
            notifySong()
            saveState()
            return
        }
        index = position
        notifySong()
        saveState()
        startPlayback(song, autoPlay = true)
    }

    /**
     * 这首歌是否就是当前正在出声 (或暂停中) 的那一首。
     *
     * 判据不止 key 相同: 冷启动 [restoreState] 恢复出来的队列也有「当前曲目」,
     * 但并没有解码器在跑, 此时点它必须正常起播。所以还要求 Service 已经 prepare 过、
     * 手上真的有可用直链。
     */
    private fun isLiveCurrent(song: Song): Boolean {
        val cur = current() ?: return false
        if (cur.key() != song.key()) return false
        val s = service ?: return false
        return s.isPrepared() && currentUrl.isNotEmpty()
    }

    // ---------------- 内部 ----------------

    private fun nextIndex(consume: Boolean): Int? {
        if (queue.isEmpty()) return null
        return when (Settings.playMode()) {
            PlayMode.SINGLE_LOOP -> if (consume) index else index
            PlayMode.SHUFFLE -> {
                if (shuffleOrder.isEmpty()) rebuildShuffle()
                val cur = shuffleOrder.indexOf(index)
                shuffleOrder.getOrNull((cur + 1) % shuffleOrder.size) ?: 0
            }
            PlayMode.LIST_LOOP -> (index + 1) % queue.size
        }
    }

    private fun rebuildShuffle() {
        shuffleOrder.clear()
        for (i in queue.indices) shuffleOrder.add(i)
        shuffleOrder.shuffle()
    }

    /**
     * 解析直链并交?Service 播放?     *
     * 优先命中缓存, 未命中时先在线播放同时写缓存, 二次播放即为本地秒开?     */
    private fun startPlayback(song: Song, autoPlay: Boolean) {
        Diag.i("PlayerController", "startPlayback 《${song.name}》 mid=${song.mid} local=${song.isLocal} autoPlay=$autoPlay")
        playSession++
        // 切歌: 上一轮的预取立即作废, 免得它继续占着 IO 线程下已经没意义的歌
        prefetchGeneration++
        uiHandler.removeCallbacks(prefetchTask)
        uiHandler.removeCallbacks(lyricTask)
        isBuffering = true
        notifyBuffering(true)
        currentUrl = ""
        // 换了歌, 上一首实际用的音质立即作废; 记下 mid 表示"这一首还没解析出来"
        actualQuality = null
        actualQualityMid = song.mid
        // 上一首的缓存下载作废: 否则 Hi-Res/SQ 动辄 90-150MB, 4 个 IO 线程
        // 会被它占满, 新歌连解析直链都要排队, 表现就是"点了没反应"
        CacheManager.cancelDownloads()
        // 先把上一首掐掉再解析。换歌要逐档探测直链, 有时要两三秒, 这期间如果旧歌还在响,
        // 用户听到的就是「界面明明切了, 歌却还在放上一首」, 然后才是一段空档 ——
        // 比单纯的空档更让人困惑。notify=false: 界面马上会切成新歌的 "播放中",
        // 中间那次 "已暂停" 广播会让按钮闪一下。
        if (autoPlay) service?.pause(notify = false)
        if (autoPlay) {
            isPlaying = true
        }
        notifyPlay()

        // 本地歌曲直接播放文件
        if (song.isLocal) {
            playLocal(song, autoPlay)
            return
        }

        val quality = Settings.onlineQuality()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // 连点下一首会并发起好几次 startPlayback。捕获当时的会话号, 每次异步结果回来都对一遍:
        // 对不上说明用户已经切走了, 结果直接丢弃 —— 否则旧结果会晚于新结果落地,
        // 把界面上的新歌顶成上一首, 就是"放的和显示的不是一首"。
        val mySession = playSession
        Task.io {
            // 排队期间用户又切了歌: 连 IO 线程都不必进去, 免得占着池子拖慢新任务
            if (mySession != playSession) {
                Diag.i("PlayerController", "跳过作废的解析任务 《${song.name}》")
                return@io
            }
            val aborted = { mySession != playSession }
            try {
                // 逐档探测直链可用性再决定用哪一档: 服务端会对部分歌曲给出 purl 非空、
                // result=0 但实际 HTTP 404 的地址
                // (实测《如春(Run)》mid=001JOLTf4UlH6q 的 Hi-Res/RS01 直链桌面和手机都 404,
                // 同一首歌标准档却返 200)。只看 purl 非空发现不了这种坏链。
                // resolvePlayable 会一直降到真能读到字节的那一档
                val picked = PlayUrlApi.resolvePlayable(song, quality)
                if (aborted()) {
                    Diag.i("PlayerController", "解析已作废 《${song.name}》")
                    return@io
                }
                if (picked == null) {
                    Diag.i(
                        "PlayerController",
                        "解析不出直链 《${song.name}》 vip=${song.vipOnly}"
                    )
                    isBuffering = false
                    notifyBuffering(false)
                    notifyError("无法播放《${song.name}》, 无版权或需要会员")
                    Task.main { PlayerController.autoSkip() }
                    return@io
                }
                val resolved = picked.first
                // 长度在探测时已经拿到, 不必再发一次请求
                val remoteSize = picked.second
                Diag.i(
                    "PlayerController",
                    "直链 ok 《${song.name}》 size=$remoteSize ${resolved.url.take(90)}"
                )
                currentUrl = resolved.url
                // 记下**实际**拿到的档位 (可能已经降过档), 并让界面把角标刷新成它。
                actualQuality = resolved.quality
                actualQualityMid = song.mid
                // 记下来: 输出徽章要拿这个文件名去读源采样率, 歌词扫色也要靠它找完整缓存
                lastCacheName = resolved.fileName
                lastCacheMid = song.mid
                /*
                 * 音质角标和输出徽章的「源采样率 / 直通·重采样」都依赖上面几个字段,
                 * 解析一完成就通知一次, 不能等下一次换歌或重进播放页 ——
                 * 首次进播放页时徽章只有设备采样率, 重进才正确, 就是这里漏了刷新。
                 */
                notifySong()

                val ctx = service ?: com.portable.qqmusic.App.instance
                // 完整缓存命中 → 直接播本地文件, 最快也最省流量
                val cached = CacheManager.cachedFile(ctx, resolved.fileName)
                if (cached != null) {
                    Diag.i("PlayerController", "缓存命中 《${song.name}》 ${cached.name}")
                    val hitPath = cached.absolutePath
                    main.post {
                        if (aborted()) return@post
                        isBuffering = false
                        notifyBuffering(false)
                        val s = service
                        if (s == null) {
                            pendingSong = song
                            pendingUrl = resolved.url
                            pendingSize = remoteSize
                            pendingLocalPath = hitPath
                            pendingGrowing = false
                            pendingAutoPlay = autoPlay
                            withService(ctx) { }
                        } else {
                            s.load(song, resolved.url, autoPlay, remoteSize, hitPath)
                        }
                    }
                    return@io
                }

                // ② 未命中 → 边下边播: 后台线程先把 .part 按总长预分配再顺序写数据,
                //    攒够预缓冲就把这个还没下完但长度正确的文件当普通本地文件播,
                //    解码线程只读文件、完全不碰网络。
                //    刻意不"全下完再播": 一首 Hi-Res 约 90-150MB, 下完要几分钟,
                //    而 MP3/FLAC 都是流式格式, 有前几百 KB 就能起播。
                val handle = CacheManager.downloadInBackground(
                    ctx, resolved.url, resolved.fileName, remoteSize
                )
                if (!handle.awaitReady(15_000L)) {
                    val got = if (handle.partFile.exists()) handle.partFile.length() / 1024 else 0L
                    Diag.i(
                        "PlayerController",
                        "预缓冲失败 《${song.name}》 failed=${handle.failed} 已下=${got}KB"
                    )
                    main.post {
                        isBuffering = false
                        notifyBuffering(false)
                        notifyError("缓冲失败, 请检查网络")
                    }
                    return@io
                }
                val partPath = handle.partFile.absolutePath
                val bufferedKb = handle.partFile.length() / 1024
                Diag.i(
                    "PlayerController",
                    "边下边播 《${song.name}》 size=$remoteSize 预缓冲=${bufferedKb}KB " +
                        "part=${handle.partFile.name}"
                )
                main.post {
                    if (aborted()) return@post
                    isBuffering = false
                    notifyBuffering(false)
                    val s = service
                    if (s == null) {
                        pendingSong = song
                        pendingUrl = resolved.url
                        pendingSize = remoteSize
                        pendingLocalPath = partPath
                        pendingGrowing = false
                        pendingAutoPlay = autoPlay
                        withService(ctx) { }
                    } else {
                        s.load(song, resolved.url, autoPlay, remoteSize, partPath)
                    }
                }
            } catch (t: Throwable) {
                Log.w("PlayerController.play", t)
                isBuffering = false
                notifyBuffering(false)
                notifyError("播放失败: ${t.message ?: "网络错误"}")
            }
        }
    }

    private var autoSkipDepth = 0

    // ---------------- 后续曲目的预缓存 ----------------

    /**
     * 起播后预热接下来的几首?     *
     * 只解析直链并落盘缓存, 不参与播放。用户点开下一首时直接命中本地文件,
     * 不用再等一?解析 + 预缓??     */
    private const val PREFETCH_COUNT = 3

    /** 起播后先等一会再预热: 当前这首还在预缓? 立刻抢线程会让它断流 */
    private const val PREFETCH_DELAY_MS = 3_000L

    /**
     * 预取世代?     *
     * ?[playSession] 分开计数: 切歌时必须让上一轮预取立刻收? 否则它会继续
     * 下着已经没有意义的歌, 跟当前这首的预缓冲抢?4 ?IO 线程?     */
    @Volatile
    private var prefetchGeneration = 0

    private val prefetchTask = Runnable { prefetchUpcoming() }

    /** 歌词预热比音频预缓存更早: 机器翻译本身还要好几秒, 早点开始才能赶上用户点开歌词页 */
    private const val LYRIC_PREFETCH_DELAY_MS = 1_500L

    private val lyricTask = Runnable { prefetchLyrics() }

    /** 当前播放模式下从 [cur] 往后数 [step] 首的下标; 单曲循环没有"后面" */
    private fun indexAfter(cur: Int, step: Int): Int? {
        if (queue.isEmpty() || cur !in queue.indices) return null
        return when (Settings.playMode()) {
            PlayMode.SINGLE_LOOP -> null
            PlayMode.SHUFFLE -> {
                if (shuffleOrder.isEmpty()) return null
                val pos = shuffleOrder.indexOf(cur)
                if (pos < 0) null else shuffleOrder[(pos + step) % shuffleOrder.size]
            }
            PlayMode.LIST_LOOP -> (cur + step) % queue.size
        }
    }

    /** 这首歌是否已有任意一档落在缓存里 (避免为了判断而白打一次直链接? */
    private fun cachedAnyQuality(ctx: Context, song: Song, from: Quality): Boolean {
        if (song.mid.isEmpty()) return false
        return Quality.ladderFrom(from).any { q ->
            CacheManager.cachedFile(ctx, q.prefix + song.mid + song.mid + q.ext) != null
        }
    }

    private fun prefetchUpcoming() {
        val ctx = service ?: com.portable.qqmusic.App.instance ?: return
        if (queue.isEmpty() || index < 0) return
        val gen = ++prefetchGeneration
        val quality = Settings.onlineQuality()

        val targets = ArrayList<Song>(PREFETCH_COUNT)
        val seen = HashSet<String>(PREFETCH_COUNT)
        for (step in 1..PREFETCH_COUNT) {
            val i = indexAfter(index, step) ?: break
            if (i == index) break
            val s = queue.getOrNull(i) ?: break
            if (s.isLocal || s.mid.isEmpty()) continue
            if (!seen.add(s.mid)) continue
            if (cachedAnyQuality(ctx, s, quality)) continue
            targets.add(s)
        }
        if (targets.isEmpty()) return
        Diag.i(
            "PlayerController",
            "预缓存开始 ${targets.size} 首: ${targets.joinToString(" / ") { it.name }}"
        )

        Task.io {
            for (song in targets) {
                if (gen != prefetchGeneration) {
                    Diag.i("PlayerController", "预缓存中断(已切歌) 《${song.name}》")
                    return@io
                }
                try {
                    val picked = PlayUrlApi.resolvePlayable(song, quality) ?: continue
                    if (gen != prefetchGeneration) return@io
                    val resolved = picked.first
                    if (CacheManager.cachedFile(ctx, resolved.fileName) != null) continue
                    val f = CacheManager.cacheBlocking(ctx, resolved.url, resolved.fileName)
                    Diag.i(
                        "PlayerController",
                        "预缓存${if (f != null) "完成" else "失败"} 《${song.name}》" +
                            "${if (f != null) " ${f.length() / 1024}KB" else ""}"
                    )
                } catch (t: Throwable) {
                    Log.w("PlayerController.prefetch", t)
                }
            }
        }
    }

    /**
     * 预热「当前 + 后面几首」的歌词。
     *
     * 需求: 播当前这首时就把后续曲目的歌词先拉下来, 这样切歌后点开歌词页
     * 不用再空等一次网络往返 (QRC/译文都在 [LyricPrefetcher] 的缓存里)。
     */
    private fun prefetchLyrics() {
        val ctx = service ?: com.portable.qqmusic.App.instance ?: return
        val list = ArrayList<Song>(PREFETCH_COUNT + 1)
        val seen = HashSet<String>(PREFETCH_COUNT + 1)
        current()?.let {
            // 用 hasRealMid 而不是 !isLocal: 下载下来的歌也是本地文件, 但带着原曲 mid,
            // 它的歌词/译文同样该预热。
            if (it.hasRealMid && seen.add(it.mid)) list.add(it)
        }
        for (step in 1..PREFETCH_COUNT) {
            val i = indexAfter(index, step) ?: break
            if (i == index) break
            val s = queue.getOrNull(i) ?: break
            if (!s.hasRealMid) continue
            if (seen.add(s.mid)) list.add(s)
        }
        if (list.isEmpty()) return
        Diag.i(
            "PlayerController",
            "歌词预热 ${list.size} 首: ${list.joinToString(" / ") { it.name }}"
        )
        LyricPrefetcher.prefetch(ctx, list)
    }

    /** 每次 startPlayback 递增: 冷静期结束时用它判断这轮报错是不是已经过期 */
    private var playSession = 0

    /** 最近一次成?prepare 的轮? ?playSession 相等说明当前这首确实出声?*/
    private var preparedSession = -1

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 报错容错阈? 收到 onError 后先挂这么久再决定要不要跳歌?     *
     * MediaPlayer 在切歌瞬间经常把上一个实例的错误回调投给当前曲目, 或者只?     * 临时解不开码。之前收到就? 结果整张歌单几秒内被冲光; 现在只要?5 秒里
     * 这首歌真的出声了 (onPrepared 到了), 就把报错当误报丢掉继续放?     */
    private const val ERROR_GRACE_MS = 5_000L

    private fun autoSkip() {
        Diag.i("PlayerController", "autoSkip depth=$autoSkipDepth 当前=${current()?.name}")
        if (autoSkipDepth > 3) {
            autoSkipDepth = 0
            isPlaying = false
            notifyPlay()
            return
        }
        autoSkipDepth++
        val next = nextIndex(consume = true)
        if (next == null || next == index) {
            autoSkipDepth = 0
            isPlaying = false
            notifyPlay()
            return
        }
        index = next
        notifySong()
        startPlayback(queue[index], autoPlay = true)
    }

    private fun playLocal(song: Song, autoPlay: Boolean) {
        val path = com.portable.qqmusic.data.LocalMusic.pathOf(song)
        if (path == null) {
            Diag.i("PlayerController", "playLocal 《${song.name}》 拿不到文件路径 mid=${song.mid} songId=${song.songId}")
            notifyError("本地文件不可访问")
            return
        }
        Diag.i("PlayerController", "playLocal 《${song.name}》 path=$path")
        isBuffering = false
        notifyBuffering(false)
        val s = service
        if (s == null) {
            pendingSong = song
            pendingUrl = path
            pendingAutoPlay = autoPlay
            // 本地文件本身就是完整文件, 直接作为 knownLocal 传下去
            // 既避免 urlFileName() 拿绝对路径去缓存目录里瞎找, 也跳过缓存命中判断
            pendingLocalPath = path
            withService(com.portable.qqmusic.App.instance) { }
            return
        }
        currentUrl = path
        s.load(song, path, autoPlay, knownSize = -1L, knownLocal = path)
    }

    /**
     * 供 Service 无缝预取下一首直链。
     *
     * 用 [PlayUrlApi.resolvePlayable] 而不是 [PlayUrlApi.getUrl]: 服务端会给 purl 非空、
     * result=0 但实际 404 的地址, 拿这种链接去预置解码器等于白预置。这里宁可多一次
     * Range 探测, 也要保证交给解码器的是真能读到字节的那一档。
     *
     * ⚠️ 会发网络请求, **必须**在 IO 线程调用。
     */
    fun resolveUrlFor(song: Song): String? {
        if (song.isLocal) return com.portable.qqmusic.data.LocalMusic.pathOf(song)
        return try {
            PlayUrlApi.resolvePlayable(song, Settings.onlineQuality())?.first?.url
        } catch (t: Throwable) {
            null
        }
    }

    private fun publishCurrentUrl(song: Song) {
        // 保留占位: 直链在解析完成后写入 currentUrl
    }

    // ---------------- Service 回调 ----------------

    fun setPlayingFlag(playing: Boolean) {
        isPlaying = playing
        notifyPlay()
    }

    fun onPrepared(durationMs: Int) {
        // 成功起播就清零: 否则前面几次解析失败累积的跳过计数会一直留着,
        // 之后任何一次失败都会直接判定「连跳太多」而停止播放
        autoSkipDepth = 0
        preparedSession = playSession
        isBuffering = false
        notifyBuffering(false)
        service?.publishState()
        notifyProgress(0, durationMs)
        // 起播稳定后再预热后面的曲目
        uiHandler.removeCallbacks(prefetchTask)
        uiHandler.postDelayed(prefetchTask, PREFETCH_DELAY_MS)
        // 歌词译文先一步预热: 翻译耗时以秒计, 不等音频缓存那几秒
        uiHandler.removeCallbacks(lyricTask)
        uiHandler.postDelayed(lyricTask, LYRIC_PREFETCH_DELAY_MS)
        // 猜你喜欢播到最后一首时把这批内容续上
        scheduleGuessExtension()
    }

    /**
     * 「猜你喜欢」播到最后一首时, 顺手取一批新的并并进队列。
     *
     * 之所以**在最后一首刚开播时**就取、而不是等它放完再取: 一首歌有几分钟,
     * 足够一次请求跑完; 等放完再取会让下一首前面空出一段静默。取回来直接 append 进队列,
     * 于是最后一首结束时 `hasNext()` 自然为真, [onCompletion] 不用为它写任何特例。
     *
     * 取不到 (断网 / 未登录) 就什么都不做 —— 队列不再增长, 播完按普通队列收尾,
     * 这比卡在"正在缓冲"不动要好。
     */
    private fun scheduleGuessExtension() {
        if (queueKind != QueueKind.GUESS) return
        if (index < 0 || index != queue.lastIndex) return
        if (guessExtensionLoading) return
        // 同一版队列只取一次; 续进来的那批会成为新的一版, 播到它的最后一首时再取
        if (guessExtensionRequestedFor == queue.size) return
        guessExtensionRequestedFor = queue.size
        guessExtensionLoading = true
        val expectedSize = queue.size
        Diag.i("PlayerController", "猜你喜欢播到最后一首(第 ${index + 1}/${expectedSize} 首), 开始续接")
        Task.io {
            val more = try {
                RecommendRepository.refreshGuess()
            } catch (t: Throwable) {
                Log.w("PlayerController.guessExtend", t)
                emptyList()
            }
            Task.main {
                guessExtensionLoading = false
                if (queueKind != QueueKind.GUESS) return@main
                if (more.isEmpty()) {
                    // 把标记放回去, 让这首下次开播时还能再试一次 (断网时不该一次失败就永久放弃)
                    guessExtensionRequestedFor = -1
                    Diag.i("PlayerController", "猜你喜欢续接失败, 队列保持不变")
                    return@main
                }
                // 服务端每次给的批次会有重叠, 按 mid 去重, 否则队列里会出现同一首歌两遍
                val known = HashSet<String>(queue.size * 2)
                for (s in queue) known.add(s.key())
                val fresh = more.filter { known.add(it.key()) }
                Diag.i(
                    "PlayerController",
                    "猜你喜欢续接拿到 ${more.size} 首, 去重后新增 ${fresh.size} 首, 队列 ${queue.size} -> ${queue.size + fresh.size}"
                )
                if (fresh.isEmpty()) return@main
                appendToQueue(fresh)
            }
        }
    }

    fun onCompletion() {
        Diag.i("PlayerController", "onCompletion 《${current()?.name}》 mode=${Settings.playMode()}")
        autoSkipDepth = 0
        val song = current()
        // 单曲循环: 重播当前曲目
        if (Settings.playMode() == PlayMode.SINGLE_LOOP && song != null) {
            startPlayback(song, autoPlay = true)
            return
        }
        if (!hasNext()) {
            isPlaying = false
            notifyPlay()
            service?.publishState()
            return
        }
        next(userTriggered = false)
    }

    fun onPlayError(what: Int, extra: Int) {
        val session = playSession
        val songName = current()?.name
        Diag.i(
            "PlayerController",
            "onPlayError 《$songName》 what=$what extra=$extra 等 ${ERROR_GRACE_MS}ms 确认 " +
                "url=${currentUrl.take(80)}"
        )
        // 刻意不弹错、不跳歌、也不立刻把 isPlaying 打回 false —— 决定推迟到冷静期结束再定
        // 这台机器的 NuPlayer 会在 prepare 阶段先抛一次 -38 再自己重试成功,
        // 一报错就提示用户反而会盖住"其实已经能放"的事实
        uiHandler.postDelayed({
            if (session != playSession) {
                Diag.i("PlayerController", "报错已过期(《$songName》), 早已切到别的歌, 忽略")
                return@postDelayed
            }
            if (preparedSession == playSession) {
                Diag.i("PlayerController", "报错是误报(《$songName》在冷静期内已出声), 继续播放")
                service?.publishState()
                return@postDelayed
            }
            Diag.i("PlayerController", "报错确认(《$songName》确实播不了), 跳到下一首")
            isPlaying = false
            notifyPlay()
            notifyError("《$songName》播不了, 已跳过")
            autoSkip()
        }, ERROR_GRACE_MS)
    }

    fun onBuffering(buffering: Boolean) {
        isBuffering = buffering
        notifyBuffering(buffering)
        service?.publishState()
    }

    /** 界面拿进? 由界面按需轮询, 避免常驻定时?*/
    fun pollProgress() {
        val pos = position()
        val dur = duration()
        val sec = pos / 1000
        if (sec != lastNotifiedSecond) {
            lastNotifiedSecond = sec
            notifyProgress(pos, dur)
        }
    }

    // ---------------- 状态持久化 ----------------

    fun saveState() {
        try {
            val arr = JSONArray()
            for (s in queue) {
                arr.put(
                    JSONObject()
                        .put("mid", s.mid)
                        .put("name", s.name)
                        .put("singer", s.singer)
                        .put("albumMid", s.albumMid)
                        .put("albumName", s.albumName)
                        .put("duration", s.durationSec)
                        .put("songId", s.songId)
                        .put("type", s.type)
                )
            }
            Settings.setLastSong(arr.toString())
            Settings.setLastIndex(index)
        } catch (t: Throwable) {
            Log.w("PlayerController.save", t)
        }
    }

    /**
     * 冷启动恢? 只恢复队列与「上次播放的曲目? 不自动播放也不启动服务?     */
    fun restoreState(context: Context) {
        if (queue.isNotEmpty()) return
        val raw = Settings.lastSong()
        if (raw.isEmpty()) return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                queue.add(
                    Song(
                        mid = o.optString("mid"),
                        name = o.optString("name"),
                        singer = o.optString("singer"),
                        albumMid = o.optString("albumMid"),
                        albumName = o.optString("albumName"),
                        durationSec = o.optInt("duration"),
                        songId = o.optLong("songId"),
                        type = o.optInt("type", Song.TYPE_ONLINE),
                        index = i
                    )
                )
            }
            index = Settings.lastIndex().coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            // 队列来源一起恢复: 猜你喜欢队列恢复后仍要禁止随机、并继续自动续接
            queueKind = Settings.queueKind()
            guessExtensionRequestedFor = -1
            guessExtensionLoading = false
            pendingAutoPlay = false
            isPlaying = false
            Log.d("PlayerController", "restored ${queue.size} songs, index=$index, kind=${queueKind.name}")
        } catch (t: Throwable) {
            Log.w("PlayerController.restore", t)
            queue.clear()
            index = -1
            queueKind = QueueKind.NORMAL
        }
    }

    fun playMode(): PlayMode = Settings.playMode()

    fun currentQueueKind(): QueueKind = queueKind

    fun cyclePlayMode() {
        val cur = Settings.playMode()
        // 猜你喜欢队列只在「列表循环 ↔ 单曲循环」之间切换, 随机被跳过。
        // 兜底那条 (cur 已经是 SHUFFLE) 是给"先开随机、再点猜你喜欢播放全部"这种情况用的,
        // setQueue 会把模式夹回来, 这里只是保证点一下不会又跳进随机。
        val next = if (queueKind == QueueKind.GUESS) {
            when (cur) {
                PlayMode.LIST_LOOP -> PlayMode.SINGLE_LOOP
                PlayMode.SINGLE_LOOP -> PlayMode.LIST_LOOP
                PlayMode.SHUFFLE -> PlayMode.LIST_LOOP
            }
        } else {
            PlayMode.next(cur)
        }
        Settings.setPlayMode(next)
        if (next == PlayMode.SHUFFLE) rebuildShuffle()
        notifyQueue()
        // 播放方式变了, 界面上的编排/徽章要立刻刷新一次 (不能等下一首歌才更新)
        notifySong()
    }

    /**
     * 本地歌曲借到在线元信息后, 把队列里这首歌的占位歌手换成真名。
     *
     * 本地文件的 TAG 里没有歌手时只能是「未知歌手」; 一旦按歌名匹配到了 QQ 音乐的
     * mid, 在线结果里是有真歌手的。这里只覆盖队列里同 mid 的那一条, 并保存 + 通知,
     * 播放页和迷你条下一帧就会显示真名, 不用等用户重进页面。
     *
     * 调用点可能在 IO 线程 (借元信息是阻塞的), 所以整个改写都折回主线程做,
     * 避免和主线程的 current()/startPlayback 抢队列。
     */
    fun applySinger(mid: String, singer: String) {
        if (mid.isEmpty() || singer.isBlank()) return
        Task.main {
            var changed = false
            for (i in queue.indices) {
                val s = queue[i]
                if (s.mid == mid && s.isUnknownSinger) {
                    queue[i] = s.copy(singer = singer)
                    changed = true
                }
            }
            if (changed) {
                saveState()
                notifySong()
            }
        }
    }

    /** 播放过渡效果变化: 同步给 Service */
    fun onFadeSettingChanged() {
        service?.releaseNext()
        if (Settings.playFade() == PlayFade.SEAMLESS) service?.preloadNext()
    }
}
