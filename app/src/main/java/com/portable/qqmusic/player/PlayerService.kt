package com.portable.qqmusic.player

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationManagerCompat
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.PlayFade
import com.portable.qqmusic.core.PlayMode
import com.portable.qqmusic.core.Settings
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.CacheManager
import com.portable.qqmusic.data.api.PlayUrlApi
import com.portable.qqmusic.data.api.Song

/**
 * 后台播放服务。
 *
 * 职责:
 *  - 持有 [AudioEngine], 处理播放队列与播放模式
 *  - 前台服务 + MediaStyle 通知 (锁屏 / 通知栏控制)
 *  - 音频焦点与耳机拔出处理
 *  - 播放中的音频写入缓存, 便于二次秒开与离线回放
 *
 * 开销控制:
 *  - 进度回调只在界面可见时以 500ms 间隔触发
 *  - 通知仅在状态实际变化时刷新
 */
class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "com.portable.qqmusic.action.PLAY"
        const val ACTION_PAUSE = "com.portable.qqmusic.action.PAUSE"
        const val ACTION_TOGGLE = "com.portable.qqmusic.action.TOGGLE"
        const val ACTION_NEXT = "com.portable.qqmusic.action.NEXT"
        const val ACTION_PREV = "com.portable.qqmusic.action.PREV"
        const val ACTION_STOP = "com.portable.qqmusic.action.STOP"
        const val ACTION_SEEK = "com.portable.qqmusic.action.SEEK"
        const val EXTRA_POSITION = "position"

        /** 淡入淡出模式下提前多久开始淡出 (毫秒) */
        private const val FADE_WINDOW_MS = 400

        fun sendCommand(context: Context, action: String, position: Int = -1) {
            val intent = Intent(context, PlayerService::class.java).setAction(action)
            if (position >= 0) intent.putExtra(EXTRA_POSITION, position)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (t: Throwable) {
                Log.w("PlayerService.cmd", t)
            }
        }
    }

    private lateinit var engine: AudioEngine
    private lateinit var mediaSession: MediaSessionCompat

    /**
     * 状态栏/锁屏媒体卡片的封面。
     *
     * 系统那份卡片只认 [MediaMetadataCompat] 里的位图 (或 URI); 不给的话就是一块灰底。
     * 封面按 mid 缓存最近 3 张, 切回来时不用再解码一次。
     */
    private val sessionArt = object : android.util.LruCache<String, android.graphics.Bitmap>(3) {}

    /** 封面为空时已经安排过重试的 mid, 避免无限重推 */
    private val artRetry = java.util.Collections.synchronizedSet(HashSet<String>())
    private var sessionArtLoading = ""
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var foreground = false

    @Volatile
    private var prepared = false

    /** 淡入淡出模式下的定时器: 提前进入淡出 */
    private var fadeOutRunnable: Runnable? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                engine.pause()
                publishState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = AudioEngine(this)
        engine.callback = engineCallback
        PlayerController.attach(this)

        mediaSession = MediaSessionCompat(this, "QQMusicPortable").apply {
            setCallback(sessionCallback)
            isActive = true
        }
        PlayerNotification.ensureChannel(this)

        try {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } catch (t: Throwable) {
            Log.w("PlayerService", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlayerController.resume()
            ACTION_PAUSE -> PlayerController.pause()
            ACTION_TOGGLE -> PlayerController.toggle()
            ACTION_NEXT -> PlayerController.next(userTriggered = true)
            ACTION_PREV -> PlayerController.previous()
            ACTION_STOP -> {
                PlayerController.pause()
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_SEEK -> {
                val pos = intent.getIntExtra(EXTRA_POSITION, -1)
                if (pos >= 0) PlayerController.seekTo(pos)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(noisyReceiver)
        } catch (t: Throwable) {
        }
        fadeOutRunnable?.let { handler.removeCallbacks(it) }
        engine.release()
        mediaSession.release()
        PlayerController.detach()
        super.onDestroy()
    }

    // ---------------- AudioEngine 回调 ----------------

    private val engineCallback = object : AudioEngine.Callback {
        override fun onPrepared(durationMs: Int) {
            prepared = true
            val seek = engine.consumePendingSeek()
            if (seek > 0) engine.seekTo(seek)
            PlayerController.onPrepared(durationMs)
            if (PlayerController.isPlaying) {
                engine.start()
            }
            scheduleFadeOut(durationMs)
            preloadNext()
            publishState()
            ensureForeground()
        }

        override fun onCompletion() {
            prepared = false
            PlayerController.onCompletion()
        }

        override fun onError(what: Int, extra: Int) {
            prepared = false
            Log.w("PlayerService", "media error what=$what extra=$extra")
            PlayerController.onPlayError(what, extra)
        }

        override fun onBufferingStart() = PlayerController.onBuffering(true)

        override fun onBufferingEnd() = PlayerController.onBuffering(false)

        override fun onFocusLoss() {
            engine.pause()
            publishState()
        }

        override fun onFocusLossTransient() {
            PlayerController.pauseFromFocus()
        }

        override fun onFocusDuck() {
            engine.setVolume(0.3f)
        }

        override fun onFocusGain() {
            engine.applyGain(immediate = true)
            if (PlayerController.isPlaying) engine.start()
        }
    }

    // ---------------- MediaSession ----------------

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = PlayerController.resume()
        override fun onPause() = PlayerController.pause()
        override fun onSkipToNext() = PlayerController.next(userTriggered = true)
        override fun onSkipToPrevious() = PlayerController.previous()
        override fun onSeekTo(pos: Long) = PlayerController.seekTo(pos.toInt())
        override fun onStop() {
            PlayerController.pause()
            stopSelf()
        }
    }

    // ---------------- 供 PlayerController 调用 ----------------

    fun load(
        song: Song,
        url: String,
        autoPlay: Boolean,
        knownSize: Long = -1L,
        /** 调用方已经下好的本地缓存路径; 空则这里自己找 */
        knownLocal: String = "",
        /** [knownLocal] 指向正在下载中的 .part 文件时置 true: 交给 GrowingFileDataSource 边下边播 */
        growing: Boolean = false
    ) {
        prepared = false
        val cached = if (knownLocal.isNotEmpty()) java.io.File(knownLocal)
        else CacheManager.cachedFile(this, urlFileName(url))
        val localPath = cached?.takeIf { it.exists() }?.absolutePath
            ?: com.portable.qqmusic.data.DownloadManager.localPath(song)
        engine.play(song, url, localPath, knownSize, growing)
        PlayerController.currentUrl = url
        if (autoPlay) {
            // 实际 start 在 onPrepared 里执行
            PlayerController.setPlayingFlag(true)
        }
        ensureForeground()
        publishState()
    }

    fun start() {
        engine.start()
        ensureForeground()
        publishState()
    }

    /**
     * @param notify false 时只停声音、不广播状态。
     * 切歌路径会用到: 要先把上一首掐掉, 但界面紧接着就切成新歌的 "播放中",
     * 中间那次 "已暂停" 广播会让按钮闪一下。
     */
    fun pause(notify: Boolean = true) {
        engine.pause()
        if (notify) publishState()
    }

    fun seekTo(ms: Int) {
        engine.seekTo(ms)
        val dur = engine.durationMs
        if (dur > 0) scheduleFadeOut(dur)
        publishState()
    }

    fun position(): Int = engine.positionMs

    fun duration(): Int = engine.durationMs

    fun isPrepared(): Boolean = prepared

    fun releaseNext() = engine.clearNext()

    /** 无缝播放: 预置下一首到平台解码器 */
    fun preloadNext() {
        if (Settings.playFade() != PlayFade.SEAMLESS) return
        val next = PlayerController.peekNext() ?: return
        // ⚠️ resolveUrlFor 要发网络请求 (取 vkey), 而这里是被 MediaPlayer 的
        // onPrepared 回调进来的 —— 那是主线程。主线程发网络会被拦成
        // NetworkOnMainThreadException, 而 resolveUrlFor 自己 catch 了它并返回 null,
        // 于是「无缝播放预取」一直是安静地什么都不做。必须挪到 IO 线程。
        Task.io {
            val url = PlayerController.resolveUrlFor(next) ?: return@io
            val cached = CacheManager.cachedFile(this, urlFileName(url))
            Task.main {
                // 解析期间用户可能已经切歌, 别把上一个 next 塞进解码器
                if (PlayerController.peekNext()?.mid != next.mid) return@main
                val ok = engine.setNext(next, url, cached?.absolutePath)
                Log.d("PlayerService", "preloadNext ${next.name} -> $ok")
            }
        }
    }

    private fun scheduleFadeOut(durationMs: Int) {
        fadeOutRunnable?.let { handler.removeCallbacks(it) }
        if (Settings.playFade() != PlayFade.CROSSFADE) return
        if (durationMs <= FADE_WINDOW_MS * 2) return
        val delay = (durationMs - FADE_WINDOW_MS).toLong()
        val r = Runnable {
            // 提前 400ms 淡出, 由 onCompletion 切到下一首
            engine.startFadeOut(FADE_WINDOW_MS.toLong())
        }
        fadeOutRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun urlFileName(url: String): String = url.substringAfterLast('/').substringBefore('?')

    private fun ensureForeground() {
        if (foreground) return
        try {
            startForeground(
                PlayerNotification.NOTIFICATION_ID,
                buildNotification()
            )
            foreground = true
        } catch (t: Throwable) {
            Log.w("PlayerService.fg", t)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (t: Throwable) {
        }
        foreground = false
    }

    fun refreshNotification() {
        if (!foreground) return
        try {
            NotificationManagerCompat.from(this).notify(
                PlayerNotification.NOTIFICATION_ID,
                buildNotification()
            )
        } catch (t: Throwable) {
            Log.w("PlayerService.notify", t)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val song = PlayerController.current()
        // 已经取到过的封面直接用在通知上; 没取到时先空着, 拿到后 publishState 会重建
        val cover: Bitmap? = song?.let { sessionArt.get(it.mid) }
        return PlayerNotification.build(
            this,
            song,
            PlayerController.isPlaying,
            PlayerController.hasNext(),
            PlayerController.hasPrevious(),
            cover,
            mediaSession.sessionToken
        )
    }

    /**
     * 系统媒体卡片的封面圆角。
     *
     * 拿不到系统卡片的圆角半径, 就按用户建议「把歌曲详情页的封面圆角缩放过来」:
     * 详情页封面是 `屏宽 - 56dp` 的正方形、圆角 12dp, 取这个比例再乘到封面位图上。
     * 位图会被系统缩放到卡片尺寸, 圆角因此等比缩放, 观感和详情页一致。
     */
    private fun roundedArt(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val dm = resources.displayMetrics
        val coverSide = (dm.widthPixels - 56f * dm.density).coerceAtLeast(1f)
        val ratio = (12f * dm.density / coverSide).coerceIn(0.02f, 0.30f)
        val size = src.width.coerceAtMost(src.height)
        if (size <= 0) return src
        val radius = (size * ratio).coerceAtLeast(1f)
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }
        val rect = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val left = (src.width - size) / 2f
        val top = (src.height - size) / 2f
        canvas.drawBitmap(src, -left, -top, paint)
        return out
    }

    /** 状态变化时同步通知与 MediaSession */
    fun publishState() {
        val playing = PlayerController.isPlaying && engine.isPlaying
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val pos = engine.positionMs.toLong()
        try {
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(state, pos, if (playing) 1.0f else 0f)
                    .build()
            )
            val song = PlayerController.current()
            // 本地歌不能用 coverUrl(): 那是 MediaStore 的 albumart URI, 没内嵌封面时
            // 给的是系统媒体库默认图; 这里走「借到的在线封面 → 内嵌封面」的顺序
            val cover = com.portable.qqmusic.ui.util.SongArt.coverUrl(this, song)
            val art = if (song == null) null else sessionArt.get(song.mid)
            val meta = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, song?.name ?: "")
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, song?.singer ?: "")
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, song?.albumName ?: "")
                .putLong(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION,
                    engine.durationMs.toLong()
                )
            if (cover.isNotEmpty()) {
                // 给 URI: 系统自己去取, 比塞位图省内存; 位图再补一份给不吃 URI 的 ROM
                meta.putString(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, cover
                )
                meta.putString(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART_URI, cover
                )
            }
            if (art != null && song != null) {
                meta.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                meta.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART, art)
            }
            mediaSession.setMetadata(meta.build())

            // 本地歌的在线信息可能还没匹配完 (封面为空): 过几秒再推一次, 只试一次
            if (song != null && cover.isEmpty() && artRetry.add(song.mid)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    publishState()
                }, 4_000L)
            }

            // 首次碰到这首歌时异步取一次封面, 拿到后重新推一遍元数据
            if (song != null && cover.isNotEmpty() &&
                sessionArt.get(song.mid) == null && sessionArtLoading != song.mid
            ) {
                val mid = song.mid
                sessionArtLoading = mid
                com.portable.qqmusic.ui.util.ImageLoader.loadBitmap(
                    this, cover, 512,
                    onReady = { bmp ->
                        sessionArtLoading = ""
                        sessionArt.put(mid, roundedArt(bmp))
                        if (PlayerController.current()?.mid == mid) publishState()
                    },
                    onFail = { sessionArtLoading = "" }
                )
            }
        } catch (t: Throwable) {
            Log.w("PlayerService.media", t)
        }
        refreshNotification()
    }
}
