package com.portable.qqmusic.data

import android.os.Handler
import android.os.Looper
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.data.api.DiscoverApi
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.data.api.Song

/**
 * 推荐页数据仓库。
 *
 * 单次刷新并发拉取三块内容 (每日推荐 / 猜你喜欢 / 推荐歌单),
 * 任意一块失败不影响其余内容展示。
 */
object RecommendRepository {

    private const val KEY_DAILY = "recommend_daily"
    private const val KEY_GUESS = "recommend_guess"
    private const val KEY_PLAYLISTS = "recommend_playlists"

    /**
     * 推荐内容的有效期。
     *
     * 每日推荐一天才换一次, 猜你喜欢/推荐货架也基本按天滚动, 所以缓存 6 小时:
     * 冷启动和断网时能立刻出内容, 又不会让"今天"的推荐拖到明天。
     */
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 「猜你喜欢」被换了一批时的订阅者。
     *
     * 存在的理由: 这块内容有**两个**刷新入口 —— 页面上的刷新按钮, 以及播放到最后一首时的
     * 自动续接 (见 `PlayerController.scheduleGuessExtension`)。后者发生在推荐页可能压根
     * 没打开的时候, 也可能正开着; 卡片不该在一批新歌已经落盘之后还显示旧的那一批。
     */
    private val guessListeners = java.util.concurrent.CopyOnWriteArrayList<(List<Song>) -> Unit>()

    fun addGuessListener(l: (List<Song>) -> Unit) {
        guessListeners.addIfAbsent(l)
    }

    fun removeGuessListener(l: (List<Song>) -> Unit) {
        guessListeners.remove(l)
    }

    /** 回调统一投到主线程: 订阅者要动界面, 而 [refreshGuess] 是在 IO 线程被调的 */
    private fun publishGuess(songs: List<Song>) {
        if (songs.isEmpty()) return
        mainHandler.post {
            for (l in guessListeners) {
                try {
                    l(songs)
                } catch (t: Throwable) {
                    Log.w("RecommendRepository", t)
                }
            }
        }
    }

    class Result(
        val daily: List<Song> = emptyList(),
        val guess: List<Song> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val errors: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = daily.isEmpty() && guess.isEmpty() && playlists.isEmpty()
    }

    /**
     * 加载推荐页三块内容。
     *
     * [useCache] 为 true 时优先用本地持久化的结果 (冷启动/断网秒开);
     * 下拉刷新要传 false, 否则刷不出新内容。
     */
    fun load(useCache: Boolean = true): Result {
        val ctx = com.portable.qqmusic.App.instance
        if (useCache && ctx != null) {
            val cachedDaily = Song.listFromCache(MetaCache.get(ctx, KEY_DAILY, TTL_MS))
            val cachedGuess = Song.listFromCache(MetaCache.get(ctx, KEY_GUESS, TTL_MS))
            val cachedPl = Playlist.listFromCache(MetaCache.get(ctx, KEY_PLAYLISTS, TTL_MS))
            if (cachedDaily.isNotEmpty() || cachedGuess.isNotEmpty() || cachedPl.isNotEmpty()) {
                Diag.i(
                    "Recommend",
                    "命中本地缓存: 每日=${cachedDaily.size} 猜你喜欢=${cachedGuess.size} 歌单=${cachedPl.size}"
                )
                return Result(cachedDaily, cachedGuess, cachedPl)
            }
        }

        var daily: List<Song> = emptyList()
        var guess: List<Song> = emptyList()
        var playlists: List<Playlist> = emptyList()
        val errors = ArrayList<String>(2)

        Diag.i("Recommend", "开始加载, 已登录=${com.portable.qqmusic.data.api.Login.isLoggedIn()}")

        try {
            daily = DiscoverApi.dailyRecommend()
            Diag.i("Recommend", "每日推荐 ${daily.size} 首")
        } catch (t: Throwable) {
            Diag.e("Recommend", "每日推荐失败", t)
            errors.add("每日推荐加载失败")
        }

        try {
            guess = DiscoverApi.guessYouLike()
            Diag.i("Recommend", "猜你喜欢 ${guess.size} 首")
        } catch (t: Throwable) {
            Diag.e("Recommend", "猜你喜欢失败", t)
            errors.add("猜你喜欢加载失败")
        }

        try {
            playlists = DiscoverApi.recommendPlaylists(12)
            Diag.i("Recommend", "推荐歌单 ${playlists.size} 个")
        } catch (t: Throwable) {
            Diag.e("Recommend", "推荐歌单失败", t)
            errors.add("推荐歌单加载失败")
        }

        if (ctx != null) {
            if (daily.isNotEmpty()) MetaCache.put(ctx, KEY_DAILY, Song.listToJson(daily).toString())
            if (guess.isNotEmpty()) MetaCache.put(ctx, KEY_GUESS, Song.listToJson(guess).toString())
            if (playlists.isNotEmpty()) {
                MetaCache.put(ctx, KEY_PLAYLISTS, Playlist.listToJson(playlists).toString())
            }
        }

        return Result(daily, guess, playlists, errors)
    }

    /**
     * 只重拉「猜你喜欢」, 并覆盖它的缓存。
     *
     * 这块来自私人电台 (`music.radioProxy.MbTrackRadioSvr/get_radio_track`, id=99),
     * 每调一次本来就换一批, 所以能单独刷。每日推荐和推荐货架是按天滚动的,
     * 单独刷它们没有意义, 也就不必跟着付一次请求。
     *
     * 失败时返回空列表而**不抛异常**: 调用方据此提示, 但**不要**清空已经显示的内容 ——
     * 刷新失败还把用户正在看的列表抹掉是最糟的交互。
     */
    fun refreshGuess(): List<Song> {
        val guess = try {
            DiscoverApi.guessYouLike()
        } catch (t: Throwable) {
            Diag.e("Recommend", "刷新猜你喜欢失败", t)
            return emptyList()
        }
        Diag.i("Recommend", "刷新猜你喜欢 ${guess.size} 首")
        val ctx = com.portable.qqmusic.App.instance
        if (ctx != null && guess.isNotEmpty()) {
            MetaCache.put(ctx, KEY_GUESS, Song.listToJson(guess).toString())
        }
        publishGuess(guess)
        return guess
    }
}
