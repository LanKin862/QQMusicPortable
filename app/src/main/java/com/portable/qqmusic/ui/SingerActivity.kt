package com.portable.qqmusic.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.portable.qqmusic.core.Contrast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.portable.qqmusic.R
import com.portable.qqmusic.data.DownloadManager
import com.portable.qqmusic.data.api.SingerApi
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.player.PlayerController
import com.portable.qqmusic.ui.adapter.SongAdapter
import com.portable.qqmusic.ui.util.Extras
import com.portable.qqmusic.ui.util.ImageLoader
import com.portable.qqmusic.ui.util.Menus
import com.portable.qqmusic.ui.util.Tip

/**
 * 歌手详情页。
 *
 * 布局是「上半屏封面 + 下方信息流」的折叠结构: 往上滑封面被工具栏吃掉, 分页栏
 * 最终贴在工具栏下沿。配色跟着封面走 —— 根布局、工具栏背景、封面底部的渐变
 * 三者用同一个「主色压暗」值, 所以封面和信息流看起来是一张连着的图, 不是
 * 两张拼起来的卡片。
 */
class SingerActivity : AppCompatActivity() {

    private lateinit var appBar: AppBarLayout
    private lateinit var toolbar: Toolbar
    private lateinit var toolbarTitle: TextView
    private lateinit var cover: ImageView
    private lateinit var coverFade: View
    private lateinit var coverTopFade: View
    private lateinit var root: View
    private lateinit var songList: RecyclerView
    private lateinit var albumList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var tabSongs: TextView
    private lateinit var tabAlbums: TextView
    private lateinit var countView: TextView

    private var singerMid = ""
    private var singerName = ""
    private var singerCover = ""
    private var backfilling = false

    /** 信息流 / 工具栏 / 渐变共用的背景色, 由封面主色压暗得到 */
    private var themeBg = 0

    /** 当前配色走深色文字 (浅底) 还是白色文字 (深底), 由封面底部取色决定 */
    private var darkText = false

    private var total = 0
    private var songs = mutableListOf<Song>()
    private var songAdapter: SongAdapter? = null

    private var albumTotal = 0
    private var albums = mutableListOf<SingerApi.SingerAlbum>()
    private var albumAdapter: AlbumAdapter? = null
    private var albumsLoaded = false
    private var showingAlbums = false

    private var loadingMore = false
    private var noMore = false
    private var nextBegin = 0

    private var miniPlayer: MiniPlayerController? = null

    // ---------------- 批量多选 ----------------

    private lateinit var multiButton: ImageButton
    private lateinit var batchBar: View
    private lateinit var batchPlay: TextView
    private lateinit var batchAddPlaylist: TextView
    private lateinit var batchDownload: TextView
    private lateinit var batchSelectAll: TextView

    /** 光晕与背景的相对亮度差下限 (WCAG 思路, 低于这个值光晕会被背景吃掉) */
    private val GLOW_MIN_CONTRAST = 0.34f
    private var selecting = false
    private var lastGlow = 0
    private val selectedKeys = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        singerMid = intent.getStringExtra(Extras.EXTRA_SINGER_MID) ?: ""
        singerName = intent.getStringExtra(Extras.EXTRA_TITLE) ?: ""
        singerCover = intent.getStringExtra(Extras.EXTRA_COVER) ?: ""
        if (singerMid.isEmpty()) {
            finish()
            return
        }

