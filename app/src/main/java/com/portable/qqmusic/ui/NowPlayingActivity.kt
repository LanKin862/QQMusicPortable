package com.portable.qqmusic.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.portable.qqmusic.R
import com.portable.qqmusic.core.AudioOutput
import com.portable.qqmusic.core.Contrast
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.PlayMode
import com.portable.qqmusic.core.Quality
import com.portable.qqmusic.core.Settings
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.DownloadManager
import com.portable.qqmusic.data.LocalMeta
import com.portable.qqmusic.data.LyricPrefetcher
import com.portable.qqmusic.data.api.Lyrics
import com.portable.qqmusic.data.api.PlayUrlApi
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.data.api.SongApi
import com.portable.qqmusic.data.api.UserApi
import com.portable.qqmusic.player.PlayerController
import com.portable.qqmusic.ui.util.Extras
import com.portable.qqmusic.ui.util.ImageLoader
import com.portable.qqmusic.ui.util.Menus
import com.portable.qqmusic.ui.util.SongActions
import com.portable.qqmusic.ui.util.Tip
import com.portable.qqmusic.ui.view.LyricsView

/**
 * 播放详情页。
 *
 * 布局:
 *  - 顶部栏左上角: 收纳 (收起) 图标
 *  - 中部 ViewPager2: 第 1 页 = 封面 + 歌名 + 歌手; 向右翻页 = 歌词详情
 *  - 底部: 进度条 + 功能按键 (循环方式 / 上一首 / 播放暂停 / 下一首 / 播放列表)
 *
 * 歌词页再分为三段: 上 1/6 歌名与歌手、中 4/6 滚动歌词、下 1/6 歌词显示设置与播放按钮。
 * 进度与歌词都只在页面可见时以 500ms 轮询, 离开页面即停止。
 */
class NowPlayingActivity : AppCompatActivity(), PlayerController.Listener, UserApi.FavoriteListener {

    private lateinit var pager: ViewPager2
    private lateinit var dotCover: View
    private lateinit var dotLyric: View
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var playButton: ImageButton
    private lateinit var modeButton: ImageButton

    /** 根容器, 配色跟随封面时改它的背景。 */
    private lateinit var playerRoot: View

    /** 顶部栏右上角「加入歌单」, 与左上角收起图标左右对称。 */
    private lateinit var addPlaylistButton: ImageButton

    private var coverPage: CoverPage? = null
    private var lyricPage: LyricPage? = null

    /** 当前主题色 (主色 / 副色); null 表示还没取到封面配色, 用默认主题 */
    private var themeMain: Int? = null
    private var themeSub: Int? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scrubbing = false
    private var durationMs = 0L
    private var currentSong: Song? = null

    /** 歌词缓存: 按 songmid 缓存, 避免来回翻页重复请求 */
    private val lyricCache = HashMap<String, Lyrics.Document>()
    private var currentDoc: Lyrics.Document? = null

    /** 已经算过响度包络的歌曲 mid, 防止重复解码整首歌 */
    private var envelopeFor: String = ""
    private var lyricLoadingFor: String? = null

    /**
     * 当前这首在不在「我喜欢」里。key 是 songmid。
     *
     * 进播放页要显示爱心状态 (实心/空心), 但每首都问一次服务端太慢 ——
     * 先查 [com.portable.qqmusic.data.api.UserApi.favoriteMids] 的进程内缓存,
     * 查到就记在这里; 用户点了爱心之后也同步改, 免得切歌回来又是旧状态。
     */
    private val favoriteStates = HashMap<String, Boolean>()

    /** 歌词显示设置 */
    private var lyricTextScale = 1.0f
    private var lyricTranslation = true

