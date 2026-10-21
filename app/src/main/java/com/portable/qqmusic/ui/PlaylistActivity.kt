package com.portable.qqmusic.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.portable.qqmusic.BuildConfig
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Settings
import com.portable.qqmusic.core.Store
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.DownloadManager
import com.portable.qqmusic.data.LocalMeta
import com.portable.qqmusic.data.LocalMusic
import com.portable.qqmusic.data.api.Login
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.data.api.PlaylistApi
import com.portable.qqmusic.data.api.SingerApi
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.data.api.UserApi
import com.portable.qqmusic.player.PlayerController
import com.portable.qqmusic.ui.adapter.SongAdapter
import com.portable.qqmusic.ui.util.Extras
import com.portable.qqmusic.ui.util.ImageLoader
import com.portable.qqmusic.ui.util.Menus
import com.portable.qqmusic.ui.util.SongActions
import com.portable.qqmusic.ui.util.Tip
import java.text.Collator
import java.util.Locale

/**
 * 歌单 / 收藏 / 本地 详情页。
 *
 * 通过 [Extras.EXTRA_SOURCE] 区分数据来源, 三者在 UI 上完全一致。
 */
class PlaylistActivity : AppCompatActivity(), PlayerController.Listener {

    private lateinit var recycler: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var descView: TextView
    private lateinit var coverView: ImageView
    private lateinit var loading: ProgressBar
    private lateinit var adapter: SongAdapter

    // ---- 本地/下载入口的两个 tab 与下载界面底部的两个导航 ----
    private lateinit var localTabs: View
    private lateinit var tabLocal: TextView
    private lateinit var tabDownload: TextView
    private lateinit var downloadNav: View
    private lateinit var navDownloaded: TextView
    private lateinit var navDownloading: TextView
    private lateinit var downloadAllButton: TextView
    private lateinit var headerCard: View
    private lateinit var moreButton: ImageButton

    /** 本地音乐入口的顶部 tab: 0=本地 1=下载 */
    private var localTab = 0

    /** 下载界面底部的导航: 0=已下载 1=正在下载 */
    private var downloadTab = 0

    /** 正在重扫本地音乐: 防连点 */
    private var rescanning = false

    private var songs: List<Song> = emptyList()

    /**
     * 未经过搜索/排序的完整列表。
     *
     * [songs] 是 [baseSongs] 经 [filterQuery] 与 [sortMode] 投影后的结果 —— 翻页、
     * 删除、下载完成刷新都要先落到这里, 否则搜过的列表一刷新就把过滤条件丢了。
     */
    private var baseSongs: List<Song> = emptyList()

    /** 歌单内搜索的关键词, 空串表示不过滤 */
    private var filterQuery = ""

    /** 排序方式, 取值见 companion object 里的 SORT_* */
    private var sortMode = SORT_DEFAULT

