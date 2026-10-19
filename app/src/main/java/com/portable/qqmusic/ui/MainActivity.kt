package com.portable.qqmusic.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Settings
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.CacheManager
import com.portable.qqmusic.data.DownloadManager
import com.portable.qqmusic.data.LocalMusic
import com.portable.qqmusic.data.api.Login

/**
 * 主页容器: 底部两个导航 (推荐 / 我的) + 常驻迷你播放条。
 *
 * 两个 Fragment 使用 show/hide 切换而非 replace, 保留各自的滚动位置与已加载数据,
 * 切换导航时不会触发重新请求。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var recommendFragment: Fragment
    private lateinit var mineFragment: Fragment
    private lateinit var miniPlayer: MiniPlayerController

    private var currentTab = 0

    /** 上一次 onResume 见到的账号, 用来识别「刚登录/刚登出」 */
    private var lastUin = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.i(TAG, "=== MainActivity.onCreate 开始 (savedInstanceState=${savedInstanceState != null}) ===")

        try {
            Diag.i(TAG, "setContentView(activity_main) ...")
            setContentView(R.layout.activity_main)
            Diag.i(TAG, "setContentView ok")

            Diag.i(TAG, "findViewById(bottom_nav) ...")
            bottomNav = findViewById(R.id.bottom_nav)
            Diag.i(TAG, "bottom_nav ok")

            Diag.i(TAG, "MiniPlayerController 初始化 ...")
            // 注意: 必须传迷你播放条自身 (mini_player), 不能传根布局 (root)。
            // 曾经误传 root, 而 MiniPlayerController 在队列为空时会把目标设为 GONE,
            // 结果整个界面被隐藏 -> 全屏白屏。
            miniPlayer = MiniPlayerController(findViewById(R.id.mini_player)) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
            }
            Diag.i(TAG, "MiniPlayerController ok")

            setupFragments()
            Diag.i(TAG, "setupFragments ok, currentTab=$currentTab")

            setupNavigation()
            Diag.i(TAG, "setupNavigation ok")

            // 首屏的账号状态已经由各 Fragment 自己加载过, 记下来避免 onResume 再强制刷一遍
            lastUin = if (Login.isLoggedIn()) Login.uin() else ""

            // 后台预热: 缓存统计 + 下载目录扫描 + 本地音乐数量, 不阻塞启动
            Task.io {
                try {
                    CacheManager.enforceLimit(this)
                    CacheManager.refreshUsage(this)
                    DownloadManager.scanDownloaded(this)
                } catch (t: Throwable) {
                    Diag.e(TAG, "后台预热失败", t)
                }
            }

            requestNotificationPermissionIfNeeded()
            Diag.watchMainThread(3000)
            Diag.i(TAG, "=== MainActivity.onCreate 完成 ===")

            // 把真实 View 树写进日志: 用于判断「setContentView 成功但界面空白」这类问题
            dumpViewTreeDiagnostic()
        } catch (t: Throwable) {
            Diag.e(TAG, "MainActivity.onCreate 抛异常", t)
            showStartupError(t)
        }
    }

    /** 延迟一帧后 dump 视图树, 此时布局已完成测量 */
    private fun dumpViewTreeDiagnostic() {
        window.decorView.post {
            try {
                val sb = StringBuilder()
                val root = findViewById<android.view.View>(R.id.root)
                sb.append("root(R.id.root)=${root != null}")
                if (root != null) {
                    sb.append(" width=${root.width} height=${root.height}")
                    sb.append(" visibility=${root.visibility}")
                    sb.append(" childCount=${(root as? android.view.ViewGroup)?.childCount}")
                }
                val container = findViewById<android.view.View>(R.id.content_container)
                sb.append(" | content_container childCount=${(container as? android.view.ViewGroup)?.childCount}")
                sb.append(" w=${container?.width} h=${container?.height} vis=${container?.visibility}")
                val nav = findViewById<android.view.View>(R.id.bottom_nav)
                sb.append(" | bottom_nav w=${nav?.width} h=${nav?.height} vis=${nav?.visibility}")
                val mini = findViewById<android.view.View>(R.id.mini_player)
                sb.append(" | mini_player vis=${mini?.visibility}")
                Diag.i(TAG, "视图尺寸: $sb")

                Diag.i(TAG, "View 树: ${describeTree(window.decorView, 0)}")
            } catch (t: Throwable) {
                Diag.e(TAG, "dump 视图树失败", t)
            }
        }
    }

    private fun describeTree(v: android.view.View, depth: Int): String {
        if (depth > 5) return "..."
        val name = v.javaClass.simpleName
        val id = try {
            if (v.id != android.view.View.NO_ID) resources.getResourceEntryName(v.id) else "-"
        } catch (_: Throwable) {
            "?"
        }
        val sb = StringBuilder()
        sb.append("\n").append("  ".repeat(depth))
            .append(name).append("#").append(id)
            .append(" ").append(v.width).append("x").append(v.height)
        if (v.visibility != android.view.View.VISIBLE) sb.append(" vis=").append(v.visibility)
        if (v is android.view.ViewGroup) {
            for (i in 0 until minOf(v.childCount, 8)) {
                sb.append(describeTree(v.getChildAt(i), depth + 1))
            }
            if (v.childCount > 8) sb.append("\n").append("  ".repeat(depth + 1)).append("...共").append(v.childCount).append("个子视图")
        }
        return sb.toString()
    }

    /**
     * 启动失败时不要留白屏: 直接把异常显示在界面上, 便于现场排查。
     * (Flyme 等 ROM 关闭了 logcat, 只靠日志无法定位。)
     */
    private fun showStartupError(t: Throwable) {
        try {
            val tv = android.widget.TextView(this).apply {
                text = "启动失败:\n\n" + Diag.throwableToString(t)
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#FF333333"))
                setPadding(32, 64, 32, 32)
                setTextIsSelectable(true)
            }
            val scroll = android.widget.ScrollView(this).apply { addView(tv) }
            scroll.setBackgroundColor(android.graphics.Color.WHITE)
            setContentView(scroll)
            Diag.i(TAG, "已把异常显示到界面")
        } catch (t2: Throwable) {
            Diag.e(TAG, "显示异常信息也失败了", t2)
        }
    }

    private fun setupFragments() {
        val fm = supportFragmentManager
        recommendFragment = fm.findFragmentByTag(TAG_RECOMMEND) ?: RecommendFragment()
        mineFragment = fm.findFragmentByTag(TAG_MINE) ?: MineFragment()
        Diag.i(TAG, "fragments: recommend.isAdded=${recommendFragment.isAdded} mine.isAdded=${mineFragment.isAdded}")

        try {
            fm.beginTransaction().apply {
                if (!recommendFragment.isAdded) add(R.id.content_container, recommendFragment, TAG_RECOMMEND)
                if (!mineFragment.isAdded) add(R.id.content_container, mineFragment, TAG_MINE)
            }.commitNow()
            Diag.i(TAG, "fragment 添加完成")
        } catch (t: Throwable) {
            Diag.e(TAG, "fragment 添加失败", t)
            throw t
        }

        currentTab = Settings.tabIndex().coerceIn(0, 1)
        Diag.i(TAG, "tabIndex=$currentTab, 准备 showTab")
        showTab(currentTab, initial = true)
        Diag.i(TAG, "showTab 完成")
    }

    private fun setupNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            val tab = when (item.itemId) {
                R.id.nav_recommend -> 0
                R.id.nav_mine -> 1
                else -> return@setOnItemSelectedListener false
            }
            showTab(tab, initial = false)
            true
        }
        bottomNav.selectedItemId =
            if (currentTab == 0) R.id.nav_recommend else R.id.nav_mine
    }

    private fun showTab(tab: Int, initial: Boolean) {
        currentTab = tab
        val fm = supportFragmentManager
        val target = if (tab == 0) recommendFragment else mineFragment
        val other = if (tab == 0) mineFragment else recommendFragment
        Diag.i(TAG, "showTab tab=$tab initial=$initial target.isAdded=${target.isAdded}")

        val tx = fm.beginTransaction().apply {
            if (other.isAdded) hide(other)
            if (!target.isAdded) add(R.id.content_container, target, if (tab == 0) TAG_RECOMMEND else TAG_MINE)
            show(target)
        }
        try {
            // 初始化阶段必须同步生效, 否则容器会保持空白 -> 白屏
            if (initial) tx.commitNow() else tx.commitAllowingStateLoss()
            Diag.i(TAG, "showTab 事务已提交 (initial=$initial)")
        } catch (t: Throwable) {
            Diag.e(TAG, "showTab 事务失败", t)
            throw t
        }

        if (!initial) Settings.setTabIndex(tab)

        // 切到「我的」页时刷新账号与歌单状态。
        // 初始化阶段不同步调用: 此时目标 Fragment 的视图可能还没创建,
        // 强制刷新会触发 lateinit 未初始化的崩溃 (曾经的真实 crash)。
        if (tab == 1 && !initial) {
            (mineFragment as? MineFragment)?.onShown()
        }
    }

    override fun onResume() {
        super.onResume()
        // 若 onCreate 中途失败, miniPlayer 可能是未初始化状态, 这里必须保护
        if (::miniPlayer.isInitialized) {
            miniPlayer.refreshAvailability()
        }
        // 从登录页 / 详情页(删歌、下载) / 设置页返回时, 账号、歌单、本地数量都可能已经变了。
        // 以前只有手动切到「我的」tab 才刷新, 于是登录完回来还是「未登录」的样子。
        if (::mineFragment.isInitialized) {
            (mineFragment as? MineFragment)?.onShown()
        }
        if (::recommendFragment.isInitialized) {
            val uin = if (Login.isLoggedIn()) Login.uin() else ""
            if (uin != lastUin) {
                lastUin = uin
                // 每日推荐 / 猜你喜欢都依赖登录态, 登入登出后要重新拉
                (recommendFragment as? RecommendFragment)?.reloadForAccountChange()
            }
        }
        // 从设置页返回时可能切换了音质/缓存上限
        Task.io {
            try {
                CacheManager.refreshUsage(this)
            } catch (t: Throwable) {
                Diag.e(TAG, "刷新缓存用量失败", t)
            }
        }
    }

    override fun onDestroy() {
        if (::miniPlayer.isInitialized) {
            miniPlayer.release()
        }
        super.onDestroy()
    }

    /** Android 13+ 需要显式申请通知权限, 否则播放控制通知不显示 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION
            )
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val TAG_RECOMMEND = "frag_recommend"
        private const val TAG_MINE = "frag_mine"
        private const val REQ_NOTIFICATION = 1001
    }
}
