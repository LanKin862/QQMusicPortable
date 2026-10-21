package com.portable.qqmusic.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/**
 * 统一持久化存储。
 *
 * - 使用单一 SharedPreferences 文件, 避免多文件带来的磁盘 IO 与内存副本
 * - apply() 异步落盘, 不阻塞 UI
 * - 提供轻量监听, 设置页改动后主界面即时生效
 */
object Store {

    private const val FILE = "qqm_store"

    private lateinit var sp: SharedPreferences
    private val listeners = LinkedHashSet<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun addListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun notifyChanged(key: String) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        if (snapshot.isEmpty()) return
        main.post { snapshot.forEach { it(key) } }
    }

    // ---------------- 基础读写 ----------------

    fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def

    fun putStr(key: String, value: String) {
        sp.edit().putString(key, value).apply()
        notifyChanged(key)
    }

    fun int(key: String, def: Int = 0): Int = sp.getInt(key, def)

    fun putInt(key: String, value: Int) {
        sp.edit().putInt(key, value).apply()
        notifyChanged(key)
    }

    fun long(key: String, def: Long = 0L): Long = sp.getLong(key, def)

    fun putLong(key: String, value: Long) {
        sp.edit().putLong(key, value).apply()
        notifyChanged(key)
    }

    fun bool(key: String, def: Boolean = false): Boolean = sp.getBoolean(key, def)

    fun putBool(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
        notifyChanged(key)
    }

    fun remove(key: String) {
        sp.edit().remove(key).apply()
        notifyChanged(key)
    }

    fun clearAll() {
        sp.edit().clear().apply()
    }

    // ---------------- 业务键 ----------------

    object K {
        // 登录态
        const val COOKIE = "cookie"
        const val UIN = "uin"
        const val NICK = "nick"
        const val AVATAR = "avatar"
        const val VIP = "vip_type"
        const val LOGIN_TIME = "login_time"

        // 音质
        const val QUALITY_ONLINE = "quality_online"
        const val QUALITY_DOWNLOAD = "quality_download"

        // 播放
        const val PLAY_FADE = "play_fade"                 // 0 无缝 1 淡入淡出
        const val VOLUME_BALANCE = "volume_balance"       // 音量平衡
        const val LOUDNESS_ADAPT = "loudness_adapt"       // 响度自适应

        // 存储
        const val CACHE_LIMIT_MB = "cache_limit_mb"
        const val CACHE_IMAGE_MB = "cache_image_mb"
        const val CACHE_META_MB = "cache_meta_mb"
        const val CACHE_LYRIC_MB = "cache_lyric_mb"
        const val DOWNLOAD_DIR = "download_dir"

        // 播放器状态
        const val LAST_SONG = "last_song"
        const val LAST_INDEX = "last_index"
        const val PLAY_MODE = "play_mode"
        const val QUEUE_KIND = "queue_kind"               // 队列来源 (普通歌单 / 猜你喜欢)

        // 界面
        const val TAB_INDEX = "tab_index"

        /** 搜索页的历史记录, 多条用 \n 分隔 (新的在前) */
        const val SEARCH_HISTORY = "search_history"

        /**
         * 「我的」页歌单列表的失效标记。
         *
         * 收藏 / 取消收藏歌单后自增一次, [com.portable.qqmusic.ui.MineFragment]
         * 监听到这个键变化就绕过缓存重新拉一次 —— 否则从歌单详情页取消收藏后
         * 退回「我的」还看得见那张歌单。
         */
        const val LIBRARY_REV = "library_rev"
    }
}
