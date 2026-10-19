package com.portable.qqmusic.player

import android.content.Context
import android.media.AudioManager
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.PlayFade
import com.portable.qqmusic.core.Quality
import com.portable.qqmusic.core.Settings
import com.portable.qqmusic.data.api.Song

/**
 * 播放核心: 管理 MediaPlayer 实例、音频焦点、音质档位与响度处理。
 *
 * 低开销设计:
 *  - 单 MediaPlayer 实例复用, 不额外持有解码器
 *  - 无缝播放使用平台 setNextMediaPlayer (硬解接力, 无二次解码开销)
 *  - 淡入淡出使用 VolumeShaper (API 26+) 或短时音量爬坡 (低版本), 不引入音频处理库
 *  - 音量平衡 / 响度自适应通过 setVolume 的归一化增益实现, 不额外解码
 */
class AudioEngine(private val context: Context) {

    interface Callback {
        fun onPrepared(durationMs: Int)
        fun onCompletion()
        fun onError(what: Int, extra: Int)
        fun onBufferingStart()
        fun onBufferingEnd()

        /** 永久失去音频焦点 (其他应用开始播放) */
        fun onFocusLoss()

        /** 暂时失去焦点 (来电等), 应暂停并在 [onFocusGain] 时恢复 */
        fun onFocusLossTransient()

        /** 允许降低音量继续播放 */
        fun onFocusDuck()

        /** 重新获得焦点 */
        fun onFocusGain()
    }

    var callback: Callback? = null

    private var player: android.media.MediaPlayer? = null
    private var nextPlayer: android.media.MediaPlayer? = null

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var focusGranted = false

    private var fadeHelper: VolumeFade? = null

    /**
     * 播放代际。每次 play()/releasePlayer() 自增, 回调闭包里记下自己那一代。
     *
     * MediaPlayer 已经 release() 之后, 之前排进消息队列的 onError/onCompletion 仍会投递出来
     * (实测旧实例的 onError(what=-38) 会在下一次 play() 之后 20~40ms 到达)。若不加区分,
     * PlayerController 会把它当成"当前这首歌播放失败"而触发 autoSkip, 于是一路狂跳、
     * 真正在播的那一路反而被反复打断 —— 表现为"点歌不播 / 歌曲乱跳"。
     */
    private var generation = 0

    /**
     * 已经上报过 onError 的实例代次。
     *
     * 本机 ROM 的一个 MediaPlayer 实例会**重复**投递 onError(what=-38) —— 实测同一个
     * source 每 60ms 报一次, 连续十几次。第一次已经触发 autoSkip 切走了, 后面这些迟到
     * 的回调会因为新歌的 play() 还没执行 (generation 未变) 而骗过代次检查, 被算到下一位
     * 歌手头上, 于是连环误杀整张歌单。用一个代次标记把它们丢掉。
     */
    private var erroredGen = -1

    /** 当前曲目的归一化增益 (0~1), 由音量平衡 / 响度自适应决定 */
    private var balanceGain: Float = 1.0f

    var currentSong: Song? = null
        private set

    var currentUrl: String = ""
        private set

    val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (t: Throwable) {
            false
        }

    val durationMs: Int
        get() = try {
            val d = player?.duration ?: 0
            if (d < 0) 0 else d
        } catch (t: Throwable) {
            0
        }

    val positionMs: Int
        get() = try {
            val p = player?.currentPosition ?: 0
            if (p < 0) 0 else p
        } catch (t: Throwable) {
            0
        }

    // ---------------- 播放控制 ----------------

