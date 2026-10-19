package com.portable.qqmusic.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.DownloadManager
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.data.api.SearchApi
import com.portable.qqmusic.data.api.Singer
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.player.PlayerController
import com.portable.qqmusic.ui.adapter.PlaylistAdapter
import com.portable.qqmusic.ui.adapter.SingerAdapter
import com.portable.qqmusic.ui.adapter.SongAdapter
import com.portable.qqmusic.ui.util.Extras
import com.portable.qqmusic.ui.util.Tip

/**
 * 搜索页, 顶部三个分类 tab: 单曲 / 歌手 / 歌单。
 *
 * 三者是**同一个接口的三种 `search_type`**, 服务端每次只填对应那一块数据,
 * 所以切换 tab 必须各发一次请求, 不能一次拿全 (见 [SearchApi.searchSingers])。
 *
 * 每个 tab 各自记页码与「已到底」标记, 这样来回切不会丢进度, 也不会重复请求。
 */
class SearchActivity : AppCompatActivity(), PlayerController.Listener {

    private companion object {
        const val TAB_SONG = 0
        const val TAB_SINGER = 1
        const val TAB_PLAYLIST = 2
        const val PAGE_SIZE = 20
    }

    private lateinit var input: EditText
    private lateinit var clearButton: ImageButton
    private lateinit var recycler: RecyclerView
    private lateinit var stateView: TextView
    private lateinit var loading: ProgressBar
    private lateinit var tabsBar: View
    private lateinit var tabSong: TextView
    private lateinit var tabSinger: TextView
    private lateinit var tabPlaylist: TextView

    private lateinit var songAdapter: SongAdapter
    private lateinit var singerAdapter: SingerAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private var tab = TAB_SONG
    private val pages = IntArray(3) { 1 }
    private val exhausted = BooleanArray(3)
    private val loaded = BooleanArray(3)
    private var loadingMore = false
    private var keyword = ""

