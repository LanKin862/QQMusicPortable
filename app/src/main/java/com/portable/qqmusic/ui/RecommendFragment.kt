package com.portable.qqmusic.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.QueueKind
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.RecommendRepository
import com.portable.qqmusic.data.api.Login
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.player.PlayerController
import com.portable.qqmusic.ui.adapter.PlaylistAdapter
import com.portable.qqmusic.ui.util.Extras
import com.portable.qqmusic.ui.util.Tip
import com.portable.qqmusic.ui.view.SongRowView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 推荐页。
 *
 * 内容: 每日推荐 (QQ 音乐雷达歌单) + 猜你喜欢 + 推荐歌单。
 * 顶部搜索框与设置入口由 [com.portable.qqmusic.ui.view] 里的 view_top_bar 提供。
 */
class RecommendFragment : Fragment(), PlayerController.Listener {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var dailyContainer: LinearLayout
    private lateinit var guessContainer: LinearLayout
    private lateinit var playlistRecycler: RecyclerView
    private lateinit var loading: View
    private lateinit var emptyView: TextView
    private lateinit var dailyDate: TextView
    private lateinit var guessRefresh: ImageButton
    private lateinit var playlistAdapter: PlaylistAdapter

    private var dailySongs: List<Song> = emptyList()
    private var guessSongs: List<Song> = emptyList()
    private var loaded = false

    /** 猜你喜欢正在单独刷新时挡住重复点击, 免得连点几下叠出一堆请求 */
    private var guessRefreshing = false