    /**
     * A→Z / Z→A 用的比较器。
     *
     * 直接比 `String` 是在比 UTF-16 码位, 中文会按编码顺序排 (「一丨丶㇐」那种),
     * 与用户预期的「按拼音首字母」完全对不上。ICU 的简体中文 collation 默认就是拼音序,
     * 借它把汉字按读音摊平; 日文假名与拉丁字母也各有稳定的位置。
     * PRIMARY 强度 = 忽略大小写与声调, 大小写混排时保持稳定顺序。
     */
    private val sortCollator: Collator by lazy {
        Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
            strength = Collator.PRIMARY
        }
    }

    private var disstid: Long = 0

    /**
     * 当前这张歌单是否已在「收藏的歌单」里。
     *
     * 入口是收藏列表时必然为 true; 从推荐/搜索打开别人的歌单时, 这个值由
     * [showPlaylistMenu] 现查一次 `collectedPlaylists()` 决定。
     */
    private var collectedPlaylist = false

    /** 菜单项在查完收藏状态后才有内容, 用一个标志避免重复触发查询 */
    private var favStateChecked = false

    // ---- 下载状态 (「正在下载」页要看得到进度与结果) ----
    /** song.key() -> 行内状态 */
    private val downloadStates = HashMap<String, SongAdapter.RowState>()
    private var downloadRefreshPending = false
    private val downloadRefreshRunnable = Runnable {
        downloadRefreshPending = false
        if (isFinishing || isDestroyed) return@Runnable
        // localSongs() 会扫 MediaStore / 下载目录, 不能在主线程干
        Task.io {
            val list = runCatching { localSongs() }.getOrDefault(emptyList())
            Task.main {
                if (isFinishing || isDestroyed) return@main
                setSongs(list)
                bindHeader(headerPlaylist, list)
            }
        }
    }

    /** 等用户确认系统删除弹窗的那首歌 */
    private var pendingDeleteSong: Song? = null

    /** 分区存储下删除媒体库条目必须由用户确认, 这里接系统弹窗的回执 */
    private val deleteLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val song = pendingDeleteSong
        pendingDeleteSong = null
        if (song == null) return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            afterFileDeleted(song)
        } else {
            Tip.toast(this, getString(R.string.file_delete_failed))
        }
    }

    /** 下载队列有变化: 刷新「正在下载」页的进度与状态 */
    private val downloadListener: (DownloadManager.DownloadTask) -> Unit = { task ->
        if (source == Extras.SOURCE_LOCAL && localTab == 1) {
            refreshDownloadStates()
            if (showingDownloading()) {
                if (task.status == DownloadManager.Status.DOWNLOADING &&
                    downloadStates.size == songs.size
                ) {
                    // 纯进度变化: 只重绑当前行, 不必重扫列表
                    adapter.refresh()
                } else {
                    scheduleFullRefresh()
                }
            } else if (task.status == DownloadManager.Status.DONE) {
                // 已下载 tab: 下完一首立刻出现在列表里
                scheduleFullRefresh()
            }
        } else if (source == Extras.SOURCE_LOCAL && task.status == DownloadManager.Status.DONE) {
            // 「本地音乐」已经把下载目录合并进来了, 所以下完一首也要立刻出现
            scheduleFullRefresh()
        }
    }

    private fun scheduleFullRefresh() {
        if (downloadRefreshPending) return
        downloadRefreshPending = true
        recycler.postDelayed(downloadRefreshRunnable, 250L)
    }

    /** 首屏拿到的歌单元数据。删歌后要重画头部, 而 load() 里的 playlist 是局部变量 */
    private var headerPlaylist: Playlist? = null

    /** 列表页已经拿到的封面, 详情接口不带封面时用它兜底 (见 [Extras.EXTRA_COVER]) */
    private var coverFromIntent = ""

    /** 用户目录歌单的 dirid (我喜欢/自建), >0 时取歌曲必须走 dirid */
    private var dirid: Long = 0
    private var source = Extras.SOURCE_PLAYLIST

    /** [Extras.SOURCE_SINGER] 时才有值; 歌手用字符串 mid 定位, 进不了 disstid */
    private var singerMid = ""

    /**
     * 歌手的总曲目数。
     *
     * 歌手接口会回 `totalNum`(周杰伦是 1011), 而歌单接口不返回总数 ——
     * 有它副标题才能直接写「共 N 首」, 而不是跟着已加载的条数一点点涨。
     */
    private var singerTotal = 0

    /** 分页状态: `CgiGetDiss` 单次最多 500 首, 需要靠 song_begin 继续翻 */
    private var loadingMore = false
    private var noMore = false

    /**
     * 下一次请求的 `song_begin`, 即服务端**已消费的条目数**。
     *
     * ⚠️ 不能用 `songs.size` 代替: 服务端返回的条目里有些没有 mid, 会被
     * [com.portable.qqmusic.data.api.Song.listFrom] 丢掉, 于是有效歌曲数会
     * 小于服务端条目数, 拿它当下标必然漏歌。
     */
    private var nextBegin = 0

    /** 已经主动预取了几页 (不含首屏), 用来给 [MAX_AUTO_PREFETCH] 封顶 */
    private var autoPages = 0

    /** 歌单页没有底部导航栏, 迷你播放条直接贴在屏幕最底部 */
    private var miniPlayer: MiniPlayerController? = null

    // ---------------- 批量多选 ----------------

    /** 是否处于批量多选 (只有系统返回键能退出) */
    private var selecting = false
    private val selectedKeys = LinkedHashSet<String>()
    private lateinit var batchBar: View
    private lateinit var batchPlay: TextView
    private lateinit var batchAddPlaylist: TextView
    private lateinit var batchDownload: TextView
    private lateinit var batchDelete: TextView
    private lateinit var batchSelectAll: TextView
    private lateinit var multiIcon: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        recycler = findViewById(R.id.song_recycler)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        descView = findViewById(R.id.desc)
        coverView = findViewById(R.id.cover)
        loading = findViewById(R.id.loading)
        localTabs = findViewById(R.id.local_tabs)
        tabLocal = findViewById(R.id.tab_local)
        tabDownload = findViewById(R.id.tab_download)
        downloadNav = findViewById(R.id.download_nav)
        navDownloaded = findViewById(R.id.nav_downloaded)
        navDownloading = findViewById(R.id.nav_downloading)
        downloadAllButton = findViewById(R.id.btn_download_all)
        headerCard = findViewById(R.id.header_card)
        batchBar = findViewById(R.id.batch_bar)
        batchPlay = findViewById(R.id.batch_play)
        batchAddPlaylist = findViewById(R.id.batch_add_playlist)
        batchDownload = findViewById(R.id.batch_download)
        batchDelete = findViewById(R.id.batch_delete)
        batchSelectAll = findViewById(R.id.batch_select_all)
        multiIcon = findViewById(R.id.btn_multi)

        disstid = intent.getLongExtra(Extras.EXTRA_DISSTID, 0L)
        dirid = intent.getLongExtra(Extras.EXTRA_DIRID, 0L)
        source = intent.getIntExtra(Extras.EXTRA_SOURCE, Extras.SOURCE_PLAYLIST)
        titleView.text = intent.getStringExtra(Extras.EXTRA_TITLE) ?: ""
        coverFromIntent = intent.getStringExtra(Extras.EXTRA_COVER) ?: ""
        singerMid = intent.getStringExtra(Extras.EXTRA_SINGER_MID) ?: ""
        // 进页面就用上次给这个歌单选过的排序, 而不是一律回到「默认顺序」
        sortMode = Settings.playlistSort(sortScope())

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_play_all).setOnClickListener {
            if (songs.isEmpty()) return@setOnClickListener
            playFrom(0)
        }
        // 这个按钮现在是批量多选入口 (歌单/专辑原来放的是「下载」)
        downloadAllButton.setOnClickListener { enterSelectMode() }
        multiIcon.setOnClickListener { enterSelectMode() }
        batchPlay.setOnClickListener { playSelected() }
        batchAddPlaylist.setOnClickListener { addSelectedToPlaylist() }
        batchDownload.setOnClickListener { batchDownloadSelected() }
        batchDelete.setOnClickListener { batchDeleteSelected() }
        batchSelectAll.setOnClickListener { toggleSelectAll() }
        findViewById<ImageButton>(R.id.btn_search_song).setOnClickListener { showPlaylistSearch() }
        findViewById<ImageButton>(R.id.btn_sort).setOnClickListener { showSortPicker() }
        moreButton = findViewById(R.id.btn_more)
        moreButton.setOnClickListener { showPlaylistMenu() }
        moreButton.visibility = if (canFavorite()) View.VISIBLE else View.GONE
        collectedPlaylist = source == Extras.SOURCE_COLLECTED

        setupLocalTabs()
        refreshDlAndMultiVisibility()
        setupRecycler()
        PlayerController.addListener(this)
        DownloadManager.addListener(downloadListener)
        miniPlayer = MiniPlayerController(findViewById(R.id.mini_player)) {
            startActivity(android.content.Intent(this, NowPlayingActivity::class.java))
        }
        load()
        recycler.postDelayed(ticker, TICK_MS)
    }

    override fun onDestroy() {
        recycler.removeCallbacks(ticker)
        DownloadManager.removeListener(downloadListener)
        PlayerController.removeListener(this)
        miniPlayer?.release()
        miniPlayer = null
        super.onDestroy()
    }

    /**
     * 上一次看到「我喜欢」的版本号。
     *
     * 在播放页取消收藏, 或从别处改了收藏状态之后, 这个 Activity 往往还在返回栈里
     * (没被销毁), 不重新拉就一直是旧列表 —— 用户反馈的「取消收藏后要重进才消失」。
     */
    private var favoriteRevSeen = UserApi.favoriteRevision

    /**
     * 上次见到本地匹配结果的版本号。
     *
     * 设置里跑完「匹配本地歌曲信息」回到本地列表时，这个 Activity 通常还在返回栈里，
     * 不重读就还是「未知歌手」—— 用户会以为批量匹配没生效。
     */
    private var localMetaRevSeen = LocalMeta.revision

    override fun onResume() {
        super.onResume()
        if (isLocalList()) {
            if (LocalMeta.revision != localMetaRevSeen) {
                localMetaRevSeen = LocalMeta.revision
                load()
            }
            return
        }
        if (!isFavoriteList()) return
        if (UserApi.favoriteRevision == favoriteRevSeen) return
        favoriteRevSeen = UserApi.favoriteRevision
        load()
    }

    /** 当前页是不是本地/下载（都会读 LocalMeta 借来的歌手和封面）。 */
    private fun isLocalList(): Boolean =
        source == Extras.SOURCE_LOCAL || source == Extras.SOURCE_DOWNLOADED

    /** 当前页是不是「我喜欢」: 从我的页进 (SOURCE_FAVORITE) 或从自建歌单里点进来。 */
    private fun isFavoriteList(): Boolean =
        source == Extras.SOURCE_FAVORITE ||
            (source == Extras.SOURCE_PLAYLIST && dirid == UserApi.FAVORITE_DIRID)

    /**
     * 「正在下载」页的刷新节拍。
     *
     * 下载进度不是每个百分点都回调, 只靠 notify 的话进度条会一格一格跳;
     * 用固定节拍重绘, 顺带把过了保留期的「已完成」行刷掉。
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (showingDownloading()) {
                refreshDownloadStates()
                // 任务集合变了 (刚有任务过了保留期被清掉) 时必须重取数据源:
                // 只 refresh 的话那一行还会留在列表里, 变成一条没有状态的普通歌曲行。
                if (DownloadManager.allTasks().size != songs.size) scheduleFullRefresh()
                else adapter.refresh()
            }
            recycler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * 本地音乐入口的顶部两个选项栏 + 下载界面底部的两个导航。
     *
     * 只有 [Extras.SOURCE_LOCAL] 才显示它们: 同一个 Activity 也承载歌单/专辑/榜单,
     * 那些页面的头部是封面与简介, 没有本地/下载的分法。
     */
    private fun setupLocalTabs() {
        if (source != Extras.SOURCE_LOCAL) {
            localTabs.visibility = View.GONE
            downloadNav.visibility = View.GONE
            // 本地/下载是文件夹, 没有「收藏歌单」这回事
            moreButton.visibility = if (canFavorite()) View.VISIBLE else View.GONE
            return
        }
        localTabs.visibility = View.VISIBLE
        moreButton.visibility = View.GONE
        tabLocal.setOnClickListener { switchLocalTab(0) }
        tabDownload.setOnClickListener { switchLocalTab(1) }
        navDownloaded.setOnClickListener { switchDownloadTab(0) }
        navDownloading.setOnClickListener { switchDownloadTab(1) }
        findViewById<ImageButton>(R.id.btn_rescan).setOnClickListener { rescanLocal() }
        refreshLocalTabs()
    }

    /**
     * 手动重扫本地音乐。
     *
     * MediaStore 是系统缓存, 用户从电脑拷歌进来 / 用别的应用下载完之后, 不重启或等系统自己扫
     * 就永远看不到 —— 而系统什么时候扫完全不可控。这里给一个按钮, 主动把常见目录交给
     * MediaScanner 扫一遍再重查。
     */
    private fun rescanLocal() {
        if (rescanning) return
        rescanning = true
        Tip.toast(this, getString(R.string.rescan_running))
        LocalMusic.rescan(this) {
            rescanning = false
            if (isFinishing || isDestroyed) return@rescan
            load()
        }
    }

    private fun switchLocalTab(tab: Int) {
        if (localTab == tab) return
        localTab = tab
        // 换了数据源就丢掉上一个列表的搜索词: 本地搜出来的词拿到下载页大概率一首都不剩,
        // 只会让用户以为下载页空了。排序是每个列表各自的偏好, 按新 tab 重新取。
        filterQuery = ""
        sortMode = Settings.playlistSort(sortScope())
        refreshLocalTabs()
        load()
    }

    private fun switchDownloadTab(tab: Int) {
        if (downloadTab == tab) return
        downloadTab = tab
        filterQuery = ""
        sortMode = Settings.playlistSort(sortScope())
        refreshLocalTabs()
        load()
    }

    private fun refreshLocalTabs() {
        if (source != Extras.SOURCE_LOCAL) return
        val onLocal = localTab == 0
        val brand = ContextCompat.getColor(this, R.color.brand)
        val idle = ContextCompat.getColor(this, R.color.text_secondary)
        tabLocal.setTextColor(if (onLocal) brand else idle)
        tabDownload.setTextColor(if (onLocal) idle else brand)
        // 本地 tab 也保留这个按钮 — 它是批量多选入口, 不再代表「下载全部」
        refreshDlAndMultiVisibility()
        // 下载界面只要列表与状态: 封面/简介/播放全部 整块头部都收起来
        headerCard.visibility = if (onLocal) View.VISIBLE else View.GONE
        downloadNav.visibility = if (onLocal) View.GONE else View.VISIBLE
        navDownloaded.setTextColor(if (downloadTab == 0) brand else idle)
        navDownloading.setTextColor(if (downloadTab == 1) brand else idle)
        if (!onLocal) refreshDownloadStates()
    }

    private fun setupRecycler() {
        adapter = SongAdapter(
            onClick = { position, _ -> playFrom(position) },
            onMore = { _, _, song -> showSongMenu(song) },
            // 「正在下载」页: 副标题显示状态、右侧百分比、行底部进度条
            stateOf = { song -> if (showingDownloading()) downloadStates[song.key()] else null },
            // 「正在下载」是纯状态页, 行尾的「更多」菜单没意义 (下载中没有可执行的队列操作)
            showMoreOf = { !showingDownloading() },
            onToggleSelect = { song -> toggleSelect(song) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        // 歌单单次最多返回 500 首, 滚到底部继续翻页
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) maybeLoadMore()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) maybeLoadMore()
            }
        })
    }

    // ---------------- 批量多选 ----------------

    private fun enterSelectMode() {
        if (selecting) return
        selecting = true
        selectedKeys.clear()
        batchBar.visibility = View.VISIBLE
        downloadAllButton.visibility = View.GONE
        multiIcon.visibility = View.GONE
        adapter.setSelection(true, emptySet())
        batchSelectAll.setText(R.string.action_select_all)
        // 本地歌已经在磁盘上, 只提供删除; 歌单/专辑/歌手才有下载
        batchDownload.visibility = if (source == Extras.SOURCE_LOCAL) View.GONE else View.VISIBLE
        batchDelete.visibility = View.VISIBLE
    }

    private fun exitSelectMode() {
        if (!selecting) return
        selecting = false
        selectedKeys.clear()
        batchBar.visibility = View.GONE
        adapter.setSelection(false, emptySet())
        refreshDlAndMultiVisibility()
    }

    /**
     * 信息栏上「多选」入口的显隐。
     *
     * 歌手页按用户要求做成小图标 (和「N 首」那行并排), 其它来源用一颗胶囊按钮,
     * 位置就是原来「下载」那个。多选进行中两个入口都收起来。
     */
    private fun refreshDlAndMultiVisibility() {
        if (selecting) {
            downloadAllButton.visibility = View.GONE
            multiIcon.visibility = View.GONE
            return
        }
        val downloadTabShown = source == Extras.SOURCE_LOCAL && localTab == 1
        val singer = source == Extras.SOURCE_SINGER
        multiIcon.visibility = if (singer) View.VISIBLE else View.GONE
        if (!singer && !downloadTabShown) {
            downloadAllButton.setText(R.string.action_multi_select)
            downloadAllButton.visibility = View.VISIBLE
        } else {
            downloadAllButton.visibility = View.GONE
        }
    }

    private fun toggleSelect(song: Song) {
        if (!selecting) return
        val key = song.key()
        if (!selectedKeys.add(key)) selectedKeys.remove(key)
        adapter.setChecked(selectedKeys.toSet())
        updateSelectAllLabel()
    }

    private fun updateSelectAllLabel() {
        val allSelected = songs.isNotEmpty() && selectedKeys.size == songs.size
        batchSelectAll.setText(
            if (allSelected) R.string.action_select_none else R.string.action_select_all
        )
    }

    private fun toggleSelectAll() {
        if (!selecting) return
        if (selectedKeys.size == songs.size) {
            selectedKeys.clear()
        } else {
            selectedKeys.clear()
            songs.forEach { selectedKeys.add(it.key()) }
        }
        adapter.setChecked(selectedKeys.toSet())
        updateSelectAllLabel()
    }

    private fun selectedSongs(): List<Song> = songs.filter { selectedKeys.contains(it.key()) }

    /** 以选中歌曲为播放列表, 从第一首开始播 */
    private fun playSelected() {
        val picked = selectedSongs()
        if (picked.isEmpty()) {
            Tip.toast(this, getString(R.string.batch_nothing_selected))
            return
        }
        PlayerController.setQueue(this, picked, 0, autoPlay = true)
        exitSelectMode()
    }

    private fun addSelectedToPlaylist() {
        val picked = selectedSongs()
        if (picked.isEmpty()) {
            Tip.toast(this, getString(R.string.batch_nothing_selected))
            return
        }
        com.portable.qqmusic.ui.util.SongActions.addToPlaylist(this, picked)
    }

    private fun batchDownloadSelected() {
        val picked = selectedSongs().filter { !it.isLocal }
        if (picked.isEmpty()) {
            Tip.toast(this, getString(R.string.batch_nothing_selected))
            return
        }
        DownloadManager.enqueue(this, picked)
        Tip.toast(this, getString(R.string.batch_download_added, picked.size))
    }

    private fun batchDeleteSelected() {
        val picked = selectedSongs()
        if (picked.isEmpty()) {
            Tip.toast(this, getString(R.string.batch_nothing_selected))
            return
        }
        when {
            source == Extras.SOURCE_LOCAL || source == Extras.SOURCE_DOWNLOADED ->
                confirmBatchDeleteFiles(picked)
            isFavoriteList() -> confirmBatchUnfavorite(picked)
            source == Extras.SOURCE_PLAYLIST && dirid > 0L ->
                confirmBatchRemoveFromPlaylist(picked)
            else -> Tip.toast(this, getString(R.string.batch_delete_unsupported))
        }
    }

    private fun confirmBatchDeleteFiles(picked: List<Song>) {
        Menus.confirm(
            this,
            getString(R.string.batch_delete_title, picked.size),
            getString(R.string.delete_file_message),
            getString(R.string.action_delete),
            getString(R.string.action_cancel)
        ) { deleteFiles(picked) }
    }

    private fun deleteFiles(picked: List<Song>) {
        val ctx = applicationContext
        Task.io {
            var done = 0
            var needConfirm = 0
            var failed = 0
            for (song in picked) {
                when (LocalMusic.delete(ctx, song)) {
                    is LocalMusic.DeleteResult.Done -> {
                        done++
                        DownloadManager.forget(song)
                        PlayerController.removeByKey(song.key())
                    }
                    is LocalMusic.DeleteResult.NeedConfirm -> needConfirm++
                    LocalMusic.DeleteResult.Failed -> failed++
                }
            }
            val list = localSongs()
            Task.main {
                if (isFinishing || isDestroyed) return@main
                setSongs(list)
                bindHeader(headerPlaylist, list)
                val msg = if (needConfirm > 0) {
                    getString(R.string.batch_delete_result_confirm, done, needConfirm)
                } else if (failed > 0) {
                    getString(R.string.batch_delete_result_fail, done, failed)
                } else {
                    getString(R.string.batch_delete_result, done)
                }
                Tip.toast(this, msg)
                exitSelectMode()
            }
        }
    }

    private fun confirmBatchUnfavorite(picked: List<Song>) {
        Menus.confirm(
            this,
            getString(R.string.batch_unfavorite_title, picked.size),
            null,
            getString(R.string.action_unfavorite),
            getString(R.string.action_cancel)
        ) { unfavoriteAll(picked) }
    }

    private fun unfavoriteAll(picked: List<Song>) {
        Task.io {
            var ok = 0
            for (song in picked) {
                if (runCatching { UserApi.setFavorite(song, false).ok }.getOrDefault(false)) ok++
            }
            val list = PlaylistApi.playlistDetail(0L, PAGE_SIZE, UserApi.FAVORITE_DIRID).second
            favoriteRevSeen = UserApi.favoriteRevision
            Task.main {
                if (isFinishing || isDestroyed) return@main
                setSongs(list)
                bindHeader(headerPlaylist, list)
                Tip.toast(this, getString(R.string.batch_unfavorite_result, ok))
                exitSelectMode()
            }
        }
    }

    private fun confirmBatchRemoveFromPlaylist(picked: List<Song>) {
        Menus.confirm(
            this,
            getString(R.string.batch_remove_title, picked.size),
            null,
            getString(R.string.action_remove_from_playlist),
            getString(R.string.action_cancel)
        ) { removeFromPlaylistBatch(picked) }
    }

    private fun removeFromPlaylistBatch(picked: List<Song>) {
        Task.io {
            var ok = 0
            for (song in picked) {
                if (runCatching { UserApi.removeSongFromPlaylist(dirid, song) }.getOrDefault(false)) ok++
            }
            val removed = picked.mapTo(HashSet()) { it.key() }
            val rest = baseSongs.filterNot { removed.contains(it.key()) }
            Task.main {
                if (isFinishing || isDestroyed) return@main
                setSongs(rest)
                bindHeader(headerPlaylist, rest)
                favoriteRevSeen = UserApi.favoriteRevision
                Store.putInt(Store.K.LIBRARY_REV, Store.int(Store.K.LIBRARY_REV, 0) + 1)
                Tip.toast(this, getString(R.string.batch_remove_result, ok))
                exitSelectMode()
            }
        }
    }

    override fun onBackPressed() {
        if (selecting) {
            exitSelectMode()
            return
        }
        super.onBackPressed()
    }

    // ---------------- 数据 ----------------

    private fun load() {
        if (selecting) exitSelectMode()
        loading.visibility = View.VISIBLE
        // 重新载入 = 从第一页再来, 预取配额也要归零, 否则第二次加载后
        // autoPages 已经用满, 副标题永远停在「499 首」。
        autoPages = 0
        Task.io {
            val (playlist, list) = when (source) {
                // 「我喜欢」= 用户目录 dirid=201, 用 CgiGetDiss{disstid:0, dirid:201}
                Extras.SOURCE_FAVORITE -> PlaylistApi.playlistDetail(0L, PAGE_SIZE, UserApi.FAVORITE_DIRID)
                // 本地/下载页: 顶部 tab 决定看本地还是下载, 下载里再分已下载/正在下载
                Extras.SOURCE_LOCAL -> null to localSongs()
                // 专辑走的是字符串 mid (EXTRA_ALBUM_MID), 不是数字 disstid ——
                // 原来这里传 disstidString() 恒为 "0", 专辑页永远是 0 首
                Extras.SOURCE_ALBUM -> PlaylistApi.albumDetail(
                    intent.getStringExtra(Extras.EXTRA_ALBUM_MID).orEmpty()
                )
                Extras.SOURCE_TOP -> PlaylistApi.topDetail(disstid.toInt())
                Extras.SOURCE_DOWNLOADED -> null to DownloadManager.downloadedFiles(this).mapIndexed { i, f ->
                    LocalMusic.fileToSong(f, i)
                }
                // 收藏的别人歌单: disstid 里装的是 tid, 而 tid 只有 legacy 认
                Extras.SOURCE_COLLECTED -> PlaylistApi.legacyDetail(disstid)
                // 歌手: `musichall.song_list_server.GetSingerSongList` 按 begin/number 翻页,
                // 一次最多 30 首, 后面的交给滚动加载 (见 SingerApi)
                Extras.SOURCE_SINGER -> {
                    val page = SingerApi.songs(singerMid, 0, SingerApi.PAGE_SIZE)
                    singerTotal = page.total
                    null to page.songs
                }
                else -> PlaylistApi.playlistDetail(disstid, 0, dirid)
            }

            Task.main {
                if (isFinishing || isDestroyed) return@main
                loading.visibility = View.GONE
                // 「正在下载」页先拍一份状态, 否则首次进来所有行都还是普通样式
                if (showingDownloading()) refreshDownloadStates()
                setSongs(list)
                // 首屏固定从 0 开始取了一整页, 所以已消费条目数就是 PAGE_SIZE。
                // 小歌单这里会多探一次(下一次返回 0 条), 换来的是不会漏歌。
                val pageSize = pageSize()
                nextBegin = if (list.size >= pageSize) pageSize else list.size
                // ⚠️ 不能写 `list.size < PAGE_SIZE`: 首屏 500 条里有 1 条没有 mid
                // 会被丢掉, 有效数是 499, 那样会直接判定「没有下一页」,
                // 大歌单永远停在 499 首 (用户反馈的正是这个现象)。
                noMore = list.isEmpty()
                headerPlaylist = playlist
                bindHeader(playlist, list)
                if (list.isEmpty()) {
                    Tip.toast(this, getString(R.string.empty_list))
                }
                Log.d("PlaylistActivity", "loaded ${list.size} songs source=$source noMore=$noMore")
                // 先把后面几页主动拉回来, 让副标题尽快变成接近真实的总数;
                // 剩下的交给滚动到底时的 maybeLoadMore()
                autoPrefetchMore()
            }
        }
    }

    /**
     * 当前数据源的单页条目数。
     *
     * 歌单接口一次能吃 500 首, 歌手接口硬上限只有 30 —— 拿 500 去判断
     * 「有没有下一页」会把歌手页在第一页就判成到底。
     */
    private fun pageSize(): Int =
        if (source == Extras.SOURCE_SINGER) SingerApi.PAGE_SIZE else PAGE_SIZE

    /**
     * 副标题里的曲目数。
     *
     * 歌手页优先用接口给的总数: 一首一首加载时数字从「30 首」往上爬很难看,
     * 而且用户来这一页就是想确认「到底有多少首」。
     */
    private fun songCountText(): String {
        val count = if (source == Extras.SOURCE_SINGER && singerTotal > 0) singerTotal else songs.size
        return getString(R.string.song_count_format, count)
    }

    /**
     * 是否已接近底部, 是则继续翻页。
     *
     * ⚠️ 用户反馈「歌单歌曲上限到 499」的第二层原因: 之前只在 `onScrolled(dy > 0)`
     * 里判断一次。惯性滚动的最后一次回调里 `dy` 仍大于 0 但位置还没到底,
     * 等真正到底时手指已抬起、没有新的滚动事件, 于是永远停在第一页。
     * 这里改成「滚动中」+「滚动停止」双检查, 首屏加载完也主动补一次。
     */
    private fun maybeLoadMore() {
        if (loadingMore || noMore) return
        if (songs.isEmpty()) return
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val total = lm.itemCount
        if (total <= 0) return
        if (lm.findLastVisibleItemPosition() >= total - 5) loadMore()
    }

    /**
     * 首屏之后主动把后面的页也拉回来, 直到凑够 [MAX_AUTO_PREFETCH] 页。
     *
     * 为什么不只靠滚动: 服务端不返回 `total`, 副标题只能显示「已加载数」,
     * 于是大歌单一打开就写着「499 首」, 看起来像被截断了 —— 用户反馈的
     * 「歌单上限又变成 500 了」就是这个观感。先把前几页拉回来,
     * 标题立刻就是接近真实的总数; 剩下的仍然交给滚动到底时继续加载。
     */
    private fun autoPrefetchMore() {
        if (autoPages >= MAX_AUTO_PREFETCH) return
        autoPages++
        loadMore(auto = true)
    }

    /**
     * 继续翻页。
     *
     * ⚠️ 「歌单歌曲上限到 499」的第一层原因: `CgiGetDiss` 单次最多返回 500 首,
     * 之前只取一页就结束, 于是大歌单永远停在 499/500 首。
     *
     * 注意 `song_begin` 是**起始下标**而非「跳过多少首」: 服务端返回
     * `[song_begin, song_begin + song_num)`。所以用 `song_begin = 已加载数量`
     * 时, 返回的第一首必然和上一页的最后一首重复 (实测 begin=499 返回的
     * 第一首 mid 正是 begin=0 那批的最后一首), 必须按 mid 去重。
     */
    private fun loadMore(auto: Boolean = false) {
        if (loadingMore || noMore) return
        if (source != Extras.SOURCE_FAVORITE && source != Extras.SOURCE_PLAYLIST &&
            source != Extras.SOURCE_SINGER
        ) {
            return
        }
        if (songs.isEmpty()) return
        loadingMore = true
        val begin = nextBegin
        val pageSize = pageSize()
        Task.io {
            val (fetched, rawCount) = try {
                if (source == Extras.SOURCE_SINGER) {
                    val page = SingerApi.songs(singerMid, begin, SingerApi.PAGE_SIZE)
                    page.songs to page.songs.size
                } else {
                    PlaylistApi.playlistSongsPage(disstid, dirid, begin, PAGE_SIZE)
                }
            } catch (t: Throwable) {
                Log.w("PlaylistActivity.loadMore", t)
                emptyList<Song>() to 0
            }
            Task.main {
                loadingMore = false
                if (isFinishing || isDestroyed) return@main
                // 服务端返回 [begin, begin+raw) 这段下标, 下次从这里接着取
                nextBegin = begin + rawCount
                // 去掉与已加载部分重叠的项 (保险: 边界上可能重复一首)
                val seen = songs.mapTo(HashSet()) { it.mid }
                val more = fetched.filter { seen.add(it.mid) }
                if (more.isEmpty()) {
                    // 空页或整页都是重复项(服务端下标越界时会重复返回尾部),
                    // 一律视为没有新内容, 同时避免 maybeLoadMore 反复触发的死循环
                    noMore = true
                    return@main
                }
                // 无搜索/排序时走增量追加, 避免整表重建把滚动位置顶回去;
                // 过滤或排序生效时必须整体重算, 增量 append 会破坏顺序。
                if (filterQuery.isEmpty() && sortMode == SORT_DEFAULT) {
                    baseSongs = baseSongs + more
                    songs = baseSongs
                    adapter.append(more)
                } else {
                    setSongs(baseSongs + more)
                }
                // noMore 必须看**原始条目数**: 见字段注释
                noMore = rawCount < pageSize
                subtitleView.text = songCountText()
                Log.d("PlaylistActivity", "loadMore +${more.size} 共 ${songs.size} 首 noMore=$noMore")
                if (auto) {
                    autoPrefetchMore()
                } else {
                    // 可能一页也没填满屏幕 (例如横向/大屏), 继续补
                    recycler.post { maybeLoadMore() }
                }
            }
        }
    }

    private fun disstidString(): String = intent.getStringExtra(Extras.EXTRA_ALBUM_MID) ?: ""

    /**
     * 本地/下载页当前 tab 对应的歌曲。
     *
     * `正在下载` 用 [DownloadManager.allTasks] 而不是 [DownloadManager.pendingTasks]:
     * 后者只有还没被 pump 取走的排队项 —— 正在下的那一首、下完的、失败的都不在里面,
     * 页面于是永远是空的。现在改成看整份任务表, 每首都有进度与状态。
     */
    private fun localSongs(): List<Song> = when {
        localTab == 0 -> LocalMusic.query(this)
        downloadTab == 0 -> DownloadManager.downloadedFiles(this).mapIndexed { i, f ->
            LocalMusic.fileToSong(f, i)
        }
        else -> DownloadManager.allTasks().map { it.song }
    }

    /** 当前是不是停在「下载 → 正在下载」 */
    private fun showingDownloading(): Boolean =
        source == Extras.SOURCE_LOCAL && localTab == 1 && downloadTab == 1

    /** 把 DownloadManager 的任务状态拍成行内状态 (副标题 + 进度条) */
    private fun refreshDownloadStates() {
        downloadStates.clear()
        for (t in DownloadManager.allTasks()) {
            downloadStates[t.song.key()] = when (t.status) {
                DownloadManager.Status.QUEUED -> SongAdapter.RowState("排队中", -1)
                DownloadManager.Status.DOWNLOADING ->
                    SongAdapter.RowState("下载中", t.progress.coerceIn(0, 100))
                DownloadManager.Status.DONE -> SongAdapter.RowState("已完成", 100)
                DownloadManager.Status.FAILED ->
                    SongAdapter.RowState("失败: " + t.error.ifEmpty { "未知原因" }, -1)
                DownloadManager.Status.CANCELLED -> SongAdapter.RowState("已取消", -1)
            }
        }
    }

    private fun bindHeader(playlist: Playlist?, list: List<Song>) {
        if (playlist != null) {
            if (titleView.text.isNullOrEmpty()) titleView.text = playlist.title
            // 接口没给封面就用列表页带过来的 —— CgiGetDiss 的 data 里没有封面字段,
            // 自建歌单因此一直显示成通用音符图标, 而列表页明明是有的。
            val cover = playlist.coverUrl().ifEmpty { Playlist.coverUrlOf(coverFromIntent) }
            // 专辑页的 Playlist 没有 creator, 不特判就会显示成应用名「轻音」
            val isFavList = source == Extras.SOURCE_FAVORITE || dirid == UserApi.FAVORITE_DIRID
            // 「我喜欢」是虚拟歌单, 没有封面: 统一用米白底红心, 与「我的」列表里那张一致
            if (isFavList) {
                coverView.setImageResource(R.drawable.bg_favorite_cover)
            } else {
                ImageLoader.load(this, cover, coverView)
            }
            val creator = when {
                source == Extras.SOURCE_ALBUM -> getString(R.string.album_label)
                playlist.creator.isNotEmpty() -> playlist.creator
                // 「我喜欢」的详情接口不给 creator, 硬补一个应用名很奇怪, 只显示曲目数
                isFavList -> ""
                else -> getString(R.string.app_name)
            }
            subtitleView.text = if (creator.isEmpty()) {
                getString(R.string.song_count_format, list.size)
            } else {
                "$creator · " + getString(R.string.song_count_format, list.size)
            }
            descView.text = playlist.desc
            descView.visibility = if (playlist.desc.isEmpty()) View.GONE else View.VISIBLE
        } else {
            subtitleView.text = songCountText()
            descView.visibility = View.GONE
            when (source) {
                Extras.SOURCE_FAVORITE -> coverView.setImageResource(R.drawable.bg_favorite_cover)
                // 歌手页: 头像从搜索结果带过来 —— 歌手歌曲接口本身不回头像
                Extras.SOURCE_SINGER -> ImageLoader.load(this, coverFromIntent, coverView)
                // 专辑页同理: albumDetail 回的 Playlist 不带封面, 用 intent 里拼好的那张
                Extras.SOURCE_ALBUM -> ImageLoader.load(this, coverFromIntent, coverView)
                else -> coverView.setImageResource(R.drawable.ic_music_placeholder)
            }
        }
    }

    // ---------------- 搜索 / 排序 ----------------

    /**
     * 列表数据的统一入口: 先记下完整列表, 再按当前搜索词与排序方式投影出 [songs]。
     *
     * 翻页、删除、下载完成刷新全都走这里, 免得某条路径直接把未过滤的列表塞回去,
     * 把用户刚输入的搜索词悄悄冲掉。
     */
    private fun setSongs(list: List<Song>) {
        baseSongs = list
        applyView()
    }

    private fun applyView() {
        val q = filterQuery.trim()
        var view = if (q.isEmpty()) {
            baseSongs
        } else {
            baseSongs.filter { s ->
                s.name.contains(q, ignoreCase = true) || s.singer.contains(q, ignoreCase = true)
            }
        }
        view = when (sortMode) {
            SORT_REVERSE -> view.reversed()
            SORT_AZ -> view.sortedWith(compareBy(sortCollator) { it.name })
            SORT_ZA -> view.sortedWith(compareByDescending(sortCollator) { it.name })
            else -> view
        }
        songs = view
        adapter.submit(view)
    }

    /** 歌单内搜索: 边输入边过滤; 「确定」保留结果, 「取消」恢复原列表 */
    private fun showPlaylistSearch() {
        Menus.showInput(
            context = this,
            title = getString(R.string.search_in_playlist),
            hint = getString(R.string.search_in_playlist_hint),
            initial = filterQuery,
            onTextChanged = { q -> updateFilter(q) },
            onClear = { updateFilter("") },
            onCancel = { updateFilter("") },
            onConfirm = { _ -> }
        )
    }

    private fun updateFilter(q: String) {
        filterQuery = q
        applyView()
        if (q.isNotBlank() && songs.isEmpty()) {
            Tip.toast(this, getString(R.string.search_no_match))
        }
    }

    /**
     * 排序偏好的存储身份。
     *
     * 每个歌单各自记一份: 本地/下载页按「本地 tab + 已下载/正在下载」再分四份
     * (本来就不是同一批歌), 其它来源用 disstid / singerMid 区分。
     */
    private fun sortScope(): String = when (source) {
        Extras.SOURCE_LOCAL -> "local_${localTab}_$downloadTab"
        Extras.SOURCE_FAVORITE -> "favorite"
        Extras.SOURCE_ALBUM -> "album_${disstidString()}"
        Extras.SOURCE_TOP -> "top_$disstid"
        Extras.SOURCE_COLLECTED -> "collected_$disstid"
        Extras.SOURCE_SINGER -> "singer_$singerMid"
        else -> "playlist_$disstid"
    }

    /** 排序方式: 默认 / 倒序 / A→Z / Z→A, 当前项画勾 */
    private fun showSortPicker() {
        val labels = listOf(
            getString(R.string.sort_default),
            getString(R.string.sort_reverse),
            getString(R.string.sort_az),
            getString(R.string.sort_za)
        )
        Menus.show(this, getString(R.string.action_sort), labels, checkedIndex = sortMode) { which ->
            sortMode = which
            Settings.setPlaylistSort(sortScope(), which)
            applyView()
        }
    }

    // ---------------- 交互 ----------------

    private fun playFrom(position: Int) {
        if (songs.isEmpty()) return
        PlayerController.setQueue(this, songs, position, autoPlay = true)
        startActivity(Extras.nowPlayingIntent(this))
    }

    private fun downloadAll() {
        if (songs.isEmpty()) return
        DownloadManager.enqueue(this, songs)
        Tip.toast(this, "已加入下载队列 (${songs.size} 首)")
    }

    /**
     * 这张歌单能不能收藏 / 取消收藏。
     *
     * 只有「别人的在线歌单」才有这个动作 —— 本地/下载是文件夹, 「我喜欢」是收藏本身,
     * 自建歌单带自己的 dirid (收藏别人的歌单只有 tid, dirid 为 0)。
     */
    private fun canFavorite(): Boolean =
        source != Extras.SOURCE_LOCAL && source != Extras.SOURCE_DOWNLOADED &&
            source != Extras.SOURCE_FAVORITE && dirid <= 0L && disstid > 0L

    private fun showPlaylistMenu() {
        if (!canFavorite()) return
        if (!Login.isLoggedIn()) {
            Tip.toast(this, getString(R.string.need_login))
            return
        }
        // 收藏状态得问一次服务端: 从推荐/搜索点进来的歌单, 光看 source 判断不出来。
        // 已经查过 (或本来就是从收藏列表进来的) 就直接弹菜单, 不用每次都等网络。
        if (collectedPlaylist || favStateChecked) {
            popPlaylistMenu()
            return
        }
        val tid = disstid
        Task.io {
            val collected = runCatching {
                UserApi.collectedPlaylists(useCache = true).any { it.tid == tid }
            }.getOrDefault(false)
            Task.main {
                if (isFinishing || isDestroyed) return@main
                collectedPlaylist = collected
                favStateChecked = true
                popPlaylistMenu()
            }
        }
    }

    private fun popPlaylistMenu() {
        val actions = ArrayList<Pair<String, () -> Unit>>(2)
        if (collectedPlaylist) {
            actions.add(getString(R.string.unfavorite_playlist) to { confirmPlaylistFav(false) })
        } else {
            actions.add(getString(R.string.favorite_playlist) to { confirmPlaylistFav(true) })
        }
        Menus.show(this, titleView.text.toString(), actions.map { it.first }) { which ->
            actions[which].second()
        }
    }

    private fun confirmPlaylistFav(favorite: Boolean) {
        val name = titleView.text?.toString().orEmpty()
        Menus.confirm(
            this,
            name,
            if (favorite) getString(R.string.favorite_playlist_message)
            else getString(R.string.unfavorite_playlist_message, name),
            getString(if (favorite) R.string.favorite_playlist else R.string.unfavorite_playlist),
            getString(R.string.action_cancel)
        ) { setPlaylistFav(favorite) }
    }

    private fun setPlaylistFav(favorite: Boolean) {
        val tid = disstid
        Task.io {
            val ok = UserApi.setPlaylistFavorite(tid, favorite)
            Task.main {
                if (isFinishing || isDestroyed) return@main
                if (!ok) {
                    Tip.toast(this, getString(R.string.playlist_fav_failed))
                    return@main
                }
                collectedPlaylist = favorite
                // 「我的」页的收藏歌单列表要跟着变; MineFragment 监听这个自增标记后重拉
                Store.putInt(Store.K.LIBRARY_REV, Store.int(Store.K.LIBRARY_REV, 0) + 1)
                Tip.toast(
                    this,
                    getString(
                        if (favorite) R.string.playlist_favorited else R.string.playlist_unfavorited
                    )
                )
                // 取消收藏后这张歌单已经不在「我的」里, 退回上一页比留在原地更顺
                if (!favorite) finish()
            }
        }
    }

    private fun showSongMenu(song: Song) {
        // 本地文件没什么可再下的, 已下载的歌更没必要: 这两个入口不出现「下载」。
        // 反过来, 只有这两个入口才谈得上「删除文件」—— 歌单/专辑/榜单是远端资源,
        // 本机只是缓存, 给它们「删除」既没意义也容易误解成「从歌单里移除」。
        val isFileEntry = source == Extras.SOURCE_LOCAL || source == Extras.SOURCE_DOWNLOADED
        // 「我喜欢」要显示当前是加还是删, 得知道状态。缓存命中就直接弹, 没命中先补一次。
        if (!song.hasRealMid) {
            popSongMenu(song, isFileEntry, favorite = false)
            return
        }
        val cached = UserApi.favoriteMidsCached()
        if (cached != null) {
            popSongMenu(song, isFileEntry, cached.contains(song.mid))
            return
        }
        Task.io {
            // 缓存里有就本地判, 没有就单查这一首 —— 不再为了弹菜单去翻三页。
            val fav = runCatching { UserApi.favoriteState(song.mid) }.getOrDefault(false)
            Task.main {
                if (isFinishing || isDestroyed) return@main
                popSongMenu(song, isFileEntry, fav)
            }
        }
    }

    private fun popSongMenu(song: Song, isFileEntry: Boolean, favorite: Boolean) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (song.hasRealMid) {
            actions.add(
                getString(if (favorite) R.string.action_unfavorite else R.string.action_favorite) to {
                    // 成功与否的 toast 都由 SongActions 负责, 失败时会说明原因。
                    SongActions.setFavorite(this, song, !favorite) { }
                }
            )
        }
        if (!isFileEntry) {
            actions.add(getString(R.string.action_download) to {
                DownloadManager.enqueue(this, listOf(song))
                Tip.toast(this, "已加入下载队列")
            })
        }
        actions.add("下一首播放" to {
            PlayerController.appendNext(listOf(song))
            Tip.toast(this, "已插入到下一首")
        })
        actions.add("加入播放队列" to {
            PlayerController.appendToQueue(listOf(song))
            Tip.toast(this, "已加入播放队列")
        })
        if (song.hasRealMid) {
            actions.add(getString(R.string.action_add_to_playlist) to {
                SongActions.addToPlaylist(this, song)
            })
        }
        if (isFileEntry) {
            actions.add(getString(R.string.action_delete) to { confirmDeleteFile(song) })
        }
        // 自建歌单里的歌可以「从歌单移除」。判据是 source + dirid: 推荐/收藏的歌单
        // dirid 都是 0, 只有「我的」里打开的自建歌单才带自己的 dirid。
        if (source == Extras.SOURCE_PLAYLIST && dirid > 0L) {
            actions.add(getString(R.string.action_remove_from_playlist) to {
                confirmRemoveFromPlaylist(song)
            })
        }
        Menus.show(this, song.name, actions.map { it.first }) { which -> actions[which].second() }
    }

    /** 把这首歌从当前自建歌单里移除。只解除关系, 不动文件。 */
    private fun confirmRemoveFromPlaylist(song: Song) {
        Menus.confirm(
            this,
            song.name,
            getString(R.string.remove_from_playlist_message, song.name),
            getString(R.string.action_remove_from_playlist),
            getString(R.string.action_cancel)
        ) { removeFromPlaylist(song) }
    }

    private fun removeFromPlaylist(song: Song) {
        Task.io {
            val ok = UserApi.removeSongFromPlaylist(dirid, song)
            Task.main {
                if (isFinishing || isDestroyed) return@main
                if (!ok) {
                    Tip.toast(this, getString(R.string.playlist_remove_failed))
                    return@main
                }
                val rest = baseSongs.filterNot { it.key() == song.key() }
                setSongs(rest)
                bindHeader(headerPlaylist, rest)
                // 自己刚改的收藏状态, 记下版本号, 免得回到前台再白拉一遍。
                favoriteRevSeen = UserApi.favoriteRevision
                // 让「我的」页的歌单条数/歌曲数也跟着变; 下次进来不会再看到这首。
                Store.putInt(Store.K.LIBRARY_REV, Store.int(Store.K.LIBRARY_REV, 0) + 1)
                Tip.toast(this, getString(R.string.playlist_song_removed))
            }
        }
    }

    /** 删除本地/已下载歌曲的文件。不可逆, 所以先确认再动手。 */
    private fun confirmDeleteFile(song: Song) {
        Menus.confirm(
            this,
            getString(R.string.delete_file_title, song.name),
            getString(R.string.delete_file_message),
            getString(R.string.action_delete),
            getString(R.string.action_cancel)
        ) { deleteFile(song) }
    }

    private fun deleteFile(song: Song) {
        // 真正的删除交给 LocalMusic: 媒体库条目要走 MediaStore (API 30+ 必须让系统弹确认),
        // 应用专属目录里的下载文件才可以直接 File.delete。
        Task.io {
            val result = LocalMusic.delete(this, song)
            Task.main {
                if (isFinishing || isDestroyed) return@main
                when (result) {
                    is LocalMusic.DeleteResult.Done -> afterFileDeleted(song)
                    is LocalMusic.DeleteResult.NeedConfirm -> {
                        pendingDeleteSong = song
                        try {
                            deleteLauncher.launch(
                                androidx.activity.result.IntentSenderRequest
                                    .Builder(result.sender)
                                    .build()
                            )
                        } catch (t: Throwable) {
                            Diag.e("PlaylistActivity", "发起系统删除确认失败", t)
                            pendingDeleteSong = null
                            Tip.toast(this, getString(R.string.file_delete_failed))
                        }
                    }
                    LocalMusic.DeleteResult.Failed ->
                        Tip.toast(this, getString(R.string.file_delete_failed))
                }
            }
        }
    }

    /**
     * 文件已经删掉 (或用户在系统弹窗里确认删掉了) 之后的收尾。
     *
     * 除了同步 DownloadManager 的记录, 还重新扫一遍真实数据源: 内存里剔除一行只是
     * 即时反馈, 之前正是「只从列表移除、文件其实还在」才导致返回再进来又冒出来。
     */
    private fun afterFileDeleted(song: Song) {
        DownloadManager.forget(song)
        // 播放队列里也得把这首拿走: 否则删掉的正好是正在放的那首时, 播放器还在解
        // 一个已经不存在的文件, 下一次切歌/续播直接报错, 队列里也还留着这一项。
        PlayerController.removeByKey(song.key())
        val path = song.localPath
        val ctx = applicationContext
        Task.io {
            // 媒体库条目被系统删掉后, 文件本身可能还在 (应用专属目录是直接删文件)
            if (path.isNotEmpty()) {
                try {
                    val f = java.io.File(path)
                    if (f.exists()) f.delete()
                } catch (t: Throwable) {
                    Log.w("PlaylistActivity", t)
                }
            }
            // 公共目录模式下同一个文件会写成两份 (MediaStore 一份 + 下载目录镜像),
            // 只删一份的话下次扫描又把镜像捡回来 —— 表现就是「删了又出现」。
            runCatching { DownloadManager.purgeCopies(song, ctx) }
            val list = localSongs()
            Task.main {
                if (isFinishing || isDestroyed) return@main
                setSongs(list)
                bindHeader(headerPlaylist, list)
                Tip.toast(this, getString(R.string.file_deleted))
            }
        }
    }

    // ---------------- PlayerController.Listener ----------------

    override fun onSongChanged(song: Song?) {
        adapter.setPlaying(song?.key())
    }

    override fun onPlayStateChanged(playing: Boolean) {}
    override fun onProgress(positionMs: Int, durationMs: Int) {}
    override fun onQueueChanged() {}
    override fun onBuffering(buffering: Boolean) {}
    override fun onError(message: String) {
        Tip.toast(this, message)
    }

    private companion object {
        /** 单页歌曲数。服务端 `song_num` 上限就是 500, 再大也只回 500 首 */
        const val PAGE_SIZE = 500

        /**
         * 首屏之后自动补齐的页数上限。
         *
         * 3 页 + 首屏 = 2000 首, 覆盖了绝大多数歌单; 再大的歌单靠滚动继续加载,
         * 免得一进页面就打出十几个请求、白耗流量。
         */
        const val MAX_AUTO_PREFETCH = 3

        /** 「正在下载」页的重绘节拍 (毫秒) */
        const val TICK_MS = 1_500L

        /** 排序: 服务端/系统返回的原始顺序 */
        const val SORT_DEFAULT = 0

        /** 排序: 原顺序倒过来 */
        const val SORT_REVERSE = 1

        /** 排序: 按歌名 A→Z (中文按 Unicode 码位, 效果等同按拼音首字母粗略排列) */
        const val SORT_AZ = 2

        /** 排序: 按歌名 Z→A */
        const val SORT_ZA = 3
    }
}
