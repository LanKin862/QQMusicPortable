package com.portable.qqmusic.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.portable.qqmusic.BuildConfig
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.PlayFade
import com.portable.qqmusic.core.Quality
import com.portable.qqmusic.core.Settings
import com.portable.qqmusic.core.StorageLocation
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.CacheManager
import com.portable.qqmusic.data.DownloadManager
import com.portable.qqmusic.data.LocalMeta
import com.portable.qqmusic.data.LocalMusic
import com.portable.qqmusic.data.api.Login
import com.portable.qqmusic.player.PlayerController

/**
 * 设置页。
 *
 * 分类结构:
 *  - 账号: QQ 音乐二维码登录 (持久化) / 退出登录
 *  - 播放与下载
 *      - 音质设置: 在线播放音质 (标准 / HQ / SQ / Hi-Res)、下载音质
 *      - 播放设置: 播放过渡效果 (无缝 / 淡入淡出)、音量平衡、响度自适应
 *      - 音乐缓存上限
 *      - 歌曲下载存储位置
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_prefs, rootKey)

            bindAccount()
            bindQuality()
            bindPlayback()
            bindStorage()
            bindAbout()
        }

        override fun onResume() {
            super.onResume()
            // 从登录页返回后刷新账号态
            refreshAccountSummary()
            refreshCacheSummary()
        }

        // ---------------- 账号 ----------------

        private fun bindAccount() {
            findPreference<Preference>("account_login")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
                true
            }
            findPreference<Preference>("account_import_cookie")?.setOnPreferenceClickListener {
                showImportCookieDialog()
                true
            }
            findPreference<Preference>("account_logout")?.setOnPreferenceClickListener {
                if (!Login.isLoggedIn()) {
                    toast("当前未登录")
                    return@setOnPreferenceClickListener true
                }
                Login.logout()
                PlayerController.clearQueue()
                toast(getString(R.string.action_confirm) + "已退出登录")
                refreshAccountSummary()
                true
            }
            refreshAccountSummary()
        }

        private fun refreshAccountSummary() {
            val pref = findPreference<Preference>("account_login") ?: return
            pref.summary = if (Login.isLoggedIn()) {
                val nick = Login.nick().ifEmpty { "QQ 音乐用户" }
                "$nick (${Login.uin()})" + if (Login.isVip) " · 会员" else ""
            } else {
                getString(R.string.login_now)
            }
            findPreference<Preference>("account_logout")?.isEnabled = Login.isLoggedIn()
        }

        /**
         * 手动导入 Cookie 对话框。
         *
         * 注意: 不能用 setMessage 放大段说明 —— Cookie 很长, 输入框本身就要占地方,
         * 两者叠加会把「确定」按钮挤出屏幕。这里改成:
         *  - 不给 message, 获取步骤放在设置项的 summary 里
         *  - 输入框放进固定高度(约 140dp)的 ScrollView, 内容多了内部滚动
         */
        private fun showImportCookieDialog() {
            val ctx = requireContext()
            val density = resources.displayMetrics.density
            val pad = (14 * density).toInt()

            val input = android.widget.EditText(ctx).apply {
                hint = getString(R.string.settings_import_cookie_hint)
                setPadding(pad, pad, pad, pad)
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                isSingleLine = false
                textSize = 12f
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setTextIsSelectable(true)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val scroll = android.widget.ScrollView(ctx).apply {
                addView(input)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    (140 * density).toInt()
                )
            }

            val wrap = android.widget.FrameLayout(ctx).apply {
                setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
                addView(scroll)
            }

            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_import_cookie)
                .setView(wrap)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm) { _, _ ->
                    val raw = input.text.toString()
                    Task.io {
                        val (ok, msg) = Login.importCookie(raw)
                        Task.main {
                            if (!isAdded) return@main
                            toast(msg)
                            if (ok) refreshAccountSummary()
                        }
                    }
                }
                .show()
        }

        // ---------------- 音质 ----------------

        private fun bindQuality() {
            val online = findPreference<ListPreference>("quality_online")
            online?.apply {
                entries = Quality.entries.map { it.label }.toTypedArray()
                entryValues = Quality.entries.map { it.name }.toTypedArray()
                value = Settings.onlineQuality().name
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    val q = Quality.fromPref(newValue as? String)
                    Settings.setOnlineQuality(q)
                    toast("在线播放音质: ${q.label}")
                    true
                }
            }

            val download = findPreference<ListPreference>("quality_download")
            download?.apply {
                entries = Quality.entries.map { it.label }.toTypedArray()
                entryValues = Quality.entries.map { it.name }.toTypedArray()
                value = Settings.downloadQuality().name
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    val q = Quality.fromPref(newValue as? String)
                    Settings.setDownloadQuality(q)
                    toast("下载音质: ${q.label}")
                    true
                }
            }
        }

        // ---------------- 播放 ----------------

        private fun bindPlayback() {
            val fade = findPreference<ListPreference>("play_fade")
            fade?.apply {
                entries = PlayFade.entries.map { it.label }.toTypedArray()
                entryValues = PlayFade.entries.map { it.name }.toTypedArray()
                value = Settings.playFade().name
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    val f = PlayFade.fromPref(newValue as? String)
                    Settings.setPlayFade(f)
                    PlayerController.onFadeSettingChanged()
                    toast("播放过渡效果: ${f.label}")
                    true
                }
            }

            findPreference<SwitchPreferenceCompat>("volume_balance")?.apply {
                isChecked = Settings.volumeBalance()
                setOnPreferenceChangeListener { _, newValue ->
                    Settings.setVolumeBalance(newValue as Boolean)
                    true
                }
            }

            findPreference<SwitchPreferenceCompat>("loudness_adapt")?.apply {
                isChecked = Settings.loudnessAdapt()
                setOnPreferenceChangeListener { _, newValue ->
                    Settings.setLoudnessAdapt(newValue as Boolean)
                    true
                }
            }
        }

        // ---------------- 存储 ----------------

        private fun bindStorage() {
            val limit = findPreference<ListPreference>("cache_limit")
            limit?.apply {
                val options = Settings.CACHE_OPTIONS
                entries = options.map { Settings.cacheLimitLabel(it) }.toTypedArray()
                entryValues = options.map { it.toString() }.toTypedArray()
                value = Settings.cacheLimitMb().toString()
                setOnPreferenceChangeListener { _, newValue ->
                    val mb = (newValue as? String)?.toIntOrNull() ?: Settings.DEFAULT_CACHE_MB
                    Settings.setCacheLimitMb(mb)
                    Task.io {
                        CacheManager.enforceLimit(requireContext().applicationContext)
                        CacheManager.refreshUsage(requireContext().applicationContext)
                        Task.main { refreshCacheSummary() }
                    }
                    true
                }
            }
            bindCacheTier("cache_image_mb", Settings.cacheImageMb(), Settings::setCacheImageMb)
            bindCacheTier("cache_meta_mb", Settings.cacheMetaMb(), Settings::setCacheMetaMb)
            bindCacheTier("cache_lyric_mb", Settings.cacheLyricMb(), Settings::setCacheLyricMb)

            refreshCacheSummary()

            findPreference<Preference>("cache_clear")?.setOnPreferenceClickListener {
                Task.io {
                    val freed = CacheManager.clear(requireContext().applicationContext)
                    Task.main {
                        toast(
                            getString(
                                R.string.cache_cleared,
                                android.text.format.Formatter.formatShortFileSize(requireContext(), freed)
                            )
                        )
                        refreshCacheSummary()
                    }
                }
                true
            }

            /*
             * 本地歌曲批量匹配。
             *
             * 平时是「用到哪首才匹配哪首」, 但那样封面要等进到播放页才出来、歌词更是
             * 得先进歌词页, 用户会觉得「没生效」。这里给一个手工入口, 一次把全部本地歌
             * 都过一遍 —— 结果落进 MetaCache, 之后播哪首都直接命中。
             */
            findPreference<Preference>("local_meta_match")?.apply {
                setOnPreferenceClickListener {
                    if (matching) return@setOnPreferenceClickListener true
                    startLocalMatch()
                    true
                }
            }

            val location = findPreference<ListPreference>("download_location")
            location?.apply {
                entries = StorageLocation.entries.map { it.label }.toTypedArray()
                entryValues = StorageLocation.entries.map { it.name }.toTypedArray()
                value = Settings.downloadLocation().name
                setOnPreferenceChangeListener { _, newValue ->
                    val loc = StorageLocation.fromPref(newValue as? String)
                    Settings.setDownloadLocation(loc)
                    // 目录变更后重新扫描
                    Task.io {
                        DownloadManager.scanDownloaded(requireContext().applicationContext)
                    }
                    updateLocationSummary(loc)
                    true
                }
                updateLocationSummary(Settings.downloadLocation())
            }
        }

        private fun updateLocationSummary(loc: StorageLocation) {
            val pref = findPreference<ListPreference>("download_location") ?: return
            val path = com.portable.qqmusic.core.Storage.displayPath(requireContext())
            pref.summary = "${loc.label}\n$path"
        }

        /**
         * 缓存占用按层展示。
         *
         * 分开显示是因为清理策略是有序的: 空间不够时先删歌曲缓存, 再删封面,
         * 最后才动歌单数据 —— 用户能直接从这一行看出哪层在被吃空间。
         */
        private fun refreshCacheSummary() {
            val pref = findPreference<ListPreference>("cache_limit") ?: return
            val limit = Settings.cacheLimitLabel(Settings.cacheLimitMb())
            val ctx = requireContext().applicationContext
            com.portable.qqmusic.core.Task.io {
                val byTier = com.portable.qqmusic.data.CacheManager.usedBytesByTier(ctx)
                val audio = byTier[com.portable.qqmusic.data.CacheManager.Tier.AUDIO] ?: 0L
                val image = byTier[com.portable.qqmusic.data.CacheManager.Tier.IMAGE] ?: 0L
                val meta = byTier[com.portable.qqmusic.data.CacheManager.Tier.META] ?: 0L
                val lyric = byTier[com.portable.qqmusic.data.CacheManager.Tier.LYRIC] ?: 0L
                com.portable.qqmusic.core.Task.main {
                    if (!isAdded) return@main
                    pref.summary = "上限 $limit · 已用 ${fmtSize(audio + image + meta + lyric)}\n" +
                        "歌曲 ${fmtSize(audio)} · 封面 ${fmtSize(image)} · " +
                        "歌单 ${fmtSize(meta)} · 歌词 ${fmtSize(lyric)}"
                }
            }
        }

        /**
         * 绑定一个分层配额项。
         *
         * 与总上限不同: 这几层只在**自己超配额**时才删自己最旧的文件, 总量超了轮不到它们
         * (歌曲缓存先被牺牲)。所以改完只需要重跑一次 enforceLimit, 不用清库。
         */
        private fun bindCacheTier(key: String, current: Int, apply: (Int) -> Unit) {
            val pref = findPreference<ListPreference>(key) ?: return
            val options = Settings.CACHE_TIER_OPTIONS
            pref.entries = options.map { Settings.cacheLimitLabel(it) }.toTypedArray()
            pref.entryValues = options.map { it.toString() }.toTypedArray()
            pref.value = current.toString()
            pref.summary = Settings.cacheLimitLabel(current)
            pref.setOnPreferenceChangeListener { _, newValue ->
                val mb = (newValue as? String)?.toIntOrNull() ?: Settings.DEFAULT_TIER_MB
                apply(mb)
                pref.summary = Settings.cacheLimitLabel(mb)
                Task.io {
                    CacheManager.enforceLimit(requireContext().applicationContext)
                    CacheManager.refreshUsage(requireContext().applicationContext)
                    Task.main { refreshCacheSummary() }
                }
                true
            }
        }

        /**
         * 字节数转人话。
         *
         * 原来是把字节先除成整 MB 再拼字符串, 结果译文这类小文件一律显示「0 MB」,
         * 看起来像统计坏了。不足 1 MB 的走 KB, 不足 1 GB 的标一位小数。
         */
        private fun fmtSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
            bytes >= 1024L -> "%d KB".format(bytes / 1024L)
            else -> "$bytes B"
        }

        // ---------------- 关于 ----------------

        private fun bindAbout() {
            findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
        }

        /** 批量匹配跑了多长, 防止连点又起一份 */
    private var matching = false

    /**
     * 把本地歌一次性全过一遍。
     *
     * 只做「借信息」这一件事：搜索 + 落盘。切到界面刷新是多余的 ——
     * 下次进播放页/歌词页时 `LocalMeta.cached` 会命中, 自然就带出来了。
     */
    private fun startLocalMatch() {
        val app = requireContext().applicationContext
        val pref = findPreference<Preference>("local_meta_match") ?: return
        matching = true
        pref.isEnabled = false
        pref.summary = getString(R.string.local_meta_running)
        toast(getString(R.string.local_meta_running))
        Task.io {
            val songs = try {
                LocalMusic.query(app, 5000)
            } catch (t: Throwable) {
                Log.w("Settings.localMatch", t)
                emptyList()
            }
            if (songs.isEmpty()) {
                Task.main {
                    matching = false
                    pref.isEnabled = true
                    pref.summary = getString(R.string.local_meta_match_hint)
                    toast(getString(R.string.local_meta_empty))
                }
                return@io
            }
            var ok = 0
            var done = 0
            for (song in songs) {
                if (!song.isLocal) continue
                done++
                if (done % 5 == 0 || done == songs.size) {
                    val text = getString(R.string.local_meta_running) + " " + done + "/" + songs.size
                    Task.main { pref.summary = text }
                }
                // 已经匹配过的直接跳过, 重跑一次也不会把几十首歌重搜一遍
                if (LocalMeta.cached(app, song) != null) {
                    ok++
                    continue
                }
                if (LocalMeta.resolveBlocking(app, song) != null) ok++
            }
            Task.main {
                matching = false
                pref.isEnabled = true
                pref.summary = getString(R.string.local_meta_match_hint)
                toast(getString(R.string.local_meta_done, ok, songs.size))
                Log.d("Settings.localMatch", "批量匹配完成 $ok/${songs.size}")
            }
        }
    }

    private fun toast(msg: String) {
            if (!isAdded) return
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            Log.d("Settings", msg)
        }
    }
}
