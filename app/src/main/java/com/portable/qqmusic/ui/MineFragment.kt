package com.portable.qqmusic.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Store
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.DownloadManager
import com.portable.qqmusic.data.LibraryRepository
import com.portable.qqmusic.data.LocalMusic
import com.portable.qqmusic.data.api.Login
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.data.api.PlaylistApi
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.data.api.UserApi
import com.portable.qqmusic.player.PlayerController
import com.portable.qqmusic.ui.adapter.PlaylistAdapter
import com.portable.qqmusic.ui.util.Extras
import com.portable.qqmusic.ui.util.ImageLoader
import com.portable.qqmusic.ui.util.Tip

/**
 * 「我的」页。
 *
 * 顶部: 搜索框 + 设置入口 (右上角)
 * 中部: 收藏 / 本地 两张卡片
 * 下部: 歌单列表, 用 Tab 区分「自建歌单」与「收藏歌单」
 */
class MineFragment : Fragment() {

    private lateinit var playlistRecycler: RecyclerView
    private lateinit var tabs: TabLayout
    private lateinit var loading: View
    private lateinit var avatar: ImageView
    private lateinit var accountName: TextView
    private lateinit var accountSub: TextView
    private lateinit var accountAction: TextView
    private lateinit var favoriteSub: TextView
    private lateinit var localSub: TextView

    private lateinit var adapter: PlaylistAdapter
    private var created: List<Playlist> = emptyList()
    private var collected: List<Playlist> = emptyList()
    private var localCount = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_mine, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playlistRecycler = view.findViewById(R.id.playlist_recycler)
        tabs = view.findViewById(R.id.playlist_tabs)
        loading = view.findViewById(R.id.loading)
        avatar = view.findViewById(R.id.account_avatar)
        accountName = view.findViewById(R.id.account_name)
        accountSub = view.findViewById(R.id.account_sub)
        accountAction = view.findViewById(R.id.account_action)
        favoriteSub = view.findViewById(R.id.favorite_sub)
        localSub = view.findViewById(R.id.local_sub)

        setupTopBar(view)
        setupCards(view)
        setupTabs()
        setupRecycler()

        view.findViewById<View>(R.id.fab_create_playlist).setOnClickListener { onCreatePlaylist() }