    private val tick = object : Runnable {
        override fun run() {
            PlayerController.pollProgress()
            syncLyric(positionMs())
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        // 从底部升起, 与收起时的向下滑出对称
        overridePendingTransition(R.anim.slide_up_in, R.anim.stay)

        pager = findViewById(R.id.pager)
        dotCover = findViewById(R.id.dot_cover)
        dotLyric = findViewById(R.id.dot_lyric)
        playerRoot = findViewById(R.id.player_root)
        seekBar = findViewById(R.id.seek_bar)
        positionText = findViewById(R.id.position)
        durationText = findViewById(R.id.duration)
        playButton = findViewById(R.id.btn_play)
        modeButton = findViewById(R.id.btn_mode)
        addPlaylistButton = findViewById(R.id.btn_add_playlist)
        addPlaylistButton.setOnClickListener {
            val s = currentSong ?: return@setOnClickListener
            if (!s.hasRealMid) return@setOnClickListener
            SongActions.addToPlaylist(this, s)
        }

        setupTopBar()
        setupPager()
        setupSeekBar()
        setupControls()

        PlayerController.addListener(this)
        UserApi.addFavoriteListener(this)
        refreshMode()
        bind(PlayerController.current())
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        PlayerController.removeListener(this)
        UserApi.removeFavoriteListener(this)
        coverPage = null
        lyricPage = null
        super.onDestroy()
    }

    /**
     * 收藏状态在别处变了 (比如在「我喜欢」列表里点了「从歌单中移除」)—— 爱心要跟着变。
     *
     * 回调可能在 IO 线程, 统一切回主线程再动视图。
     */
    override fun onFavoriteChanged(mid: String, favorite: Boolean) {
        Task.main {
            if (isFinishing || isDestroyed) return@main
            favoriteStates[mid] = favorite
            if (currentSong?.mid == mid) coverPage?.refreshFavorite(mid, favorite)
        }
    }

    // ---------------- 顶部与底部 ----------------

    private fun setupTopBar() {
        findViewById<ImageButton>(R.id.btn_collapse).setOnClickListener { collapse() }
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { PlayerController.previous() }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { PlayerController.next(this) }
        playButton.setOnClickListener { PlayerController.toggle() }
        modeButton.setOnClickListener {
            PlayerController.cyclePlayMode()
            refreshMode()
            Tip.toast(this, PlayerController.playMode().label)
        }
        findViewById<ImageButton>(R.id.btn_queue).setOnClickListener { showQueue() }
    }

    private fun setupSeekBar() {
        seekBar.progressDrawable =
            frostedProgress(liftForTrack(ContextCompat.getColor(this, R.color.brand)))
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val pos = (durationMs * progress / 100).toInt()
                positionText.text = PlayUrlApi.durationText(pos / 1000)
                // 拖动过程中实时预览对应歌词
                lyricPage?.lyrics?.syncToImmediately(pos)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubbing = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
                PlayerController.seekTo((durationMs * bar.progress / 100).toInt())
            }
        })
    }

    // ---------------- 翻页 ----------------

    private fun setupPager() {
        pager.adapter = PlayerPagerAdapter()
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                if (position == PAGE_LYRIC) {
                    ensureLyrics()
                    // 已存在的歌词页不会重新走 onBindViewHolder, 这里补一次定位,
                    // 否则翻回歌词页要等下一个 tick 才对齐当前行。
                    syncLyric(positionMs(), smooth = false)
                }
            }
        })
        updateIndicator(0)
    }

    private fun updateIndicator(position: Int) {
        dotCover.setBackgroundResource(
            if (position == PAGE_COVER) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
        )
        dotLyric.setBackgroundResource(
            if (position == PAGE_LYRIC) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
        )
        // 选中的那个点跟着封面主色走, 未选中的仍是半透明白。
        // setBackgroundResource 会把之前的 tint 清掉, 所以必须放在它后面重新设。
        val active = themeMain ?: ContextCompat.getColor(this, R.color.brand)
        val inactive = ContextCompat.getColor(this, R.color.text_on_player_secondary)
        dotCover.backgroundTintList =
            ColorStateList.valueOf(if (position == PAGE_COVER) active else inactive)
        dotLyric.backgroundTintList =
            ColorStateList.valueOf(if (position == PAGE_LYRIC) active else inactive)
    }

    private inner class PlayerPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == PAGE_COVER) {
                CoverPage(inflater.inflate(R.layout.page_player_cover, parent, false))
            } else {
                LyricPage(inflater.inflate(R.layout.page_player_lyric, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is CoverPage) {
                coverPage = holder
                holder.bind(currentSong)
                // 第 1 页只展示封面/歌名/歌手, 播放控制统一由底部功能按键承担
                holder.qualityBadge.setOnClickListener { showQualityPicker() }
                applySongPalette()
            } else if (holder is LyricPage) {
                lyricPage = holder
                // 先同步显示设置再装载歌词, 只触发一次重建
                holder.lyrics.textScale = lyricTextScale
                holder.lyrics.showTranslation = lyricTranslation
                holder.bind(currentSong, currentDoc)
                holder.sizeButton.setOnClickListener { cycleLyricSize() }
                holder.transButton.setOnClickListener { toggleTranslation() }
                holder.lyrics.onSeek = { ms -> PlayerController.seekTo(ms) }
                syncLyric(positionMs(), smooth = false)
                syncPlayIcons()
                applySongPalette()
            }
        }

        override fun getItemCount(): Int = PAGE_COUNT
    }

    /**
     * 点副标题里的歌手名。
     *
     * 一位歌手直接进主页；多位（QQ 音乐用 `/` 拼接，如 `A/B`）先弹一个选择框 ——
     * 否则永远只能进第一位歌手，其他歌手没有入口。
     */
    private fun chooseSinger(song: Song, ctx: android.content.Context) {
        val names = song.singer.split('/', '／').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return
        if (names.size == 1) {
            openSinger(ctx, names[0], song.singerMids.firstOrNull().orEmpty())
            return
        }
        Menus.show(this, getString(R.string.choose_singer), names) { which ->
            openSinger(ctx, names[which], song.singerMids.getOrNull(which).orEmpty())
        }
    }

    /** 进歌手主页；mid 缺失时按名字反查一次（推荐接口不带 singer mid），查不到退回搜索页 */
    private fun openSinger(ctx: android.content.Context, name: String, mid: String) {
        if (mid.isNotEmpty()) {
            ctx.startActivity(Extras.singerIntent(ctx, mid, name))
            return
        }
        Task.io {
            val found = com.portable.qqmusic.data.api.SingerApi.findByName(name)
            Task.main {
                if (found == null || found.mid.isEmpty()) {
                    ctx.startActivity(Extras.searchIntent(ctx, name))
                } else {
                    ctx.startActivity(Extras.singerIntent(ctx, found))
                }
            }
        }
    }

    /** 第 1 页: 封面 + 歌名 + 歌手 + 音质角标 */
    private inner class CoverPage(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.cover)
        val title: TextView = itemView.findViewById(R.id.title)
        val subtitle: TextView = itemView.findViewById(R.id.subtitle)
        val qualityBadge: TextView = itemView.findViewById(R.id.quality_badge)
        val buffering: ProgressBar = itemView.findViewById(R.id.buffering)
        val download: ImageButton = itemView.findViewById(R.id.btn_download)
        val favorite: ImageButton = itemView.findViewById(R.id.btn_favorite)
        val outputBadge: View = itemView.findViewById(R.id.output_badge)
        val outputText: TextView = itemView.findViewById(R.id.output_text)

        /** 当前绑着的歌曲 mid, 用来判断「收藏变化是不是这一首的」。 */
        private var boundMid: String = ""

        /** 收藏状态在别处变了 —— 不重新问服务器, 直接按新状态画。 */
        fun refreshFavorite(mid: String, on: Boolean) {
            if (boundMid != mid) return
            applyFavoriteIcon(on)
        }

        /**
         * 输出通路: 设备 + 输出采样率 + 源是否同档。
         *
         * 不做「DAC 直出」的宣称 —— 普通应用绕不过系统混音器, 只有源采样率和设备采样率
         * 一致时那一档重采样才是恒等变换。采样率读不出来就只显示设备名。
         */
        fun bindOutput(song: Song?) {
            val file = if (song == null) null else PlayerController.audioFileForEnvelope(itemView.context)
            val info = if (song == null) null else AudioOutput.query(itemView.context, file)
            if (info == null || info.hzText.isEmpty()) {
                outputBadge.visibility = View.GONE
                return
            }
            val sb = StringBuilder(info.label).append(" · ").append(info.hzText)
            if (info.srcHz > 0) {
                sb.append(if (info.bitPerfect) " · 直通" else " · 重采样")
            }
            outputText.text = sb
            outputBadge.visibility = View.VISIBLE
            applyOutputTint()
        }

        /** 徽章跟副色走; 主题色还没算出来时用默认的次要文字色 */
        fun applyOutputTint() {
            val color = themeSub ?: ContextCompat.getColor(itemView.context, R.color.text_on_player_secondary)
            outputText.setTextColor(color)
            (outputBadge as? android.view.ViewGroup)?.let { row ->
                for (i in 0 until row.childCount) {
                    val child = row.getChildAt(i)
                    if (child is android.widget.ImageView) {
                        ImageViewCompat.setImageTintList(child, ColorStateList.valueOf(color))
                    }
                }
            }
        }

        /**
         * 歌名用主色, 歌手/专辑与右侧两个小图标用副色。
         *
         * 原先靠「白色 / 半透明白」分两档, 配色跟封面走之后这两档都换成封面取出来的颜色。
         */
        fun applyPalette(main: Int, sub: Int) {
            title.setTextColor(main)
            subtitle.setTextColor(sub)
            val tint = ColorStateList.valueOf(sub)
            ImageViewCompat.setImageTintList(download, tint)
            ImageViewCompat.setImageTintList(favorite, tint)
            applyOutputTint()
        }

        fun bind(song: Song?) {
            if (song == null) {
                boundMid = ""
                title.text = getString(R.string.player_no_song)
                subtitle.text = ""
                cover.setImageResource(R.drawable.ic_music_placeholder)
                bindQualityBadge()
                buffering.visibility = View.GONE
                download.visibility = View.GONE
                favorite.visibility = View.GONE
                bindOutput(null)
                return
            }
            title.text = song.name
            boundMid = song.mid
            bindSubtitle(song)
            bindOutput(song)
            ImageLoader.loadSong(itemView.context, song, cover)
            bindQualityBadge()
            bindDownload(song)
            bindFavorite(song)
        }

        /**
         * 副标题「歌手 - 专辑」拆成两段可点区域。
         *
         * 用的是 [ClickableSpan] 而不是两个 TextView: 拆成两个控件之后中间那条竖线
         * 要单独排布, 而且长专辑名换行时两段会各走各的, 对不齐。
         */
        fun bindSubtitle(song: Song) {
            val singer = song.singer.trim()
            val album = song.albumName.trim()
            if (singer.isEmpty() && album.isEmpty()) {
                subtitle.text = ""
                return
            }
            val raw = if (album.isEmpty()) singer else if (singer.isEmpty()) album else "$singer - $album"
            val span = android.text.SpannableString(raw)

            if (singer.isNotEmpty()) {
                span.setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        chooseSinger(song, widget.context)
                    }

                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false
                    }
                }, 0, singer.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (album.isNotEmpty() && song.albumMid.isNotEmpty()) {
                val start = raw.length - album.length
                span.setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        startActivity(
                            Extras.albumIntent(this@NowPlayingActivity, song.albumMid, album)
                        )
                    }

                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false
                    }
                }, start, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // 只要有一段可点就得给 movement method, 否则 ClickableSpan 收不到点击 ——
            // 以前只在「专辑可点」时设置, 没有专辑的歌点歌手名是没反应的。
            val singerClickable = singer.isNotEmpty()
            val albumClickable = album.isNotEmpty() && song.albumMid.isNotEmpty()
            subtitle.movementMethod = if (singerClickable || albumClickable) {
                android.text.method.LinkMovementMethod.getInstance()
            } else {
                null
            }

            subtitle.text = span
        }

        /**
         * 歌名右侧的爱心 (与左侧下载按钮对称)。
         *
         * 状态来源: [favoriteStates] 命中直接用; 没命中就问 [UserApi.favoriteState]
         * (缓存命中就直接判, 否则单查这一首并顺手预热整表, 不再同步翻三页)。
         */
        private fun bindFavorite(song: Song) {
            if (!song.hasRealMid) {
                favorite.visibility = View.GONE
                return
            }
            favorite.visibility = View.VISIBLE
            val mid = song.mid
            val known = favoriteStates[mid]
            applyFavoriteIcon(known == true)
            favorite.setOnClickListener { toggleFavorite(song) }
            if (known != null) return
            Task.io {
                // 缓存命中直接判; 没命中只查这一首 (1 个请求), 不再同步翻三页。
                val fav = runCatching { UserApi.favoriteState(mid) }.getOrDefault(false)
                Task.main {
                    if (isFinishing || isDestroyed) return@main
                    favoriteStates[mid] = fav
                    if (currentSong?.mid == mid) applyFavoriteIcon(fav)
                }
            }
        }

        private fun applyFavoriteIcon(on: Boolean) {
            favorite.setImageResource(
                if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            )
            favorite.contentDescription =
                getString(if (on) R.string.action_unfavorite else R.string.action_favorite)
        }

        /** 点赞时的「跳一下」: 先放大再回弹, 让手感是立刻的。 */
        private fun bounceFavorite() {
            favorite.animate().cancel()
            favorite.scaleX = 1f
            favorite.scaleY = 1f
            favorite.animate()
                .scaleX(1.35f).scaleY(1.35f)
                .setDuration(110L)
                .withEndAction {
                    favorite.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                }
                .start()
        }

        private fun toggleFavorite(song: Song) {
            val on = favoriteStates[song.mid] == true
            val target = !on
            // 动画先行: 图标立刻切过去, 用户不用等网络。
            // 真的写失败时再把图标翻回来并给一句提示 (见 SongActions.setFavorite 的 onRollback)。
            favoriteStates[song.mid] = target
            applyFavoriteIcon(target)
            if (target) bounceFavorite()
            SongActions.setFavorite(
                this@NowPlayingActivity,
                song,
                target,
                onDone = { now ->
                    favoriteStates[song.mid] = now
                    if (currentSong?.mid == song.mid) applyFavoriteIcon(now)
                },
                onRollback = {
                    favoriteStates[song.mid] = on
                    if (currentSong?.mid == song.mid) applyFavoriteIcon(on)
                }
            )
        }

        /**
         * 封面左下方的下载按钮。
         *
         * 已经在本机 (下载下来的歌也是 TYPE_LOCAL) 就没有再下一次的必要, 直接隐藏;
         * 在线歌点一下走 DownloadManager, 已下载过的给个提示而不是重复入队。
         */
        private fun bindDownload(song: Song) {
            download.visibility = if (song.isLocal) View.GONE else View.VISIBLE
            download.setOnClickListener {
                val s = currentSong ?: return@setOnClickListener
                if (s.isLocal) return@setOnClickListener
                if (DownloadManager.isDownloaded(s)) {
                    Tip.toast(this@NowPlayingActivity, getString(R.string.toast_already_downloaded))
                    return@setOnClickListener
                }
                DownloadManager.enqueue(this@NowPlayingActivity, listOf(s))
                Tip.toast(this@NowPlayingActivity, getString(R.string.toast_added_to_download))
            }
        }

        /** 角标文案 + 说明一起更新, 免得两处各写一遍导致对不上 */
        fun bindQualityBadge() {
            qualityBadge.text = qualityShort()
            qualityBadge.contentDescription = qualityDescription()
        }
    }

    /** 第 2 页: 歌词详情 */
    private inner class LyricPage(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lyricCover: ImageView = itemView.findViewById(R.id.lyric_cover)
        val lyricTitle: TextView = itemView.findViewById(R.id.lyric_title)
        val lyricArtist: TextView = itemView.findViewById(R.id.lyric_artist)
        val lyrics: LyricsView = itemView.findViewById(R.id.lyrics_view)
        val sizeButton: android.widget.ImageButton = itemView.findViewById(R.id.btn_lyric_size)
        val transButton: TextView = itemView.findViewById(R.id.btn_lyric_trans)

        /**
         * 歌词用副色: 普通行是副色调淡版, 当前行是副色提亮版。
         *
         * 当前行不能直接用副色原色 —— 跟唱那一行必须比别的行亮, 否则「唱到哪」就看不出来了。
         */
        fun applyPalette(main: Int, sub: Int) {
            lyricTitle.setTextColor(main)
            lyricArtist.setTextColor(sub)
            lyrics.setPalette(sub, ColorUtils.blendARGB(sub, Color.WHITE, 0.55f))
            // 底部两个图标 (字号 / 翻译) 一起跟着副色走。
            // 字号那个是 ImageButton, 直接改 imageTintList; 翻译是 TextView + drawableStart,
            // setTextColor 管不到图标, 得走 TextViewCompat。
            val tint = ColorStateList.valueOf(sub)
            sizeButton.imageTintList = tint
            transButton.setTextColor(sub)
            TextViewCompat.setCompoundDrawableTintList(transButton, tint)
        }

        fun bind(song: Song?, doc: Lyrics.Document?) {
            if (song != null) {
                lyricTitle.text = song.name
                lyricArtist.text = song.singer
                ImageLoader.loadSong(itemView.context, song, lyricCover)
            } else {
                lyricTitle.text = getString(R.string.player_no_song)
                lyricArtist.text = ""
            }
            if (doc == null) {
                lyrics.setEmpty()
            } else {
                lyrics.setLyrics(doc)
            }
            // 歌词扫色按「锚点 + 系统时间」推进, 得先告诉它现在是不是在放
            lyrics.setPlaying(PlayerController.isPlaying)
            updateSettingLabels()
        }

        fun updateSettingLabels() {
            sizeButton.setImageResource(sizeIconRes())
            transButton.alpha = if (lyricTranslation) 1f else 0.45f
        }
    }

    // ---------------- 绑定状态 ----------------

    /**
     * 用音频响度反推每句真正唱完的时刻, 回填给歌词栏。
     *
     * LRC 只给「这一句什么时候开始」, 结束点是按下一句的开始推的。中间隔了间奏或者尾奏时,
     * 一句一秒的字会扫上一二十秒, 看着像卡住。这里拿完整缓存文件解出响度包络, 在每句的
     * [line.timeMs, 下一句) 区间里从后往前找最后一个真正有声的位置。
     *
     * 三件事都可以失败, 失败就当没测出来 —— 扫色仍走原来的时间轴:
     * 文件没缓存完 / 解码失败 / 这段里听不出人声。
     */

    /** 诊断用：整首歌人声频带占比的中位数，用来判断门限是不是设歪了 */

    private fun bind(song: Song?) {
        val songChanged = song?.mid != currentSong?.mid
        currentSong = song

        if (songChanged) {
            currentDoc = null
            lyricPage?.lyrics?.setEmpty()
        }

        coverPage?.bind(song)
        // 绑定这一刻设备混音率可能还是上一首的码流, 等新码流真正提交后再校准一次
        scheduleOutputRefresh(900L)
        lyricPage?.bind(song, currentDoc)
        addPlaylistButton.visibility =
            if (song != null && song.hasRealMid) View.VISIBLE else View.GONE
        if (songChanged) applyCoverPalette(song)

        // 只信这首歌自己的元数据时长。不能拿 PlayerController.duration():
        // 切歌时上一首只是 pause 了, 解码器还挂着它的时长, 取过来就成了"新歌配旧分母",
        // 连点下一首时表现就是进度条怎么走都对不上。真实时长等 onProgress 再校正。
        durationMs = if (song != null && song.durationSec > 0) song.durationSec * 1000L else 0L
        if (songChanged) {
            seekBar.progress = 0
            positionText.text = PlayUrlApi.durationText(0)
        }
        durationText.text = PlayUrlApi.durationText((durationMs / 1000).toInt())
        syncPlayIcons()
        updateBuffering(PlayerController.isBuffering)

        if (songChanged && pager.currentItem == PAGE_LYRIC) ensureLyrics()
    }

    // ---------------- 跟着封面配色 ----------------

    /**
     * 播放详情的配色跟着封面走。
     *
     * 一个主色 + 三个配色, 各管一处:
     *  - 主色  : 进度条的填充 (vibrant, 退化到 dominant / muted)
     *  - 背景顶: 主色压到很暗, 让上方标题区染上封面的色调
     *  - 背景腰: 强调色再压暗一档, 作为中间过渡
     *  - 背景底: 回落默认深色, 保证进度条与按钮的对比度
     *
     * 没有封面、或者取色失败, 就整屏回落默认主题。
     */
    private fun applyCoverPalette(song: Song?) {
        val url = song?.coverUrl()
        if (url.isNullOrEmpty()) {
            resetPlayerTheme()
            return
        }
        ImageLoader.loadBitmap(
            applicationContext,
            url,
            onReady = { bitmap ->
                val palette = Palette.from(bitmap).maximumColorCount(24).generate()
                val primary = palette.vibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                if (primary == null) {
                    Task.main { if (!isFinishing && !isDestroyed) resetPlayerTheme() }
                    return@loadBitmap
                }
                val accent = palette.lightVibrantSwatch?.rgb
                    ?: palette.lightMutedSwatch?.rgb
                    ?: ColorUtils.blendARGB(primary, Color.WHITE, 0.55f)
                val main = liftForTrack(primary)
                // 顶栏图标和歌名用的是提亮后的主色, 背景得压到和它至少 3:1,
                // 否则彩色字糊在彩色底上 —— 亮封面时尤其明显
                val bgTop = Contrast.darkenUntil(
                    main,
                    ColorUtils.blendARGB(primary, Color.BLACK, 0.58f),
                    Contrast.AA_LARGE
                )
                // 中间这一档让强调色透出来一点: 压太浅会把整屏拖成土褐色, 压太深又看不出偏色
                val bgMid = ColorUtils.blendARGB(accent, Color.BLACK, 0.72f)
                Task.main {
                    if (isFinishing || isDestroyed) return@main
                    playerRoot.background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(bgTop, bgMid, ContextCompat.getColor(this, R.color.bg_player))
                    )
                    // 状态栏吃掉渐变顶部的颜色, 变成「背景一直铺到屏幕最上面」的效果
                    @Suppress("DEPRECATION")
                    run { window.statusBarColor = bgTop }
                    seekBar.progressDrawable = frostedProgress(main)
                    applyIconTint(main)
                    // 副色只借一点色相: 直接用强调色的原色会暗成一团土黄, 混白之后才读得清
                    themeMain = main
                    themeSub = ColorUtils.blendARGB(liftForTrack(accent), Color.WHITE, 0.42f)
                    applySongPalette()
                }
            },
            // 封面拉不到就别让上一首的配色留在屏幕上
            onFail = { Task.main { if (!isFinishing && !isDestroyed) resetPlayerTheme() } }
        )
    }

    /**
     * 主色铺到图标和歌名上。
     *
     * 顶栏和底部控制栏总共七个图标, 原先分「主要」「次要」两档白 —— 配色跟着封面走之后
     * 统一成同一个主色, 分成两档反而把主色拆散了。
     */
    private fun applyIconTint(color: Int) {
        val tint = ColorStateList.valueOf(color)
        listOf(
            R.id.btn_collapse, R.id.btn_add_playlist, R.id.btn_mode,
            R.id.btn_prev, R.id.btn_play, R.id.btn_next, R.id.btn_queue
        ).forEach { id ->
            (findViewById<View>(id) as? ImageView)?.let {
                ImageViewCompat.setImageTintList(it, tint)
            }
        }
        positionText.setTextColor(color)
        durationText.setTextColor(color)
    }

    /**
     * 歌名/副标题的配色。
     *
     * `coverPage` 和 `lyricPage` 是在 ViewPager2 的 onBindViewHolder 里才赋值的,
     * 而取色回来的时候页面可能还没绑定 —— 所以主题色先记在字段里, 绑定后由
     * onBindViewHolder 再补一次 applySongPalette()。
     */
    private fun applySongPalette() {
        val main = themeMain ?: return
        val sub = themeSub ?: return
        coverPage?.applyPalette(main, sub)
        lyricPage?.applyPalette(main, sub)
        updateIndicator(pager.currentItem)
    }

    private fun resetIconTint() {
        val main = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_on_player))
        val sub = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_on_player_secondary))
        listOf(
            R.id.btn_collapse, R.id.btn_add_playlist,
            R.id.btn_prev, R.id.btn_play, R.id.btn_next
        ).forEach { id ->
            (findViewById<View>(id) as? ImageView)?.let { ImageViewCompat.setImageTintList(it, main) }
        }
        listOf(R.id.btn_mode, R.id.btn_queue).forEach { id ->
            (findViewById<View>(id) as? ImageView)?.let { ImageViewCompat.setImageTintList(it, sub) }
        }
        positionText.setTextColor(ContextCompat.getColor(this, R.color.text_on_player_secondary))
        durationText.setTextColor(ContextCompat.getColor(this, R.color.text_on_player_secondary))
    }

    /**
     * 把主色提到能在深色轨道上看得出来的亮度。
     *
     * 封面主色常常是墨蓝、深褐这类很暗的颜色, 原样画进进度条几乎和半透明的轨道一个色,
     * 用户只会看到一根灰条。这里按亮度逐档往白里混, 色相不变, 只抬明度。
     */
    private fun liftForTrack(color: Int): Int {
        var lifted = color
        var guard = 0
        while (ColorUtils.calculateLuminance(lifted) < 0.42f && guard++ < 8) {
            lifted = ColorUtils.blendARGB(lifted, Color.WHITE, 0.18f)
        }
        return lifted
    }

    private fun resetPlayerTheme() {
        themeMain = null
        themeSub = null
        playerRoot.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_player))
        @Suppress("DEPRECATION")
        run { window.statusBarColor = ContextCompat.getColor(this, R.color.bg_player) }
        seekBar.progressDrawable =
            frostedProgress(liftForTrack(ContextCompat.getColor(this, R.color.brand)))
        resetIconTint()
        coverPage?.applyPalette(
            ContextCompat.getColor(this, R.color.text_on_player),
            ContextCompat.getColor(this, R.color.text_on_player_secondary)
        )
        lyricPage?.applyPalette(
            ContextCompat.getColor(this, R.color.text_on_player),
            ContextCompat.getColor(this, R.color.text_on_player_secondary)
        )
    }

    /**
     * 磨砂玻璃质感的进度条。
     *
     * 这台设备是 API 24, 用不了 RenderEffect 的真模糊, 玻璃感只能画出来:
     * 轨道是上下两档半透明白加一圈极细的白描边, 上缘亮、下缘暗, 像一块受光的毛玻璃;
     * 填充层沿用同样的描边, 颜色换成封面主色并往白里提亮一点做出厚度。
     */
    private fun frostedProgress(primary: Int): Drawable {
        // 圆角取高度的一半, 两端就是完整的半圆 —— 6dp 高的进度条配 3dp 圆角
        val radius = dp(3f)
        val stroke = dp(0.8f).toInt().coerceAtLeast(1)
        val track = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x33FFFFFF, 0x1AFFFFFF)
        ).apply {
            cornerRadius = radius
            setStroke(stroke, 0x66FFFFFF)
        }
        val fill = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.blendARGB(primary, Color.WHITE, 0.42f), primary)
        ).apply {
            cornerRadius = radius
            setStroke(stroke, 0x99FFFFFF.toInt())
        }
        val layer = LayerDrawable(
            arrayOf(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))
        )
        layer.setId(0, android.R.id.background)
        layer.setId(1, android.R.id.progress)
        return layer
    }

    /**
     * 在封面页向下滑就收起播放详情。
     *
     * 只由 [dispatchTouchEvent] 旁听、不消费事件: ViewPager2 的横向翻页、SeekBar 的拖动
     * 都还得照常收到完整手势。判定条件卡了三道 —— 纵向位移够远、速度够快、横向分量
     * 不能压过纵向, 否则斜着划一下就误退了。
     */
    private val swipeDownDetector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dy = e2.y - start.y
                if (dy > dp(96f) && velocityY > 800f &&
                    kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY) * 1.5f
                ) {
                    collapse()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (pager.currentItem == PAGE_COVER) swipeDownDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 收起播放详情。
     *
     * 退出动画是「整页向下滑出」而不是默认的向右平移 —— 这一屏是从底部迷你条升上来的,
     * 收起时原路退回才对得上。两个参数是 (进入, 退出): 这里进入留空, 因为收起时
     * 底下那层(主界面)不该有任何动作。
     */
    private fun collapse() {
        finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_down_out)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun syncPlayIcons() {
        val playing = PlayerController.isPlaying
        val icon = if (playing) R.drawable.ic_pause_ring else R.drawable.ic_play_ring
        val desc = getString(if (playing) R.string.action_pause else R.string.action_play)
        playButton.setImageResource(icon)
        playButton.contentDescription = desc
    }

    private fun updateBuffering(buffering: Boolean) {
        coverPage?.buffering?.visibility = if (buffering) View.VISIBLE else View.GONE
    }

    private fun refreshMode() {
        val mode = PlayerController.playMode()
        modeButton.setImageResource(
            when (mode) {
                PlayMode.LIST_LOOP -> R.drawable.ic_repeat
                PlayMode.SINGLE_LOOP -> R.drawable.ic_repeat_one
                PlayMode.SHUFFLE -> R.drawable.ic_shuffle
            }
        )
        modeButton.contentDescription = mode.label
    }

    /**
     * 音质角标显示**实际在用**的那档, 不是设置里的预设。
     *
     * 预设 Hi-Res 但服务端对这首歌只给标准档时, [PlayerController.actualQualityOf]
     * 会返回 STANDARD, 角标就写「标准」—— 用户要的是「当前使用的质量」。
     * 解析完成前 (通常几百毫秒) 先显示「…」, 不拿预设去冒充实际值。
     */
    private fun qualityShort(): String {
        val song = currentSong
        if (song != null && song.isLocal) return getString(R.string.quality_local)
        return when (PlayerController.actualQualityOf(song)) {
            Quality.STANDARD -> "标准"
            Quality.HQ -> "HQ"
            Quality.SQ -> "SQ"
            Quality.HIRES -> "Hi-Res"
            null -> "…"
        }
    }

    /** 角标在读屏/长按提示里的说明: 降过档时把预设也讲清楚 */
    private fun qualityDescription(): String {
        val song = currentSong
        if (song != null && song.isLocal) return getString(R.string.quality_local)
        val actual = PlayerController.actualQualityOf(song)
            ?: return getString(R.string.quality_resolving)
        val preset = Settings.onlineQuality()
        return if (actual == preset) {
            getString(R.string.quality_current_format, actual.label)
        } else {
            getString(R.string.quality_downgraded_format, actual.label, preset.label)
        }
    }

    private fun positionMs(): Int = PlayerController.position()

    // ---------------- 歌词 ----------------

    private fun ensureLyrics() {
        val song = currentSong ?: return

        /*
         * 纯本地文件 (mid = local_/file_) 本身没有线上歌词, 但我们按「歌名 + 歌手」借了
         * 一个 QQ 音乐的 mid —— 拿那个去请求才有词。批量匹配过的直接命中缓存,
         * 没匹配过的在这里补一次 (LocalMeta 内部也会落盘, 下次就快了)。
         */
        val requestMid = if (song.hasRealMid) {
            song.mid
        } else {
            val borrowed = LocalMeta.resolveBlocking(this, song)
            if (borrowed == null || borrowed.mid.isEmpty()) {
                if (currentDoc == null) {
                    val empty = Lyrics.Document(emptyList())
                    currentDoc = empty
                    lyricPage?.lyrics?.setLyrics(empty)
                }
                return
            }
            // 借到 mid 的同时也借到了真歌手: 本地 TAG 里是「未知歌手」的话就地换掉
            if (borrowed.singer.isNotEmpty()) PlayerController.applySinger(song.mid, borrowed.singer)
            borrowed.mid
        }

        currentDoc?.let { doc ->
            /*
             * 空文档不要挡住后面的加载。
             *
             * 切歌时上一首的文档可能还挂在 currentDoc 上, 若那首恰好没有歌词, 这里会直接
             * setLyrics(空) 再 return, 新歌的歌词永远没机会去请求 —— 表现就是「一直暂无歌词」。
             */
            if (doc.isEmpty) {
                currentDoc = null
                return@let
            }
            // 已有歌词: 直接应用并按当前进度定位
            lyricPage?.lyrics?.setLyrics(doc)
            syncLyric(positionMs(), smooth = false)
            return
        }
        if (lyricLoadingFor == song.mid) {
            Diag.i("NowPlaying", "歌词请求进行中, 跳过 mid=${song.mid}")
            return
        }

        lyricCache[song.mid]?.let { cached ->
            currentDoc = cached
            lyricPage?.lyrics?.setLyrics(cached)
            syncLyric(positionMs(), smooth = false)
            return
        }

        // 播放时已经把当前 + 后面几首的歌词预热过了: 直接命中, 省掉一次网络往返
        LyricPrefetcher.cachedDoc(song.mid)?.let { doc ->
            if (!doc.isEmpty) {
                lyricCache[song.mid] = doc
                currentDoc = doc
                lyricPage?.lyrics?.setLyrics(doc)
                syncLyric(positionMs(), smooth = false)
                return
            }
        }

        lyricLoadingFor = song.mid
        Task.io {
            val (lrc, trans) = try {
                LyricPrefetcher.lyric(this@NowPlayingActivity, requestMid)
            } catch (t: Throwable) {
                Log.w("NowPlaying.lyric", t)
                "" to ""
            }
            /*
             * QRC 优先, LRC 保底。
             *
             * 预取那条路已经把 QRC 拉过了, 这里大多是命中缓存; 真的 miss（比如直接从外部
             * 深链进播放页）就补一次。QrcSource 内部吞掉所有异常, 失败返回空表退回 LRC。
             */
            val qrc = com.portable.qqmusic.data.api.qrc.QrcSource
                .fetch(this@NowPlayingActivity, requestMid)
            val parsed = Lyrics.parse(lrc, trans)
            val doc = if (qrc.isNotEmpty()) {
                Diag.i("NowPlaying", "歌词源=QRC mid=${song.mid} ${qrc.size} 行")
                Lyrics.fromQrc(qrc, trans)
            } else {
                Diag.i("NowPlaying", "歌词源=LRC mid=${song.mid} ${parsed.lines.size} 行")
                Lyrics.applyTranslation(parsed)
            }
            Task.main {
                if (isFinishing || isDestroyed) return@main
                lyricLoadingFor = null
                lyricCache[song.mid] = doc
                // 只有当前歌曲仍是请求时的那首才应用
                if (currentSong?.mid == song.mid) {
                    currentDoc = doc
                    lyricPage?.lyrics?.setLyrics(doc)
                    syncLyric(positionMs(), smooth = false)
                    Diag.i("NowPlaying", "歌词加载完成 ${song.name} 行数=${doc.lines.size} 逐字=${doc.lines.count { it.hasWords }}")
                }
            }
        }
    }

    /** 把播放进度同步到歌词高亮 */
    private fun syncLyric(positionMs: Int, smooth: Boolean = true) {
        val view = lyricPage?.lyrics ?: return
        if (!view.hasLyrics()) return
        view.syncTo(positionMs, smooth)
    }

    private fun cycleLyricSize() {
        lyricTextScale = when {
            lyricTextScale < 0.99f -> 1.0f
            lyricTextScale < 1.19f -> 1.25f
            lyricTextScale < 1.49f -> 1.5f
            else -> 0.85f
        }
        val page = lyricPage
        if (page != null) {
            // 记录当前行, 重建后按时间重新定位, 避免跳回开头
            val anchor = currentDoc?.lines?.getOrNull(page.lyrics.currentLine())?.timeMs
            page.lyrics.textScale = lyricTextScale
            page.updateSettingLabels()
            page.lyrics.syncToImmediately(anchor ?: positionMs())
        }
    }

    /** 当前字号档位对应的图标: 图标里的字母本身随档位变大, 不用再读一遍数字 */
    private fun sizeIconRes(): Int = when {
        lyricTextScale < 0.99f -> R.drawable.ic_font_small
        lyricTextScale < 1.19f -> R.drawable.ic_font_medium
        lyricTextScale < 1.49f -> R.drawable.ic_font_large
        else -> R.drawable.ic_font_xlarge
    }

    private fun toggleTranslation() {
        lyricTranslation = !lyricTranslation
        val page = lyricPage
        if (page != null) {
            val anchor = currentDoc?.lines?.getOrNull(page.lyrics.currentLine())?.timeMs
            page.lyrics.showTranslation = lyricTranslation
            page.updateSettingLabels()
            page.lyrics.syncToImmediately(anchor ?: positionMs())
        }
        // 打开翻译只是切换是否显示服务端自带译文, 没有本地补翻这一步
        Tip.toast(
            this,
            getString(if (lyricTranslation) R.string.lyric_translation_on else R.string.lyric_translation_off)
        )
    }

    // ---------------- 弹窗 ----------------

    private fun showQualityPicker() {
        val qualities = Quality.allDesc()
        val checked = qualities.indexOf(Settings.onlineQuality()).coerceAtLeast(0)
        Menus.show(
            this,
            getString(R.string.quality_online),
            qualities.map { it.label },
            checked
        ) { which ->
            val q = qualities[which]
            Settings.setOnlineQuality(q)
            // 不在这里直接写角标: 角标显示的是**实际**音质, 要等重新解析完才知道。
            // playAt 会清零记录, 解析成功后 notifySong 把角标刷成真在放的那档。
            Tip.toast(this, getString(R.string.quality_switched, q.label))
            // 用新音质重新解析并播放当前曲目
            // ⚠️ 这里必须强制重播: playAt 默认会把「点击正在播放的歌」当成续播而不动作,
            // 那样换了音质也不会重新解析, 角标和实际音质永远对不上。
            if (currentSong != null) {
                PlayerController.playAt(this, PlayerController.currentIndex(), forceRestart = true)
            }
        }
    }

    private fun showQueue() {
        val queue = PlayerController.queueSnapshot()
        if (queue.isEmpty()) {
            Tip.toast(this, getString(R.string.empty_list))
            return
        }
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12f)
                setColor(androidx.core.content.ContextCompat.getColor(this@NowPlayingActivity, R.color.bg_card))
            }
            // 让子 View 也吃圆角 (否则列表的白色方块会盖住四个角)
            clipToOutline = true
        }
        panel.addView(android.widget.TextView(this).apply {
            text = getString(R.string.player_queue)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@NowPlayingActivity, R.color.text_primary))
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(8f).toInt())
        })

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(panel)
            .create()

        val recycler = RecyclerView(this)
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val adapter = com.portable.qqmusic.ui.adapter.SongAdapter(
            onClick = { position, _ ->
                PlayerController.playAt(this, position)
                dialog.dismiss()
            }
        )
        recycler.adapter = adapter
        adapter.submit(queue)
        // 队列里也把正在放的那首标绿, 和歌单列表保持同一套视觉
        adapter.setPlaying(PlayerController.current()?.key())
        panel.addView(
            recycler,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        dialog.show()
        dialog.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            w.setLayout((screenW * 0.82f).toInt(), (screenH * 0.62f).toInt())
        }
    }

    // ---------------- PlayerController.Listener ----------------

    override fun onSongChanged(song: Song?) {
        val previousMid = currentSong?.mid
        val changed = previousMid != null && song != null && song.mid != previousMid
        if (!changed) {
            bind(song)
            return
        }
        // 换歌: 先把当前这一屏淡掉再换内容。
        // 冷启动时新歌要重新拉直链再缓冲, 这一两百毫秒里如果还挂着上一首的封面和标题,
        // 等新信息突然蹦出来就是一次跳变; 先淡到很浅再换, 松手换信息看着自然得多。
        pager.animate().cancel()
        pager.animate().alpha(0.12f).setDuration(130L).withEndAction {
            bind(song)
            pager.animate().cancel()
            pager.animate().alpha(1f).setDuration(240L).start()
        }.start()
    }

    /** 系统返回也走向下收纳, 不然会退回默认的向右平移 */
    @Deprecated("用 collapse() 统一处理收起动画")
    override fun onBackPressed() {
        collapse()
    }

    override fun onPlayStateChanged(playing: Boolean) {
        syncPlayIcons()
        lyricPage?.lyrics?.setPlaying(playing)
        if (playing) {
            // 开声后立刻重算一次; 再等 900ms 让 AudioFlinger 把新码流的输出配置定下来。
            // 没有这一步时, 首次进播放页读到的可能还是上一首 Hi-Res 码流留下的 96 kHz。
            coverPage?.bindOutput(currentSong)
            scheduleOutputRefresh(900L)
        } else {
            handler.removeCallbacks(outputRefresh)
        }
    }

    /** 只重算输出徽章, 不动封面/歌词 —— 换歌与开声后都要用 */
    private val outputRefresh = Runnable { coverPage?.bindOutput(currentSong) }

    private fun scheduleOutputRefresh(delayMs: Long) {
        handler.removeCallbacks(outputRefresh)
        handler.postDelayed(outputRefresh, delayMs)
    }

    override fun onProgress(positionMs: Int, durationMs: Int) {
        // 以解码器报的实际时长为准, 但只在差得明显时才改:
        // 元数据里的时长和真实解码时长差几秒是常事(尤其是 FLAC), 差太多就得纠正,
        // 每帧都跟着改又会让分母抖。1 秒容差。
        if (durationMs > 0 && (this.durationMs <= 0L ||
                kotlin.math.abs(durationMs - this.durationMs) > 1000L)
        ) {
            this.durationMs = durationMs.toLong()
        }
        if (!scrubbing) {
            val progress = if (this.durationMs > 0) {
                (positionMs * 100 / this.durationMs).toInt().coerceIn(0, 100)
            } else 0
            seekBar.progress = progress
            positionText.text = PlayUrlApi.durationText(positionMs / 1000)
        }
        if (this.durationMs > 0) {
            durationText.text = PlayUrlApi.durationText((this.durationMs / 1000).toInt())
        }
    }

    override fun onQueueChanged() = refreshMode()

    override fun onBuffering(buffering: Boolean) = updateBuffering(buffering)

    override fun onError(message: String) {
        if (!isFinishing) Tip.toast(this, message)
    }

    companion object {
        private const val TICK_MS = 500L
        private const val PAGE_COVER = 0
        private const val PAGE_LYRIC = 1
        private const val PAGE_COUNT = 2
    }
}