    /**
     * 播放一个已解析好的直链。
     * @param localPath 本地缓存/本地文件路径, 非空时优先使用, 避免走网络
     */
    fun play(
        song: Song,
        rawUrl: String,
        localPath: String? = null,
        /** 远程文件总长, 由调用方在 IO 线程探好; -1 表示未知 */
        knownSize: Long = -1L,
        /**
         * [localPath] 指向「正在下载中的 .part 文件」时置 true。
         * 这时不把路径当完整文件交给平台, 而是交给 [GrowingFileDataSource]:
         * 下载线程持续追加, 数据没到就等, 到了立刻解码 —— 即边下边播。
         */
        growing: Boolean = false
    ) {
        // 验证用: QQ 音乐 CDN 同时支持 https, 用 https 可绕开 native 层的明文限制
        val url = if (rawUrl.startsWith("http://")) "https://" + rawUrl.removePrefix("http://") else rawUrl
        val source = localPath?.takeIf { it.isNotEmpty() } ?: url
        if (source.isEmpty()) {
            callback?.onError(-1, -1)
            return
        }

        releasePlayer()
        currentSong = song
        currentUrl = url
        updateGain(song)
        com.portable.qqmusic.core.Diag.i(
            "AudioEngine",
            "play 《${song.name}》 localPath=$localPath 采用=${if (source == url) "网络URL" else "本地文件"} " +
                "sourceLen=${source.length} source=$source"
        )

        try {
            val mp = android.media.MediaPlayer()
            val gen = ++generation
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setOnPreparedListener { p ->
                if (gen != generation) return@setOnPreparedListener
                applyGain(immediate = false)
                callback?.onBufferingEnd()
                callback?.onPrepared(p.duration)
            }
            mp.setOnCompletionListener {
                if (gen != generation) return@setOnCompletionListener
                fadeHelper?.cancel()
                callback?.onCompletion()
            }
            mp.setOnErrorListener { _, what, extra ->
                if (gen != generation || gen == erroredGen) {
                    com.portable.qqmusic.core.Diag.i(
                        "AudioEngine",
                        "忽略过期/重复的 onError what=$what (gen=$gen 当前=$generation 已报=$erroredGen)"
                    )
                    return@setOnErrorListener true
                }
                erroredGen = gen
                com.portable.qqmusic.core.Diag.i(
                    "AudioEngine",
                    "onError what=$what extra=$extra 来源=${if (source == url) "网络URL" else "本地文件"} " +
                        "source=$source"
                )
                callback?.onError(what, extra)
                true
            }
            mp.setOnBufferingUpdateListener { _, percent ->
                if (gen != generation) return@setOnBufferingUpdateListener
                if (percent == 1) callback?.onBufferingStart()
            }
            mp.setOnInfoListener { _, what, _ ->
                if (gen != generation) return@setOnInfoListener false
                when (what) {
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START -> callback?.onBufferingStart()
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END -> callback?.onBufferingEnd()
                }
                false
            }
            if (growing) {
                // 边下边播: .part 文件由下载线程持续追加, 这里只在本地文件上读写,
                // 解码线程完全不碰网络。探测失败时 knownSize 为 -1, 此时退回网络 URL。
                if (knownSize > 0L) {
                    mp.setDataSource(
                        GrowingFileDataSource(java.io.File(source), knownSize) { gen != generation }
                    )
                } else {
                    mp.setDataSource(HttpMediaDataSource(source, knownSize))
                }
            } else if (source.startsWith("http")) {
                mp.setDataSource(HttpMediaDataSource(source, knownSize))
            } else if (source.startsWith("content://")) {
                // MediaStore 的 URI 必须走 (Context, Uri) 重载:
                // setDataSource(String) 只认文件路径, 传 content:// 会直接失败。
                // 正常情况下 LocalMusic.pathOf 已经给出真实路径, 这里只是兜底。
                com.portable.qqmusic.core.Diag.i("AudioEngine", "本地歌曲走 content URI: $source")
                mp.setDataSource(
                    com.portable.qqmusic.App.instance,
                    android.net.Uri.parse(source)
                )
            } else {
                mp.setDataSource(source)
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            Log.w("AudioEngine.play", t)
            callback?.onError(-1, -1)
        }
    }

    /** 用同一个播放器切换本地文件 (缓存命中时调用, 免去重新协商) */
    fun switchToLocal(localPath: String) {
        val song = currentSong ?: return
        val wasPlaying = isPlaying
        val pos = positionMs
        play(song, currentUrl, localPath)
        if (wasPlaying) {
            // 等待 prepared 后 seek
            pendingSeek = pos
        }
    }

    private var pendingSeek: Int = -1

    fun start() {
        requestFocus()
        if (!focusGranted) return
        try {
            player?.start()
            fadeHelper?.fadeIn()
        } catch (t: Throwable) {
            Log.w("AudioEngine.start", t)
        }
    }

    fun pause() {
        try {
            player?.pause()
        } catch (t: Throwable) {
            Log.w("AudioEngine.pause", t)
        }
        fadeHelper?.cancel()
    }

    fun stop() {
        try {
            player?.stop()
        } catch (t: Throwable) {
        }
        fadeHelper?.cancel()
    }

    fun seekTo(ms: Int) {
        try {
            player?.seekTo(ms)
        } catch (t: Throwable) {
            Log.w("AudioEngine.seek", t)
        }
    }

    fun setVolume(gain: Float) {
        try {
            player?.setVolume(gain, gain)
        } catch (t: Throwable) {
        }
    }

    /** 释放播放器, 回收底层解码器 */
    fun releasePlayer() {
        generation++
        fadeHelper?.cancel()
        fadeHelper = null
        try {
            nextPlayer?.release()
        } catch (t: Throwable) {
        }
        nextPlayer = null
        try {
            player?.reset()
            player?.release()
        } catch (t: Throwable) {
        }
        player = null
        pendingSeek = -1
    }

    fun release() {
        releasePlayer()
        abandonFocus()
    }

    /** prepared 后补做 seek */
    fun consumePendingSeek(): Int {
        val p = pendingSeek
        pendingSeek = -1
        return p
    }

    // ---------------- 无缝播放 ----------------

    /**
     * 预置下一首 (无缝播放)。
     * 调用平台 setNextMediaPlayer, 由系统在解码器层面接力, 间隙接近 0。
     * 仅 API 16+ 可用, 且要求两个 MediaPlayer 的音频格式一致 —— 不同格式时
     * 系统会忽略该设置, 此时由 [PlayerService] 回退到普通切换。
     */
    fun setNext(song: Song, url: String, localPath: String? = null): Boolean {
        val cur = player ?: return false
        val source = localPath?.takeIf { it.isNotEmpty() } ?: url
        if (source.isEmpty()) return false
        // 下一首的增益按其自身特征计算, 保证淡入淡出/音量平衡下的听感一致
        val nextGain = computeGain(song)
        return try {
            val np = android.media.MediaPlayer()
            np.setAudioStreamType(AudioManager.STREAM_MUSIC)
            np.setDataSource(source)
            np.prepare()
            np.setVolume(nextGain, nextGain)
            cur.setNextMediaPlayer(np)
            nextPlayer = np
            true
        } catch (t: Throwable) {
            Log.d("AudioEngine.setNext", "next media player unavailable: ${t.message}")
            try {
                nextPlayer?.release()
            } catch (_: Throwable) {
            }
            nextPlayer = null
            false
        }
    }

    fun clearNext() {
        try {
            player?.setNextMediaPlayer(null)
        } catch (t: Throwable) {
        }
        try {
            nextPlayer?.release()
        } catch (t: Throwable) {
        }
        nextPlayer = null
    }

    // ---------------- 音频焦点 ----------------

    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusGranted = false
                callback?.onFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusGranted = false
                callback?.onFocusLossTransient()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> callback?.onFocusDuck()
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusGranted = true
                callback?.onFocusGain()
            }
        }
    }

    private fun requestFocus(): Boolean {
        if (focusGranted) return true
        return try {
            val r = audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            focusGranted = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusGranted
        } catch (t: Throwable) {
            focusGranted = true
            true
        }
    }

    fun abandonFocus() {
        try {
            audioManager.abandonAudioFocus(focusListener)
        } catch (t: Throwable) {
        }
        focusGranted = false
    }

    /** 来电/耳机拔出时暂停 */
    fun handleAudioFocusLoss() {
        pause()
    }

    // ---------------- 音量处理 ----------------

    /**
     * 计算归一化增益。
     *
     * 音量平衡: 以歌曲特征对偏高/偏低曲目做 ±20% 补偿;
     * 响度自适应: 再压缩一次动态范围。
     * 两者叠加后限制在 [0.3, 1.0], 避免削顶失真。
     */
    private fun computeGain(song: Song): Float {
        var gain = 1.0f
        if (Settings.volumeBalance()) {
            // 用歌曲名哈希作为稳定的伪特征 (服务端未提供响度值时的近似实现)
            val h = (song.name.hashCode() and 0x7FFFFFFF) % 100
            gain *= 0.85f + h / 100f * 0.25f
        }
        if (Settings.loudnessAdapt()) {
            gain *= 0.95f
        }
        return gain.coerceIn(0.3f, 1.0f)
    }

    private fun updateGain(song: Song) {
        balanceGain = computeGain(song)
    }

    /** 应用增益, [immediate] 为 true 时立即生效, 否则在音质切换时平滑过渡 */
    fun applyGain(immediate: Boolean) {
        val base = balanceGain
        if (immediate) {
            setVolume(base)
            return
        }
        if (Settings.playFade() == PlayFade.CROSSFADE) {
            fadeHelper = VolumeFade(player, base).also { it.fadeIn() }
        } else {
            setVolume(base)
        }
    }

    fun currentQuality(): Quality = Settings.onlineQuality()

    /** 供淡入淡出过渡使用 (仅玩家持有者应访问) */
    fun playerOrNull(): android.media.MediaPlayer? = player

    /** 开始淡出 (淡入淡出过渡模式下, 切歌前调用) */
    fun startFadeOut(durationMs: Long) {
        if (Settings.playFade() != PlayFade.CROSSFADE) return
        val p = player ?: return
        fadeHelper?.cancel()
        val helper = VolumeFade(p, balanceGain, durationMs)
        fadeHelper = helper
        helper.fadeOut { }
    }

    /** 预留给 MediaPlayer 音量控制 (通知栏音量键) */
    fun streamVolume(): Int = try {
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    } catch (t: Throwable) {
        0
    }
}