        refreshAccount()
        loadLibrary()
        loadLocalCount()
        // 首屏已经刷过一次, 记下当时的账号, 免得 onShown 立刻又触发一次强制刷新
        lastAccount = if (Login.isLoggedIn()) Login.uin() else ""
        DownloadManager.addListener(downloadListener)
        Store.addListener(storeListener)
    }

    /**
     * 每次切换回「我的」页时刷新账号状态。
     *
     * 注意: 必须在视图已创建时才能刷新 —— `MainActivity.showTab()` 在
     * `setupFragments()` 里是同步调用的，此时本 Fragment 可能刚被 add,
     * `onViewCreated` 尚未执行, 各 lateinit 视图字段还是未初始化状态。
     * 曾经只判断 isAdded 就刷新, 导致
     * `lateinit property accountName has not been initialized` 崩溃。
     */
    fun onShown() {
        if (!isAdded) return
        if (view?.findViewById<TextView>(R.id.account_name) == null) return
        if (!::accountName.isInitialized) return
        refreshAccount()
        // 刚登录 / 刚登出: 歌单与收藏数量必须立刻跟上, 而不是等下次冷启动
        val account = if (Login.isLoggedIn()) Login.uin() else ""
        if (account != lastAccount) {
            lastAccount = account
            loadLibrary(force = true)
        }
        // 下载完成、删歌、外部往手机里拷歌都会改变本地数量
        loadLocalCount()
    }

    /** 上一次刷新时的账号, 用来识别「登录态变了」 */
    private var lastAccount = ""

    /**
     * 歌单集合变了 (在歌单详情页收藏 / 取消收藏之后)。
     *
     * [com.portable.qqmusic.ui.PlaylistActivity] 操作成功就往 [Store.K.LIBRARY_REV]
     * 里 +1, 这里收到就绕开缓存重拉一次 —— 否则退回「我的」还看得见刚取消收藏的那张。
     */
    private val storeListener: (String) -> Unit = { key ->
        if (key == Store.K.LIBRARY_REV && isAdded && view != null) {
            loadLibrary(force = true)
        }
    }

    /**
     * 下载任务有变化: 本地音乐数量要跟着变。
     *
     * 只在「下完」时刷新, 且节流 —— 进度回调一秒能来好几次。
     */
    private val downloadListener: (DownloadManager.DownloadTask) -> Unit = { task ->
        if (task.status == DownloadManager.Status.DONE) {
            val now = System.currentTimeMillis()
            if (now - lastLocalCountAt > 800L && isAdded) loadLocalCount()
        }
    }

    private fun setupTopBar(root: View) {
        root.findViewById<View>(R.id.search_box).setOnClickListener {
            startActivity(Extras.searchIntent(requireContext()))
        }
        root.findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    private fun setupCards(root: View) {
        root.findViewById<View>(R.id.card_favorite).setOnClickListener {
            if (!Login.isLoggedIn()) {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
                return@setOnClickListener
            }
            startActivity(
                Extras.playlistIntent(
                    requireContext(),
                    UserApi.FAVORITE_DISSTID,
                    getString(R.string.card_favorite),
                    Extras.SOURCE_FAVORITE,
                    UserApi.FAVORITE_DIRID
                )
            )
        }

        root.findViewById<View>(R.id.card_local).setOnClickListener {
            ensureAudioPermission {
                startActivity(
                    Extras.playlistIntent(
                        requireContext(),
                        0L,
                        getString(R.string.card_local),
                        Extras.SOURCE_LOCAL
                    )
                )
            }
        }

        root.findViewById<View>(R.id.account_row).setOnClickListener {
            if (!Login.isLoggedIn()) {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
            } else {
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
        }
    }

    private fun setupTabs() {
        tabs.addTab(tabs.newTab().setText(R.string.section_created_playlist))
        tabs.addTab(tabs.newTab().setText(R.string.section_collected_playlist))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = renderPlaylists(tab.position)

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupRecycler() {
        adapter = PlaylistAdapter(PlaylistAdapter.MODE_ROW, onClick = { playlist ->
            openPlaylist(playlist)
        }, onMore = { _, playlist -> showPlaylistMenu(playlist) })
        playlistRecycler.layoutManager = LinearLayoutManager(requireContext())
        playlistRecycler.adapter = adapter
        playlistRecycler.setHasFixedSize(true)
    }

    /**
     * 歌单右侧「⋮」菜单。
     *
     * ⚠️ 这里原本只 `Tip.toast(playlist.title)` —— 点一下只弹个歌单名, 什么也没做。
     * 用户反馈的「右侧功能键不能实现快捷功能操作」就是这一处。
     */
    private fun showPlaylistMenu(playlist: Playlist) {
        // 自建与收藏是两套写接口: 自建传 dirId, 收藏传收藏产生的 tid。
        // 用列表归属判断而不是当前 tab —— 菜单可能在切换 tab 的动画期间被点开。
        val isCollected = collected.contains(playlist)
        val items = arrayOf("播放全部", "下载全部", "加入播放队列", "复制歌单名", "删除歌单")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(playlist.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> withPlaylistSongs(playlist) { songs ->
                        if (songs.isEmpty()) Tip.toast(requireContext(), getString(R.string.empty_list))
                        else {
                            PlayerController.setQueue(requireContext(), songs, 0, autoPlay = true)
                            startActivity(Extras.nowPlayingIntent(requireContext()))
                        }
                    }
                    1 -> withPlaylistSongs(playlist) { songs ->
                        if (songs.isEmpty()) Tip.toast(requireContext(), getString(R.string.empty_list))
                        else {
                            DownloadManager.enqueue(requireContext(), songs)
                            Tip.toast(requireContext(), "已加入下载队列 (${songs.size} 首)")
                        }
                    }
                    2 -> withPlaylistSongs(playlist) { songs ->
                        if (songs.isEmpty()) Tip.toast(requireContext(), getString(R.string.empty_list))
                        else {
                            PlayerController.appendToQueue(songs)
                            Tip.toast(requireContext(), "已加入播放队列 (${songs.size} 首)")
                        }
                    }
                    3 -> {
                        val cm = requireContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("playlist", playlist.title)
                        )
                        Tip.toast(requireContext(), "已复制歌单名")
                    }
                    else -> confirmDeletePlaylist(playlist, isCollected)
                }
            }
            .show()
    }

    /**
     * 删除/取消收藏歌单。
     *
     * 收藏的别人歌单只能取消收藏 —— 那是对方的歌单, 我们无权删除;
     * 所以文案与调用的接口都按 [isCollected] 分。
     */
    private fun confirmDeletePlaylist(playlist: Playlist, isCollected: Boolean) {
        com.portable.qqmusic.core.Diag.i(
            "MineFragment",
            "删除歌单入口 title=${playlist.title} isCollected=$isCollected " +
                "tid=${playlist.tid} dirid=${playlist.dirid} id=${playlist.id}"
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(if (isCollected) "取消收藏「${playlist.title}」?" else "删除「${playlist.title}」?")
            .setMessage(
                if (isCollected) "只会从你的收藏里移除, 不会影响对方创建的歌单。"
                else "歌单会被删除, 且无法恢复。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton(if (isCollected) "取消收藏" else "删除") { _, _ ->
                Task.io {
                    val mid = playlist.tid
                    val id = if (isCollected) mid else playlist.dirid
                    val ok = if (isCollected) {
                        UserApi.unfavoritePlaylist(id)
                    } else {
                        UserApi.deletePlaylist(id)
                    }
                    Task.main {
                        if (!isAdded) return@main
                        if (ok) {
                            Tip.toast(
                                requireContext(),
                                if (isCollected) "已取消收藏" else "已删除歌单"
                            )
                            loadLibrary(force = true)
                        } else {
                            Tip.toast(requireContext(), "操作失败, 请检查登录状态")
                        }
                    }
                }
            }
            .show()
    }

    /** 后台取歌单歌曲, 回到主线程执行 [action] */
    private fun withPlaylistSongs(playlist: Playlist, action: (List<Song>) -> Unit) {
        Tip.toast(requireContext(), "正在读取歌单…")
        Task.io {
            val songs = try {
                // 收藏歌单只有 legacy 认 tid; 自建歌单走用户目录的 dirid
                val (_, list) = if (playlist.tid > 0L) {
                    PlaylistApi.legacyDetail(playlist.tid)
                } else {
                    PlaylistApi.playlistDetail(playlist.id, 500, playlist.dirid)
                }
                list
            } catch (t: Throwable) {
                Log.w("MineFragment.withPlaylistSongs", t)
                emptyList()
            }
            Task.main {
                if (!isAdded) return@main
                action(songs)
            }
        }
    }

    // ---------------- 数据 ----------------

    private fun refreshAccount() {
        val loggedIn = Login.isLoggedIn()
        accountName.text = if (loggedIn) Login.nick().ifEmpty { "QQ 音乐用户" } else getString(R.string.not_login)
        accountSub.text = if (loggedIn) {
            if (Login.isVip) "会员 · ${Login.uin()}" else Login.uin()
        } else {
            getString(R.string.login_now)
        }
        accountAction.text = if (loggedIn) getString(R.string.action_settings) else getString(R.string.login_now)
        if (loggedIn && Login.avatar().isNotEmpty()) {
            ImageLoader.load(requireContext(), Login.avatar(), avatar)
        } else {
            avatar.setImageResource(R.drawable.ic_person)
        }
    }

    /**
     * 加载资料库。
     *
     * 先出本地缓存 (冷启动/断网时立刻有内容), 再静默用网络刷一遍 ——
     * 只吃缓存会让改过的歌单一直不更新, 只等网络又会白屏几秒。
     */
    private fun loadLibrary(force: Boolean = false) {
        if (!Login.isLoggedIn()) {
            created = emptyList()
            collected = emptyList()
            renderPlaylists(tabs.selectedTabPosition)
            favoriteSub.text = getString(R.string.need_login)
            return
        }
        val hasContent = created.isNotEmpty() || collected.isNotEmpty()
        if (!hasContent) loading.visibility = View.VISIBLE
        Task.io {
            if (!force) {
                val cached = LibraryRepository.load(localCount, useCache = true)
                if (cached.created.isNotEmpty() || cached.collected.isNotEmpty()) {
                    Task.main { if (isAdded) applyLibrary(cached) }
                }
            }
            val fresh = LibraryRepository.load(localCount, useCache = false)
            Task.main { if (isAdded) applyLibrary(fresh) }
        }
    }

    private fun applyLibrary(result: LibraryRepository.Result) {
        loading.visibility = View.GONE
        created = result.created
        collected = result.collected
        favoriteSub.text = if (result.favoriteSongCount > 0) {
            getString(R.string.song_count_format, result.favoriteSongCount)
        } else {
            getString(R.string.card_favorite_desc)
        }
        renderPlaylists(tabs.selectedTabPosition)
    }

    private var lastLocalCountAt = 0L

    private fun loadLocalCount() {
        val now = System.currentTimeMillis()
        if (now - lastLocalCountAt < 1_000L) return
        lastLocalCountAt = now
        val ctx = context ?: return
        Task.io {
            val count = runCatching { LocalMusic.query(ctx, 500).size }.getOrDefault(0)
            Task.main {
                if (!isAdded) return@main
                localCount = count
                localSub.text = getString(R.string.song_count_format, count)
            }
        }
    }

    private fun renderPlaylists(tabIndex: Int) {
        val list = if (tabIndex == 0) created else collected
        adapter.submit(list)
    }

    private fun openPlaylist(playlist: Playlist) {
        // 收藏的别人歌单与自建歌单要用不同的接口取歌曲, 手里的 id 也不一样:
        // 收藏歌单手里是 tid (只有 legacy 认), 自建歌单手里是 disstid + dirid。
        // 判断用列表归属而不是当前 tab 下标 —— 菜单/其它入口进来时下标未必同步。
        val isCollected = collected.contains(playlist)
        startActivity(
            Extras.playlistIntent(
                requireContext(),
                if (isCollected) playlist.tid else playlist.id,
                playlist.title,
                if (isCollected) Extras.SOURCE_COLLECTED else Extras.SOURCE_PLAYLIST,
                if (isCollected) 0L else playlist.dirid,
                playlist.cover
            )
        )
    }

    private fun onCreatePlaylist() {
        if (!Login.isLoggedIn()) {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            return
        }
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.create_playlist)
            setSingleLine()
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_playlist)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                Task.io {
                    val ok = UserApi.createPlaylist(name)
                    Task.main {
                        if (!isAdded) return@main
                        Tip.toast(
                            requireContext(),
                            if (ok) "已创建: $name" else "创建失败, 请稍后重试"
                        )
                        if (ok) loadLibrary()
                    }
                }
            }
            .show()
    }

    // ---------------- 权限 ----------------

    private fun ensureAudioPermission(onGranted: () -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            onGranted()
            return
        }
        requestPermissions(arrayOf(permission), REQ_AUDIO)
        pendingAfterPermission = onGranted
    }

    private var pendingAfterPermission: (() -> Unit)? = null

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission?.invoke()
            loadLocalCount()
        } else {
            Tip.toast(requireContext(), getString(R.string.permission_storage_needed))
        }
        pendingAfterPermission = null
    }

    override fun onDestroyView() {
        Log.d("MineFragment", "onDestroyView")
        DownloadManager.removeListener(downloadListener)
        Store.removeListener(storeListener)
        super.onDestroyView()
    }

    companion object {
        private const val REQ_AUDIO = 2001
    }
}
