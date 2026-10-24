package com.portable.qqmusic.core

/**
 * 设置读写门面。
 *
 * 所有设置项集中在这里, 提供默认值 + 类型安全访问,
 * 并在写入时通知监听者 (播放器 / 界面) 实时生效。
 */
object Settings {

    // ---------------- 音质 ----------------

    /** 在线播放音质 */
    fun onlineQuality(): Quality =
        Quality.fromPref(Store.str(Store.K.QUALITY_ONLINE, Quality.STANDARD.name), Quality.STANDARD)

    fun setOnlineQuality(q: Quality) = Store.putStr(Store.K.QUALITY_ONLINE, q.name)

    /** 下载音质 */
    fun downloadQuality(): Quality =
        Quality.fromPref(Store.str(Store.K.QUALITY_DOWNLOAD, Quality.SQ.name), Quality.SQ)

    fun setDownloadQuality(q: Quality) = Store.putStr(Store.K.QUALITY_DOWNLOAD, q.name)

    // ---------------- 播放 ----------------

    /** 播放过渡效果 */
    fun playFade(): PlayFade = PlayFade.fromPref(Store.str(Store.K.PLAY_FADE, PlayFade.SEAMLESS.name))

    fun setPlayFade(f: PlayFade) = Store.putStr(Store.K.PLAY_FADE, f.name)

    /** 音量平衡: 对不同歌曲做响度归一化, 避免忽大忽小 */
    fun volumeBalance(): Boolean = Store.bool(Store.K.VOLUME_BALANCE, false)

    fun setVolumeBalance(v: Boolean) = Store.putBool(Store.K.VOLUME_BALANCE, v)

    /** 响度自适应: 跟随环境/设备动态微调增益 */
    fun loudnessAdapt(): Boolean = Store.bool(Store.K.LOUDNESS_ADAPT, false)

    fun setLoudnessAdapt(v: Boolean) = Store.putBool(Store.K.LOUDNESS_ADAPT, v)

    // ---------------- 存储 ----------------

    /**
     * 缓存上限 (MB): 歌单数据 + 封面 + 歌词三者之和, 0 表示不限制。
     *
     * 歌曲缓存不再计入, 也不再单独设上限 —— 它在每次启动时清掉, 只留当前播放那一首。
     */
    fun cacheDataMb(): Int = Store.int(Store.K.CACHE_DATA_MB, DEFAULT_DATA_MB)

    fun setCacheDataMb(mb: Int) = Store.putInt(Store.K.CACHE_DATA_MB, mb)

    fun downloadLocation(): StorageLocation = Storage.location()

    fun setDownloadLocation(loc: StorageLocation) = Store.putStr(Store.K.DOWNLOAD_DIR, loc.name)

    const val DEFAULT_DATA_MB = 300

    /** 缓存上限候选项 (MB) */
    val CACHE_DATA_OPTIONS = intArrayOf(100, 200, 300, 500, 1024, 2048, 0)

    fun cacheLimitLabel(mb: Int): String =
        if (mb <= 0) "不限制" else if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

    // ---------------- 播放器状态 ----------------

    fun playMode(): PlayMode = PlayMode.fromPref(Store.str(Store.K.PLAY_MODE, PlayMode.LIST_LOOP.name))

    fun setPlayMode(m: PlayMode) = Store.putStr(Store.K.PLAY_MODE, m.name)

    fun queueKind(): QueueKind = QueueKind.fromPref(Store.str(Store.K.QUEUE_KIND, QueueKind.NORMAL.name))

    fun setQueueKind(k: QueueKind) = Store.putStr(Store.K.QUEUE_KIND, k.name)

    fun lastSong(): String = Store.str(Store.K.LAST_SONG)

    fun setLastSong(json: String) = Store.putStr(Store.K.LAST_SONG, json)

    fun lastIndex(): Int = Store.int(Store.K.LAST_INDEX, 0)

    fun setLastIndex(i: Int) = Store.putInt(Store.K.LAST_INDEX, i)

    // ---------------- 界面 ----------------

    fun tabIndex(): Int = Store.int(Store.K.TAB_INDEX, 0)

    fun setTabIndex(i: Int) = Store.putInt(Store.K.TAB_INDEX, i)

    /**
     * 某个歌单上次用的排序方式。
     *
     * [scope] 是歌单的身份串 (见 `PlaylistActivity.sortScope()`), 每个歌单各记一份,
     * 退出重进或换一个歌单都不会互相影响。0 = 默认顺序。
     */
    fun playlistSort(scope: String): Int = Store.int("sort_$scope", 0)

    fun setPlaylistSort(scope: String, mode: Int) = Store.putInt("sort_$scope", mode)

    // ---------------- 缓存占用 ----------------

    /** 读取当前缓存占用 (MB), 由 [com.portable.qqmusic.data.CacheManager] 刷新 */
    fun cacheUsedMb(): Int = Store.int("cache_used_mb", 0)

    fun setCacheUsedMb(mb: Int) = Store.putInt("cache_used_mb", mb)
}
