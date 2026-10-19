package com.portable.qqmusic.data

import android.content.Context
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Settings
import com.portable.qqmusic.core.Storage
import com.portable.qqmusic.core.Task
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 音乐缓存管理。
 *
 * 播放时把音频流写到 cache 目录, 二次播放直接命中本地文件,
 * 既省流量也降低解码前的等待时间。
 *
 * 缓存以「服务端返回的文件名」为 key, 天然带音质信息 (F000/M800/M500 前缀),
 * 不同音质互不覆盖。
 */
object CacheManager {

    private val pending = ConcurrentHashMap<String, Boolean>()

    /** 每个文件的下载锁: 让后来者等前一个下完, 而不是直接放弃 */
    private val locks = ConcurrentHashMap<String, Any>()

    /** 缓存文件 (不保证存在) */
    fun fileFor(context: Context, fileName: String): File =
        File(Storage.cacheDir(context), fileName)

    fun cachedFile(context: Context, fileName: String): File? {
        if (fileName.isEmpty()) return null
        val f = fileFor(context, fileName)
        return if (f.exists() && f.length() > 0) f else null
    }

    /**
     * 把远程音频缓存到本地, 返回本地文件 (失败返回 null)。
     * 同一文件并发下载会去重。
     */
    fun cache(context: Context, url: String, fileName: String): File? {
        if (url.isEmpty() || fileName.isEmpty()) return null
        cachedFile(context, fileName)?.let { return it }

        if (pending.putIfAbsent(fileName, true) != null) return null
        try {
            val target = fileFor(context, fileName)
            val ok = com.portable.qqmusic.data.api.PlayUrlApi.downloadToFile(url, target, { _, _ -> })
            if (ok && target.exists()) {
                enforceLimit(context)
                return target
            }
        } catch (t: Throwable) {
            Log.w("CacheManager", t)
        } finally {
            pending.remove(fileName)
        }
        return null
    }

    /**
     * 同步把远程音频缓存到本地并返回文件; 失败返回 null。
     *
     * 与 [cache] 的区别: [cache] 发现同一文件已在下载就直接返回 null (丢给后台),
     * 而这里会**等**前一个下载结束再返回结果 —— 播放路径需要拿到确定的文件才能起播。
     * 调用方必须在 IO 线程。
     */
    fun cacheBlocking(context: Context, url: String, fileName: String): File? {
        if (url.isEmpty() || fileName.isEmpty()) return null
        cachedFile(context, fileName)?.let { return it }
        // 记下当前世代: 一旦用户在下载途中切了歌 (cancelDownloads 递增),
        // 这个下载就会自行退出, 不再占着 IO 线程往下拖。
        val generation = downloadGeneration
        val lock = locks.getOrPut(fileName) { Any() }
        synchronized(lock) {
            // 拿到锁后再查一次: 可能已经被别的线程下好了
            cachedFile(context, fileName)?.let { return it }
            return try {
                val target = fileFor(context, fileName)
                val ok = com.portable.qqmusic.data.api.PlayUrlApi.downloadToFile(
                    url, target, { _, _ -> }
                ) { generation != downloadGeneration }
                if (ok && target.exists() && target.length() > 0) {
                    enforceLimit(context)
                    target
                } else {
                    null
                }
            } catch (t: Throwable) {
                Log.w("CacheManager.cacheBlocking", t)
                null
            }
        }
    }

    /** 下载世代: [cancelDownloads] 递增它, 让还在跑的播放缓存下载放弃 */
    @Volatile
    private var downloadGeneration = 0

    /**
     * 取消此前为播放而起的缓存下载。
     *
     * 播放路径每次切歌都调用: Hi-Res/SQ 一首歌 90-150MB, 4 个 IO 线程一旦被
     * 这种下载占满, 后续切歌连解析直链都要排队, 表现出来就是"点了没反应"。
     */
    fun cancelDownloads() {
        downloadGeneration++
    }