        // 沉浸式: 内容画到状态栏底下, 状态栏本身透明 —— 封面才能真正顶到屏幕最上面
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
        }
        setContentView(R.layout.activity_singer)

        appBar = findViewById(R.id.app_bar)
        toolbar = findViewById(R.id.singer_toolbar)
        cover = findViewById(R.id.singer_cover)
        coverFade = findViewById(R.id.cover_fade)
        coverTopFade = findViewById(R.id.cover_top_fade)
        root = findViewById(R.id.singer_root)
        songList = findViewById(R.id.song_list)
        albumList = findViewById(R.id.album_list)
        loading = findViewById(R.id.loading)
        tabSongs = findViewById(R.id.tab_songs)
        tabAlbums = findViewById(R.id.tab_albums)
        countView = findViewById(R.id.singer_count)
        multiButton = findViewById(R.id.btn_multi)
        batchBar = findViewById(R.id.batch_bar)
        batchPlay = findViewById(R.id.batch_play)
        batchAddPlaylist = findViewById(R.id.batch_add_playlist)
        batchDownload = findViewById(R.id.batch_download)
        batchSelectAll = findViewById(R.id.batch_select_all)
        multiButton.setOnClickListener { enterSelectMode() }
        batchPlay.setOnClickListener { playSelected() }
        batchAddPlaylist.setOnClickListener { addSelectedToPlaylist() }
        batchDownload.setOnClickListener { downloadSelected() }
        batchSelectAll.setOnClickListener { toggleSelectAll() }

        // 没有封面时也要是个深色底: 分页栏和标题都是白的, 浅底会直接看不见
        themeBg = ColorUtils.blendARGB(ContextCompat.getColor(this, R.color.brand), Color.BLACK, 0.82f)

        // 封面占上半屏: 固定 52%, 留一点给分页栏和列表, 避免小屏上列表只剩两行
        val screenHeight = resources.displayMetrics.heightPixels
        appBar.layoutParams = appBar.layoutParams.apply { height = (screenHeight * 0.52f).toInt() }

        setupToolbar()
        setupTabs()
        setupLists()
        // 主题色必须等工具栏和两个适配器都建好之后再灌一次:
        // 它们身上有按色板设置的文字颜色, 提前调会撞上未初始化的 toolbarTitle
        applyThemeColor(themeBg)
        bindCover()

        loadSongs(0)

        miniPlayer = MiniPlayerController(findViewById(R.id.mini_player), dark = true) {
            startActivity(Extras.nowPlayingIntent(this))
        }
        miniPlayer?.setBackground(themeBg)
    }

    override fun onResume() {
        super.onResume()
        miniPlayer?.refreshAvailability()
    }

    override fun onDestroy() {
        miniPlayer?.release()
        miniPlayer = null
        super.onDestroy()
    }

    private fun setupToolbar() {
        // 返回键用 drawable 自带的 setTint 上色: ImageViewCompat 要的是 ImageView,
        // 而 navigationIcon 是个 Drawable, 类型对不上
        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_back)?.apply {
            setTint(Color.WHITE)
        }
        toolbar.setNavigationOnClickListener { finish() }

        // 标题自己加: CollapsingToolbarLayout 的 titleEnabled 已关掉, 免得它接管
        // 标题的缩放动画 —— 这里只要「折叠程度 → 透明度」这一条线性关系
        toolbarTitle = TextView(this).apply {
            text = singerName
            setTextColor(Color.WHITE)
            textSize = 18f
            alpha = 0f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(
            toolbarTitle,
            Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        )

        // 状态栏是透明的, 工具栏要自己让出那一条高度, 否则返回键会被压在状态栏下面
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            view.layoutParams = view.layoutParams.apply { height = view.height.coerceAtLeast(top) + 0 }
            insets
        }

        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
            val range = appBar.totalScrollRange
            val ratio = if (range <= 0) 1f else (-verticalOffset).toFloat() / range
            val clamped = ratio.coerceIn(0f, 1f)
            // 工具栏底色随折叠程度淡入 —— 完全展开时它就只是一条透明带
            toolbar.setBackgroundColor(
                ColorUtils.setAlphaComponent(themeBg, (clamped * 255).toInt())
            )
            toolbarTitle.alpha = clamped
        })
    }

    private fun setupTabs() {
        tabSongs.setOnClickListener { switchTab(false) }
        tabAlbums.setOnClickListener { switchTab(true) }
        refreshTabs()
    }

    private fun switchTab(albums: Boolean) {
        if (showingAlbums == albums) return
        // 多选只属于「单曲」页: 切到专辑先退出, 图标也跟着收起来
        if (albums) exitSelectMode()
        showingAlbums = albums
        refreshTabs()
        songList.visibility = if (albums) View.GONE else View.VISIBLE
        albumList.visibility = if (albums) View.VISIBLE else View.GONE
        // 多选入口只在单曲页出现
        multiButton.visibility = if (albums) View.GONE else View.VISIBLE
        updateCount()
        if (albums && !albumsLoaded) loadAlbums()
    }

    private fun refreshTabs() {
        val active = textMain()
        val idle = ColorUtils.setAlphaComponent(textMain(), 0x8A)
        tabSongs.setTextColor(if (showingAlbums) idle else active)
        tabAlbums.setTextColor(if (showingAlbums) active else idle)
    }

    private fun setupLists() {
        songAdapter = SongAdapter(
            onClick = { position, _ -> playFrom(position) },
            onMore = { _, _, song -> showSongMenu(song) },
            onToggleSelect = { song -> toggleSelect(song) },
            titleColor = Color.WHITE,
            subtitleColor = ColorUtils.setAlphaComponent(Color.WHITE, 0x9E)
        )
        songList.layoutManager = LinearLayoutManager(this)
        songList.adapter = songAdapter
        songList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) maybeLoadMore()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) maybeLoadMore()
            }
        })

        albumAdapter = AlbumAdapter()
        albumList.layoutManager = LinearLayoutManager(this)
        albumList.adapter = albumAdapter
    }

    /** 封面上色: 取主色压暗当背景, 底部渐变从透明过渡到它 */
    private fun bindCover() {
        // 从播放页跳过来时手上只有 mid 和名字, 没有头像 —— 歌手头像没法按 mid 拼
        // (搜出来的 mid 和头像文件名里的 mid 经常不是同一个), 只能按名字反查一次补上
        if (singerCover.isEmpty()) {
            if (!backfilling && singerName.isNotEmpty()) {
                backfilling = true
                Task.io {
                    val found = SingerApi.findByName(singerName)
                    val avatar = found?.avatar.orEmpty()
                    Task.main {
                        if (isFinishing || isDestroyed) return@main
                        if (avatar.isNotEmpty()) {
                            singerCover = avatar
                            bindCover()
                        }
                    }
                }
            }
            return
        }
        ImageLoader.load(this, singerCover, cover)

        ImageLoader.loadBitmap(this, singerCover, width = 192, onReady = { bmp ->
            // 底色只从封面**底部**取: 背景要和下边缘同色, 中间那道渐变才看不出接缝。
            // 具体取样/聚类/筛选规则都在 CoverColor 里 (底部 40%、k-means 6 簇、
            // 过滤小占比与纯黑纯白、按色差与色相校验, 底部太花就退回均色)。
            val main = com.portable.qqmusic.core.CoverColor.backgroundFor(bmp)
                ?: return@loadBitmap
            applyThemeColor(main)
            toolbar.setBackgroundColor(
                ColorUtils.setAlphaComponent(themeBg, (toolbarTitle.alpha * 255).toInt())
            )
        })
    }

    private fun textMain(): Int = if (darkText) 0xDE000000.toInt() else Color.WHITE

    private fun textSub(): Int = if (darkText) 0x99000000.toInt() else ColorUtils.setAlphaComponent(Color.WHITE, 0xCC)

    private fun textDim(): Int = if (darkText) 0x61000000 else ColorUtils.setAlphaComponent(Color.WHITE, 0x99)

    private fun applyThemeColor(rawBg: Int) {
        // 文字色板固定成三档白 (100/80/60), 所以反过来要求背景够深:
        // 纯白压到 7:1 之后, 60% 白叠上去也还在 4.5 以上。
        // 浅色封面 (比如亮黄) 只压 74% 是不够的, 那样白字实际只有 4 出头。
        darkText = Contrast.preferDarkText(rawBg)
        val bg = Contrast.fitBackground(rawBg, darkText)
        themeBg = bg
        toolbar.navigationIcon?.setTint(textMain())
        toolbarTitle.setTextColor(textMain())
        countView.setTextColor(textSub())
        refreshTabs()
        songAdapter?.setColors(textMain(), textSub())
        albumAdapter?.notifyDataSetChanged()
        root.setBackgroundColor(bg)
        // 渐变遮罩: 上透明下背景色, 让封面底边「化」进信息流
        coverFade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, bg)
        )
        // 顶部反着来一次: 主题色 → 透明, 让状态栏这一条和封面接得上
        coverTopFade.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.TRANSPARENT, bg)
        )
        @Suppress("DEPRECATION")
        run { window.navigationBarColor = bg }
        // 状态栏是透明的, 图标黑白必须自己判断: 系统只给一个开关, 不会看背景
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = Contrast.luminance(bg) > 0.5
            isAppearanceLightNavigationBars = false
        }
        // 迷你条也吃这个色: 让它和列表背景连成一片, 而不是用歌曲封面自己的颜色
        miniPlayer?.setBackground(bg)
        updateOverscroll()
    }

    /** 单曲行右侧 ⋮: 下载 / 下一首播放 / 加入播放队列 / 加入歌单 */
    private fun showSongMenu(song: Song) {
        val actions = ArrayList<Pair<String, () -> Unit>>(4)
        actions.add(getString(R.string.action_download) to {
            DownloadManager.enqueue(this, listOf(song))
            Tip.toast(this, getString(R.string.toast_added_to_download))
        })
        actions.add(getString(R.string.action_play_next) to {
            PlayerController.appendNext(listOf(song))
            Tip.toast(this, getString(R.string.toast_play_next_added))
        })
        actions.add(getString(R.string.action_add_to_queue) to {
            PlayerController.appendToQueue(listOf(song))
            Tip.toast(this, getString(R.string.toast_added_to_queue))
        })
        if (song.hasRealMid) {
            actions.add(getString(R.string.action_add_to_playlist) to {
                com.portable.qqmusic.ui.util.SongActions.addToPlaylist(this, song)
            })
        }
        Menus.show(this, song.name, actions.map { it.first }) { which -> actions[which].second() }
    }

    /**
     * 边缘回弹 (overscroll) 的颜色。
     *
     * 默认吃主题的 colorAccent, 是品牌绿; 但这一页的背景是封面取的深色, 绿光很跳。
     * 目标是「同色系、但一眼能看见」: 暗背景往白提, 亮背景往黑压, 而且要保证
     * 相对亮度差 >= [GLOW_MIN_CONTRAST], 否则光晕会被背景吃掉。
     */
    private fun updateOverscroll() {
        val bg = themeBg
        if (bg == 0) return
        val glow = glowFor(bg)
        if (glow == lastGlow) return
        lastGlow = glow
        installGlow(songList, glow)
        installGlow(albumList, glow)
    }

    /**
     * 按 WCAG 的相对亮度挑一个够亮/够暗的光晕色。
     *
     * 暗背景混白、亮背景混黑, 比例从 0.45 一路加到 1.0, 取第一个亮度差达标的;
     * 加到纯白/纯黑还不达标 (极端中间灰) 就直接用纯白/纯黑。
     */
    private fun glowFor(bg: Int): Int {
        val bgLum = Contrast.luminance(bg)
        val target = if (bgLum < 0.5f) Color.WHITE else Color.BLACK
        for (f in floatArrayOf(0.45f, 0.6f, 0.75f, 0.9f, 1f)) {
            val c = ColorUtils.blendARGB(bg, target, f)
            if (kotlin.math.abs(Contrast.luminance(c) - bgLum) >= GLOW_MIN_CONTRAST) return c
        }
        return target
    }

    private fun installGlow(list: RecyclerView, color: Int) {
        list.edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
            override fun createEdgeEffect(view: RecyclerView, direction: Int): android.widget.EdgeEffect =
                android.widget.EdgeEffect(view.context).apply { this.color = color }
        }
    }

    private fun enterSelectMode() {
        if (selecting || showingAlbums) return
        selecting = true
        selectedKeys.clear()
        batchBar.visibility = View.VISIBLE
        multiButton.visibility = View.GONE
        songAdapter?.setSelection(true, emptySet())
        batchSelectAll.setText(R.string.action_select_all)
    }

    private fun exitSelectMode() {
        if (!selecting) return
        selecting = false
        selectedKeys.clear()
        batchBar.visibility = View.GONE
        multiButton.visibility = if (showingAlbums) View.GONE else View.VISIBLE
        songAdapter?.setSelection(false, emptySet())
    }

    private fun toggleSelect(song: Song) {
        if (!selecting) return
        val key = song.key()
        if (!selectedKeys.add(key)) selectedKeys.remove(key)
        songAdapter?.setChecked(selectedKeys)
        updateSelectAllLabel()
    }

    private fun toggleSelectAll() {
        if (selectedKeys.size >= songs.size) {
            selectedKeys.clear()
        } else {
            songs.forEach { selectedKeys.add(it.key()) }
        }
        songAdapter?.setChecked(selectedKeys)
        updateSelectAllLabel()
    }

    private fun updateSelectAllLabel() {
        batchSelectAll.setText(
            if (songs.isNotEmpty() && selectedKeys.size >= songs.size) R.string.action_select_none
            else R.string.action_select_all
        )
    }

    private fun selectedSongs(): List<Song> = songs.filter { selectedKeys.contains(it.key()) }

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

    private fun downloadSelected() {
        val picked = selectedSongs()
        if (picked.isEmpty()) {
            Tip.toast(this, getString(R.string.batch_nothing_selected))
            return
        }
        DownloadManager.enqueue(this, picked)
        Tip.toast(this, getString(R.string.batch_download_added, picked.size))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selecting) {
            exitSelectMode()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun updateCount() {
        countView.text = if (showingAlbums) {
            getString(R.string.singer_album_count_format, albumTotal)
        } else {
            getString(R.string.song_count_format, if (total > 0) total else songs.size)
        }
    }

    // ---------------- 数据 ----------------

    private fun loadSongs(begin: Int) {
        loading.visibility = View.VISIBLE
        Task.io {
            val page = try {
                SingerApi.songs(singerMid, begin, SingerApi.PAGE_SIZE)
            } catch (t: Throwable) {
                Diag.i("SingerActivity", "取歌手歌曲失败 begin=$begin: ${t.message}")
                SingerApi.SongPage(emptyList(), 0)
            }
            Task.main {
                if (isFinishing || isDestroyed) return@main
                loading.visibility = View.GONE
                if (begin == 0) {
                    total = page.total
                    songs = page.songs.toMutableList()
                } else {
                    val seen = songs.mapTo(HashSet()) { it.mid }
                    songs.addAll(page.songs.filter { seen.add(it.mid) })
                }
                nextBegin = begin + page.songs.size
                noMore = page.songs.isEmpty() || (total > 0 && songs.size >= total)
                songAdapter?.submit(songs)
                loadingMore = false
                updateCount()
                Diag.i("SingerActivity", "歌曲 begin=$begin -> 共 ${songs.size}/$total noMore=$noMore")
            }
        }
    }

    private fun maybeLoadMore() {
        if (showingAlbums || loadingMore || noMore || songs.isEmpty()) return
        val lm = songList.layoutManager as? LinearLayoutManager ?: return
        if (lm.findLastVisibleItemPosition() >= songList.adapter!!.itemCount - 5) {
            loadingMore = true
            loadSongs(nextBegin)
        }
    }

    private fun loadAlbums() {
        loading.visibility = View.VISIBLE
        Task.io {
            val page = try {
                SingerApi.albums(singerMid, 0, 60)
            } catch (t: Throwable) {
                Diag.i("SingerActivity", "取歌手专辑失败: ${t.message}")
                SingerApi.AlbumPage(emptyList(), 0)
            }
            Task.main {
                if (isFinishing || isDestroyed) return@main
                loading.visibility = View.GONE
                albumsLoaded = true
                albumTotal = page.total
                albums = page.albums.toMutableList()
                albumAdapter?.submit(albums)
                updateCount()
                Diag.i("SingerActivity", "专辑 -> ${albums.size}/$albumTotal")
            }
        }
    }

    private fun playFrom(position: Int) {
        if (songs.isEmpty()) return
        PlayerController.setQueue(this, songs, position, autoPlay = true)
        startActivity(Extras.nowPlayingIntent(this))
    }

    // ---------------- 专辑列表 ----------------

    private inner class AlbumAdapter : RecyclerView.Adapter<AlbumAdapter.Holder>() {

        private val items = mutableListOf<SingerApi.SingerAlbum>()

        fun submit(list: List<SingerApi.SingerAlbum>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_singer_album, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val coverView: ImageView = view.findViewById(R.id.album_cover)
            private val nameView: TextView = view.findViewById(R.id.album_name)
            private val metaView: TextView = view.findViewById(R.id.album_meta)

            fun bind(album: SingerApi.SingerAlbum) {
                nameView.setTextColor(textMain())
                nameView.text = album.name
                val meta = listOf(album.publishDate, album.type)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                metaView.setTextColor(textSub())
                metaView.text = meta
                ImageLoader.load(itemView.context, album.coverUrl(), coverView)
                itemView.setOnClickListener {
                    itemView.context.startActivity(
                        Extras.albumIntent(itemView.context, album.mid, album.name)
                    )
                }
            }
        }
    }
}

/** 专辑封面按 mid 拼, 与歌单/歌曲用的是同一套 `photo_new` 规则 */
private fun SingerApi.SingerAlbum.coverUrl(): String =
    if (mid.isEmpty()) "" else "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg"