    /**
     * 「猜你喜欢」被换了一批时的回填。
     *
     * 这个列表有两个刷新入口: 页面上的刷新按钮, 以及播到最后一首时的自动续接。
     * 挂在仓库上而不是刷新按钮的回调里, 才能让自动续接也把卡片刷成新的那一批。
     */
    private val onGuessRefreshed: (List<Song>) -> Unit = { songs ->
        if (isAdded && songs.isNotEmpty()) {
            guessSongs = songs
            renderSongs(guessContainer, guessSongs, R.id.section_guess, QueueKind.GUESS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_recommend, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipe = view.findViewById(R.id.swipe_refresh)
        dailyContainer = view.findViewById(R.id.daily_container)
        guessContainer = view.findViewById(R.id.guess_container)
        playlistRecycler = view.findViewById(R.id.playlist_recycler)
        loading = view.findViewById(R.id.loading)
        emptyView = view.findViewById(R.id.empty_view)
        dailyDate = view.findViewById(R.id.daily_date)
        guessRefresh = view.findViewById(R.id.guess_refresh)

        dailyDate.text = SimpleDateFormat("MM月dd日", Locale.CHINA).format(Date())

        setupTopBar(view)
        setupPlaylistRecycler()

        swipe.setColorSchemeResources(R.color.brand)
        swipe.setOnRefreshListener { load(force = true) }

        view.findViewById<TextView>(R.id.daily_more).setOnClickListener {
            if (dailySongs.isEmpty()) return@setOnClickListener
            playAll(dailySongs, 0)
        }
        view.findViewById<TextView>(R.id.guess_more).setOnClickListener {
            if (guessSongs.isEmpty()) return@setOnClickListener
            playAll(guessSongs, 0, QueueKind.GUESS)
        }

        // 「更多为你推荐」: 把货架 + 广场推荐流合并后的完整列表铺一整页
        view.findViewById<TextView>(R.id.playlist_more).setOnClickListener {
            startActivity(Intent(requireContext(), RecommendMoreActivity::class.java))
        }
        guessRefresh.setOnClickListener { refreshGuess() }

        PlayerController.addListener(this)
        RecommendRepository.addGuessListener(onGuessRefreshed)

        if (!loaded) load(force = false)
    }

    override fun onDestroyView() {
        PlayerController.removeListener(this)
        RecommendRepository.removeGuessListener(onGuessRefreshed)
        super.onDestroyView()
    }

    private fun setupTopBar(root: View) {
        root.findViewById<View>(R.id.search_box).setOnClickListener {
            startActivity(Extras.searchIntent(requireContext()))
        }
        root.findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    private fun setupPlaylistRecycler() {
        playlistAdapter = PlaylistAdapter(PlaylistAdapter.MODE_CARD, onClick = { playlist ->
            openPlaylist(playlist)
        })
        playlistRecycler.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        playlistRecycler.adapter = playlistAdapter
        playlistRecycler.setHasFixedSize(true)
    }

    // ---------------- 数据加载 ----------------

    private fun load(force: Boolean) {
        if (loading.visibility != View.VISIBLE) loading.visibility = View.VISIBLE
        emptyView.visibility = View.GONE

        Task.io {
            // 下拉刷新 (force) 时跳过本地缓存, 否则刷不出新内容; 冷启动则优先用缓存秒开
            val result = RecommendRepository.load(useCache = !force)
            Task.main {
                if (!isAdded) return@main
                loaded = true
                loading.visibility = View.GONE
                swipe.isRefreshing = false

                dailySongs = result.daily
                guessSongs = result.guess
                renderSongs(dailyContainer, dailySongs, R.id.section_daily, QueueKind.NORMAL)
                renderSongs(guessContainer, guessSongs, R.id.section_guess, QueueKind.GUESS)
                playlistAdapter.submit(result.playlists)

                val nothing = result.isEmpty
                emptyView.visibility = if (nothing) View.VISIBLE else View.GONE
                if (result.errors.isNotEmpty() && nothing) {
                    emptyView.text = result.errors.joinToString(" / ")
                }
                Log.d("Recommend", "loaded daily=${result.daily.size} guess=${result.guess.size} playlists=${result.playlists.size}")
            }
        }
    }

    /**
     * 登录态变了 (登录成功 / 退出登录) 之后由 MainActivity 调用。
     *
     * 每日推荐和「猜你喜欢」都按 uin 出内容, 不重拉就会一直显示未登录时的旧结果。
     */
    fun reloadForAccountChange() {
        if (!isAdded) return
        if (view == null) return
        loaded = false
        load(force = true)
    }

    /**
     * 只重拉「猜你喜欢」, 每日推荐与推荐歌单原地不动。
     *
     * 这块来自私人电台, 每次调用本来就是另一批歌, 所以单独刷是有意义的;
     * 而另外两块按天滚动, 跟着一起请求只会白费流量、还会把已经看好的位置顶掉。
     */
    private fun refreshGuess() {
        if (guessRefreshing) return
        guessRefreshing = true
        guessRefresh.isEnabled = false
        // 转一圈当进度反馈: 这是唯一能表示"正在刷"的信号, 不能只靠按钮变灰
        guessRefresh.animate().rotationBy(360f).setDuration(700).start()

        Task.io {
            // 成功时由 onGuessRefreshed 回填界面 —— 自动续接走的是同一条路,
            // 两处各写一份渲染逻辑迟早会分叉
            val songs = RecommendRepository.refreshGuess()
            Task.main {
                if (!isAdded) return@main
                guessRefreshing = false
                guessRefresh.isEnabled = true
                if (songs.isEmpty()) {
                    // 失败时保留原来那批: 刷不出来还把用户正在看的内容抹掉是最糟的交互
                    Tip.toast(requireContext(), getString(R.string.guess_refresh_failed))
                }
            }
        }
    }

    /** 把歌曲填充进容器 (最多 6 行, 避免首屏过长) */
    private fun renderSongs(
        container: LinearLayout,
        songs: List<Song>,
        sectionId: Int,
        kind: QueueKind
    ) {
        container.removeAllViews()
        val section = view?.findViewById<View>(sectionId)
        if (songs.isEmpty()) {
            section?.visibility = View.GONE
            return
        }
        section?.visibility = View.VISIBLE

        val limit = minOf(songs.size, MAX_ROWS)
        for (i in 0 until limit) {
            val row = SongRowView(requireContext())
            row.bind(
                songs[i],
                i,
                { song, _ -> onSongClicked(song, i, kind) },
                { _, index -> playAll(songs, index, kind) }
            )
            container.addView(row)
        }
    }

    private fun onSongClicked(song: Song, index: Int, kind: QueueKind) {
        // 点歌名即播放整块列表 (与 QQ 音乐一致)
        val list = if (dailySongs.any { it.key() == song.key() }) dailySongs else guessSongs
        // 只有整块都是猜你喜欢时才按 GUESS 走: 命中的是每日推荐就该是普通队列
        val listKind =
            if (list === dailySongs) QueueKind.NORMAL else if (list === guessSongs) kind else QueueKind.NORMAL
        playAll(list, index, listKind)
    }

    private fun playAll(songs: List<Song>, startIndex: Int, kind: QueueKind = QueueKind.NORMAL) {
        if (songs.isEmpty()) return
        PlayerController.setQueue(requireContext(), songs, startIndex, autoPlay = true, kind = kind)
        startActivity(Extras.nowPlayingIntent(requireContext()))
    }

    private fun openPlaylist(playlist: Playlist) {
        when (playlist.type) {
            Playlist.TYPE_ALBUM -> startActivity(
                Extras.albumIntent(requireContext(), playlist.disstid, playlist.title)
            )
            Playlist.TYPE_TOP -> startActivity(
                Extras.playlistIntent(
                    requireContext(), playlist.id, playlist.title, Extras.SOURCE_TOP,
                    cover = playlist.cover
                )
            )
            else -> startActivity(
                Extras.playlistIntent(
                    requireContext(), playlist.id, playlist.title, Extras.SOURCE_PLAYLIST,
                    cover = playlist.cover
                )
            )
        }
    }

    // ---------------- PlayerController.Listener ----------------

    override fun onSongChanged(song: Song?) {
        val key = song?.key()
        forEachRow(dailyContainer, dailySongs, key)
        forEachRow(guessContainer, guessSongs, key)
    }

    private fun forEachRow(container: LinearLayout, songs: List<Song>, playingKey: String?) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? SongRowView ?: continue
            val song = songs.getOrNull(i) ?: continue
            child.setPlaying(song.key() == playingKey)
        }
    }

    override fun onPlayStateChanged(playing: Boolean) {}

    override fun onProgress(positionMs: Int, durationMs: Int) {}

    override fun onQueueChanged() {}

    override fun onBuffering(buffering: Boolean) {}

    override fun onError(message: String) {
        if (isAdded) Tip.toast(requireContext(), message)
    }

    companion object {
        private const val MAX_ROWS = 6
    }
}