    /**
     * 边下边播的暂存文件。
     *
     * 刻意放在 stream/ 子目录、文件名保留 .mp3/.flac, 而不是叫 xxx.mp3.part:
     * 这台 Flyme 机器的 MediaPlayer 会因为 .part 这种未知扩展名直接拒播
     * (onError what=-38, 48ms 内返回), 预缓冲攒了几十 MB 也照样失败。
     * 下载线程直接写这个文件, 写完整再 rename 到正式缓存目录。
     */
    fun partFile(context: Context, fileName: String): File {
        val dir = File(Storage.cacheDir(context), "stream")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName)
    }

    /**
     * 缓存分层。
     *
     * 清理时按声明顺序腾空间: 歌曲缓存最容易再拿到, 最先删; 歌单数据
     * (歌单列表/歌曲列表这类「相对固定」的内容) 重新拉取代价最高, 留到最后。
     */
    enum class Tier(val label: String) {
        AUDIO("歌曲缓存"),
        IMAGE("封面缓存"),
        META("歌单数据"),
        LYRIC("歌词译文")
    }

    /** 各层目录; 一层可能有多个目录 (音频的正式目录 + stream 暂存, 图片的本地层 + Glide 层) */
    private fun tierDirs(context: Context): Map<Tier, List<File>> = mapOf(
        Tier.AUDIO to listOf(File(Storage.audioDir(context), "stream"), Storage.audioDir(context)),
        Tier.IMAGE to listOf(Storage.imageDir(context), glideDiskDir(context)),
        Tier.META to listOf(Storage.metaDir(context)),
        Tier.LYRIC to listOf(Storage.lyricDir(context))
    )

    private fun listFilesOf(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        val out = ArrayList<File>()
        dir.listFiles()?.forEach { f -> if (f.isFile) out.add(f) }
        return out
    }

    /**
     * Glide 自带的磁盘缓存目录 (封面)。
     *
     * 没有为它注册 AppGlideModule: 改动构建链 (引入 kapt) 的代价比收益大。
     * 这里把它整体纳入图片层统计, 清理时整个目录删掉, Glide 会自行重建。
     */
    private fun glideDiskDir(context: Context): File =
        File(context.cacheDir, "image_manager_disk_cache")

    private fun tierFiles(context: Context): Map<Tier, List<File>> =
        tierDirs(context).mapValues { (_, dirs) -> dirs.flatMap { listFilesOf(it) } }

    /** 当前缓存占用 (字节) */
    fun usedBytes(context: Context): Long =
        tierFiles(context).values.sumOf { files -> files.sumOf { it.length() } }

    /** 各层占用 (字节), 供设置页展示 */
    fun usedBytesByTier(context: Context): Map<Tier, Long> =
        tierFiles(context).mapValues { (_, files) -> files.sumOf { it.length() } }

    /**
     * 边下边播的下载句柄。
     *
     * 拿到句柄后 [awaitReady] 等到预缓冲攒够, 就能把 [partFile] 当普通本地文件
     * 交给 MediaPlayer —— 解码线程只读本地文件, 完全不碰网络 (这台 Flyme 机器的
     * NuPlayer 读 MediaDataSource 只有 ~250ms 耐心, 走不通)。
     */
    class StreamHandle(
        val partFile: File,
        private val ready: java.util.concurrent.CountDownLatch,
        @Volatile var failed: Boolean = false
    ) {
        /** 等到预缓冲完成; false 表示下载失败或超时 */
        fun awaitReady(timeoutMs: Long): Boolean =
            ready.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && !failed
    }

    /**
     * 起一个后台下载, 立即返回句柄。
     *
     * 下载线程直接写 stream/ 下的暂存文件 (先按 [totalSize] 预分配, 这样播放器
     * 打开还没下完的文件也能算出正确时长, 不会把开头的几十秒当成整首歌),
     * 攒够 [readyThreshold] 就放行播放, 全部写完再 rename 成正式缓存文件。
     */
    fun downloadInBackground(
        context: Context,
        url: String,
        fileName: String,
        totalSize: Long = -1L
    ): StreamHandle {
        val part = partFile(context, fileName)
        val ready = java.util.concurrent.CountDownLatch(1)
        val handle = StreamHandle(part, ready)
        if (url.isEmpty() || fileName.isEmpty()) {
            handle.failed = true
            ready.countDown()
            return handle
        }
        if (cachedFile(context, fileName) != null) {
            handle.failed = true
            ready.countDown()
            return handle
        }
        val threshold = readyThreshold(totalSize)
        val generation = downloadGeneration
        Task.io {
            try {
                val ok = com.portable.qqmusic.data.api.PlayUrlApi.downloadToFile(
                    url,
                    part,
                    { done, _ -> if (done >= threshold) ready.countDown() },
                    preallocate = totalSize,
                    tmpSuffix = ""
                ) { generation != downloadGeneration }
                if (!ok) {
                    handle.failed = true
                    part.delete()
                } else {
                    val finalFile = fileFor(context, fileName)
                    if (finalFile.exists()) finalFile.delete()
                    val moved = part.renameTo(finalFile)
                    com.portable.qqmusic.core.Diag.i(
                        "CacheManager",
                        "边下边播完成 rename=$moved $fileName 落盘=${if (finalFile.exists()) finalFile.length() / 1024 else -1}KB"
                    )
                    enforceLimit(context)
                    refreshUsage(context)
                }
            } catch (t: Throwable) {
                Log.w("CacheManager.downloadInBackground", t)
                handle.failed = true
            } finally {
                ready.countDown()
            }
        }
        return handle
    }

    /**
     * 起播前要攒够的字节数。
     *
     * 起播优先: 攒到这么多就先开声, 后面不够时 [com.portable.qqmusic.player.GrowingFileDataSource]
     * 的 readAt 会按需阻塞等待(上限 45s), 所以阈值调低并不会变成"刚开声就断流"。
     *
     * 历史: 原先是「文件 5%, 夹在 512KB ~ 4MB」。Hi-Res 动辄 90MB, 5% 直接被夹到 4MB,
     * 4G 上要下 3~10 秒才能起播 —— 那 2~3 秒空档主要就是这么来的。
     */
    private fun readyThreshold(totalSize: Long): Long {
        val byFraction = if (totalSize > 0L) totalSize / 40 else 0L
        return byFraction.coerceIn(MIN_PREBUFFER_BYTES, MAX_PREBUFFER_BYTES)
    }

    /** 约 1.5 秒的 128kbps, 够解码器吃到首帧 */
    private const val MIN_PREBUFFER_BYTES = 192L * 1024
    private const val MAX_PREBUFFER_BYTES = 1L * 1024 * 1024

    /** 当前缓存占用 (字节) */
    fun refreshUsage(context: Context) {
        Task.io {
            val mb = (usedBytes(context) / 1024 / 1024).toInt()
            Settings.setCacheUsedMb(mb)
        }
    }

    /**
     * 按上限清理缓存。
     *
     * 顺序: 先歌曲缓存, 再封面, 最后歌单数据; 每层内部**按最后修改时间从旧到新**删。
     * 这样最有价值、最难重新获取的数据会被留到最后。
     */
    fun enforceLimit(context: Context) {
        val tiers = tierFiles(context)
        val now = System.currentTimeMillis()
        val freed = LinkedHashMap<Tier, Long>()

        fun oldestFirst(tier: Tier): List<File> =
            (tiers[tier] ?: emptyList()).sortedBy { it.lastModified() }

        /**
         * 删到 [tier] 的占用不超过 [quotaBytes]; 返回实际腾出的字节数。
         *
         * 单个文件按**最后修改时间从旧到新**删: 越久没碰过的越可能再也用不上。
         */
        fun trim(tier: Tier, quotaBytes: Long): Long {
            if (quotaBytes <= 0) return 0L   // <=0 表示这一层不限
            val files = oldestFirst(tier)
            var used = files.sumOf { it.length() }
            var got = 0L
            for (f in files) {
                if (used <= quotaBytes) break
                // 刚被写过的文件还在被播放器读 (边下边播的 stream/ 暂存文件),
                // 删掉会让正在播的这首歌当场断流, 所以留一个使用窗口。
                if (now - f.lastModified() < IN_USE_WINDOW_MS) continue
                val len = f.length()
                if (f.delete()) {
                    used -= len
                    got += len
                }
            }
            return got
        }

        // 第一轮: 各层自己的配额。译文和歌单重新拉取代价最高, 所以它们只受自己配额约束,
        // 不会被歌曲缓存把总量吃满而连带清掉。
        for (tier in listOf(Tier.IMAGE, Tier.META, Tier.LYRIC)) {
            val got = trim(tier, quotaBytes(tier))
            if (got > 0) freed[tier] = (freed[tier] ?: 0L) + got
        }

        // 第二轮: 总量超上限时按优先级清 —— 歌曲缓存最先, 其次封面, 歌单与译文最后。
        val limitMb = Settings.cacheLimitMb()
        if (limitMb > 0) {
            val limitBytes = limitMb.toLong() * 1024 * 1024
            var total = tiers.values.sumOf { files -> files.sumOf { it.length() } }
            for (tier in Tier.entries) {
                if (total <= limitBytes) break
                for (f in oldestFirst(tier)) {
                    if (total <= limitBytes) break
                    if (now - f.lastModified() < IN_USE_WINDOW_MS) continue
                    val len = f.length()
                    if (f.delete()) {
                        total -= len
                        freed[tier] = (freed[tier] ?: 0L) + len
                    }
                }
            }
        }

        if (freed.isNotEmpty()) {
            Diag.i(
                "CacheManager",
                "缓存清理 limit=${Settings.cacheLimitMb()}MB " +
                    freed.entries.joinToString(" ") { "${it.key.label}=${it.value / 1024 / 1024}MB" }
            )
        }
    }

    /**
     * 某一层的配额 (字节); 0 表示不限。
     *
     * 歌曲缓存不单独封顶 —— 它是「最先被牺牲」的那一层, 只吃总量扣掉其他层之后的剩余空间。
     */
    private fun quotaBytes(tier: Tier): Long = when (tier) {
        Tier.IMAGE -> Settings.cacheImageMb()
        Tier.META -> Settings.cacheMetaMb()
        Tier.LYRIC -> Settings.cacheLyricMb()
        Tier.AUDIO -> 0
    }.toLong() * 1024 * 1024

    /** 判定「正在使用中」的时间窗: 边下边播的文件每次写入都会刷新 mtime */
    private const val IN_USE_WINDOW_MS = 5 * 60 * 1000L

    /** 清空缓存 (含边下边播的 stream 暂存目录) */
    fun clear(context: Context): Long {
        var freed = 0L
        tierFiles(context).values.forEach { files ->
            files.forEach { f ->
                val len = f.length()
                if (f.delete()) freed += len
            }
        }
        Settings.setCacheUsedMb(0)
        return freed
    }

    /** 按后缀/文件名清理 (用于单曲取消下载) */
    fun remove(context: Context, fileName: String): Boolean =
        fileFor(context, fileName).delete()
}