    /** 搜索结果页没有底部导航栏, 迷你播放条直接贴在屏幕最底部 */
    private var miniPlayer: MiniPlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.search_input)
        clearButton = findViewById(R.id.btn_clear)
        recycler = findViewById(R.id.result_recycler)
        stateView = findViewById(R.id.search_state)
        loading = findViewById(R.id.loading)
        tabsBar = findViewById(R.id.search_tabs)
        tabSong = findViewById(R.id.tab_song)
        tabSinger = findViewById(R.id.tab_singer)
        tabPlaylist = findViewById(R.id.tab_playlist)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_search).setOnClickListener { doSearch(reset = true) }

        setupInput()
        setupRecycler()
        setupTabs()
        PlayerController.addListener(this)
        miniPlayer = MiniPlayerController(findViewById(R.id.mini_player)) {
            startActivity(android.content.Intent(this, NowPlayingActivity::class.java))
        }

        val initial = intent.getStringExtra(Extras.EXTRA_QUERY).orEmpty()
        if (initial.isNotEmpty()) {
            input.setText(initial)
            input.setSelection(initial.length)
            tabsBar.visibility = View.VISIBLE
            selectTab(TAB_SONG)
        } else {
            input.requestFocus()
            showKeyboard()
        }
    }

    // ---------------- 分类 tab ----------------

    private fun setupTabs() {
        tabSong.setOnClickListener { selectTab(TAB_SONG) }
        tabSinger.setOnClickListener { selectTab(TAB_SINGER) }
        tabPlaylist.setOnClickListener { selectTab(TAB_PLAYLIST) }
    }

    private fun selectTab(which: Int) {
        tab = which
        val brand = ContextCompat.getColor(this, R.color.brand)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        tabSong.setTextColor(if (which == TAB_SONG) brand else secondary)
        tabSinger.setTextColor(if (which == TAB_SINGER) brand else secondary)
        tabPlaylist.setTextColor(if (which == TAB_PLAYLIST) brand else secondary)

        recycler.adapter = when (which) {
            TAB_SINGER -> singerAdapter
            TAB_PLAYLIST -> playlistAdapter
            else -> songAdapter
        }

        // 还没搜过就不请求; 搜过但没切到过这个 tab 才补一次
        if (keyword.isEmpty()) return
        if (!loaded[which]) {
            doSearch(reset = true)
        } else {
            refreshStateView()
        }
    }

    private fun adapterCount(): Int = when (tab) {
        TAB_SINGER -> singerAdapter.itemCount
        TAB_PLAYLIST -> playlistAdapter.itemCount
        else -> songAdapter.itemCount
    }

    private fun refreshStateView() {
        if (adapterCount() == 0 && exhausted[tab]) {
            stateView.visibility = View.VISIBLE
            stateView.text = getString(R.string.empty_list)
        } else {
            stateView.visibility = View.GONE
        }
    }

    // ---------------- 输入 ----------------

    private fun setupInput() {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(reset = true)
                true
            } else false
        }
        clearButton.setOnClickListener {
            input.setText("")
            keyword = ""
            clearAll()
            stateView.visibility = View.VISIBLE
            stateView.text = getString(R.string.search_hint)
        }
    }

    private fun clearAll() {
        for (i in 0..2) {
            pages[i] = 1
            exhausted[i] = false
            loaded[i] = false
        }
        songAdapter.submit(emptyList())
        singerAdapter.submit(emptyList())
        playlistAdapter.submit(emptyList())
        tabsBar.visibility = View.GONE
    }

    private fun setupRecycler() {
        songAdapter = SongAdapter(
            onClick = { position, _ ->
                val list = songAdapter.currentItems()
                if (list.isNotEmpty()) {
                    PlayerController.setQueue(this, list, position, autoPlay = true)
                    startActivity(Extras.nowPlayingIntent(this))
                }
            },
            onMore = { _, _, song -> showSongMenu(song) }
        )
        singerAdapter = SingerAdapter { singer ->
            startActivity(Extras.singerIntent(this, singer))
        }
        playlistAdapter = PlaylistAdapter(
            PlaylistAdapter.MODE_ROW,
            onClick = { playlist -> openPlaylist(playlist) }
        )

        val lm = LinearLayoutManager(this)
        recycler.layoutManager = lm
        recycler.adapter = songAdapter
        recycler.setHasFixedSize(true)

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadingMore || exhausted[tab]) return
                val last = lm.findLastVisibleItemPosition()
                if (last >= adapterCount() - 3) {
                    pages[tab]++
                    doSearch(reset = false)
                }
            }
        })
    }

    private fun openPlaylist(playlist: Playlist) {
        startActivity(
            Extras.playlistIntent(
                this,
                playlist.id,
                playlist.title,
                Extras.SOURCE_PLAYLIST,
                playlist.dirid,
                playlist.cover
            )
        )
    }

    // ---------------- 搜索 ----------------

    private fun doSearch(reset: Boolean) {
        val kw = input.text.toString().trim()
        if (kw.isEmpty()) {
            Tip.toast(this, getString(R.string.search_hint))
            return
        }
        if (exhausted[tab] && !reset) return
        if (loadingMore) return

        if (reset) {
            keyword = kw
            pages[tab] = 1
            exhausted[tab] = false
            loaded[tab] = true
            stateView.visibility = View.GONE
            tabsBar.visibility = View.VISIBLE
        }

        loadingMore = true
        if (reset) loading.visibility = View.VISIBLE
        hideKeyboard()

        val which = tab
        val requestPage = pages[which]
        Task.io {
            val kind = when (which) {
                TAB_SINGER -> "歌手"
                TAB_PLAYLIST -> "歌单"
                else -> "单曲"
            }
            var count = 0
            try {
                when (which) {
                    TAB_SINGER -> {
                        val list = SearchApi.searchSingers(kw, requestPage, PAGE_SIZE)
                        count = list.size
                        Task.main {
                            if (isFinishing || isDestroyed) return@main
                            if (reset) singerAdapter.submit(list) else singerAdapter.append(list)
                            finishPage(which, reset, count)
                        }
                    }
                    TAB_PLAYLIST -> {
                        val list = SearchApi.searchPlaylists(kw, requestPage, PAGE_SIZE)
                        count = list.size
                        Task.main {
                            if (isFinishing || isDestroyed) return@main
                            if (reset) playlistAdapter.submit(list) else playlistAdapter.append(list)
                            finishPage(which, reset, count)
                        }
                    }
                    else -> {
                        val list = SearchApi.search(kw, requestPage, PAGE_SIZE, SearchApi.TYPE_SONG)
                        count = list.size
                        Task.main {
                            if (isFinishing || isDestroyed) return@main
                            if (reset) songAdapter.submit(list) else songAdapter.append(list)
                            finishPage(which, reset, count)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("SearchActivity", t)
                Task.main {
                    if (isFinishing || isDestroyed) return@main
                    finishPage(which, reset, 0)
                }
            }
        }
    }

    /** 一次请求收尾: 关 loading、判是否到底、刷新空状态 */
    private fun finishPage(which: Int, reset: Boolean, count: Int) {
        loading.visibility = View.GONE
        loadingMore = false
        if (count < PAGE_SIZE) exhausted[which] = true
        if (which == tab) refreshStateView()
    }

    // ---------------- 歌曲菜单 ----------------

    private fun showSongMenu(song: Song) {
        val items = arrayOf(
            getString(R.string.action_download),
            getString(R.string.action_play_next),
            getString(R.string.action_add_to_queue)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(song.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        DownloadManager.enqueue(this, listOf(song))
                        Tip.toast(this, getString(R.string.toast_added_to_download))
                    }
                    1 -> {
                        PlayerController.appendNext(listOf(song))
                        Tip.toast(this, getString(R.string.toast_play_next_added))
                    }
                    2 -> {
                        PlayerController.appendToQueue(listOf(song))
                        Tip.toast(this, getString(R.string.toast_added_to_queue))
                    }
                }
            }
            .show()
    }

    // ---------------- 输入法 ----------------

    private fun showKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        } catch (t: Throwable) {
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (t: Throwable) {
        }
    }

    // ---------------- PlayerController.Listener ----------------

    override fun onSongChanged(song: Song?) = songAdapter.setPlaying(song?.key())
    override fun onPlayStateChanged(playing: Boolean) {}
    override fun onProgress(positionMs: Int, durationMs: Int) {}
    override fun onQueueChanged() {}
    override fun onBuffering(buffering: Boolean) {}
    override fun onError(message: String) = Tip.toast(this, message)

    override fun onDestroy() {
        miniPlayer?.release()
        miniPlayer = null
        PlayerController.removeListener(this)
        super.onDestroy()
    }
}
