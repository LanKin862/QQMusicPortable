package com.portable.qqmusic.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Settings
import com.portable.qqmusic.core.Storage
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.api.PlayUrlApi
import com.portable.qqmusic.data.api.Song
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 离线下载。
 *
 * - 串行执行, 避免多线程同时写公共目录导致 MediaStore 竞争
 * - Android 10+ 落在公共目录时通过 MediaStore 注册, 让系统音乐库能扫到
 * - 进度通过 [addListener] 回调到主线程
 */
object DownloadManager {

    /** 已结束的任务在「正在下载」页停留多久 (毫秒); 下完即走, 不占着列表 */
    private const val DONE_KEEP_MS = 1_000L

    enum class Status { QUEUED, DOWNLOADING, DONE, FAILED, CANCELLED }

    /**
     * 下载文件的元信息。
     *
     * 磁盘上只有一个音频文件, 但列表里要显示真实歌名/歌手/时长/封面, 播放页还要按**原曲 mid**
     * 去拉歌词 —— 光靠文件名「歌手 - 歌名_HiRes.flac」是拿不到这些的, 所以下载成功时把
     * [Song] 的关键字段一起存下来 (持久化到 `files/download_meta.json`)。
     */
    data class DownloadMeta(
        val mid: String = "",
        val songId: Long = 0L,
        val name: String = "",
        val singer: String = "",
        val albumMid: String = "",
        val albumName: String = "",
        val durationSec: Int = 0
    )

    class DownloadTask(
        val song: Song,
        val quality: com.portable.qqmusic.core.Quality,
        val targetFile: File
    ) {
        @Volatile
        var status: Status = Status.QUEUED

        @Volatile
        var progress: Int = 0

        @Volatile
        var error: String = ""

        /** 结束时间戳, 用于让「已完成」在列表里停留一会儿后自动消失 */
        @Volatile
        var finishedAt: Long = 0L
    }

    /** 监听: (任务, 状态) */
    private val listeners = CopyOnWriteArrayList<(DownloadTask) -> Unit>()

    /** 已下载记录: songKey -> 文件路径 */
    private val completed = ConcurrentHashMap<String, String>()

    /**
     * 已下载记录: 规范化文件名 (歌手 - 歌名) -> 文件路径。
     *
     * 冷启动后只有磁盘上的文件、没有 song.mid 可以反查, 所以用文件名做第二把钥匙。
     * 之前 [scanDownloaded] 把「文件路径」当 key 塞进 [completed], 于是
     * `isDownloaded()` / `localPath()` 全部失配 —— 这是「删掉再下就加不进下载列表」的根因之一。
     */
    private val byBaseName = ConcurrentHashMap<String, String>()

    /** 下载文件的元信息: 去音质后缀的基名 -> [DownloadMeta] */
    private val metas = ConcurrentHashMap<String, DownloadMeta>()

    private val metaLock = Any()

    @Volatile
    private var metaLoaded = false

    @Volatile
    private var appContext: Context? = null

    /** 音频时长缓存 (绝对路径 -> 秒), 从文件里读一次就够 */
    private val durations = HashMap<String, Int>()

    /** 本次会话里出现过的所有下载任务 (含已完成/失败), 「正在下载」页靠它显示进度与状态 */
    private val tasks = CopyOnWriteArrayList<DownloadTask>()

    private val queue = ArrayDeque<DownloadTask>()

    @Volatile
    private var running = false

    /** 冷启动时调用一次: 记住 application context, 供元信息懒加载与时长读取使用 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 取某个下载文件的元信息 (没有返回 null) */
    fun metaFor(fileName: String): DownloadMeta? {
        if (fileName.isEmpty()) return null
        ensureMetaLoaded()
        return metas[baseNameOfFile(fileName)]
    }

    /**
     * 记下这首歌的元信息 (下载成功 / 命中已有文件时调用)。
     *
     * 文件名与 [baseNameOf] 可能不完全一致 (音质后缀), 所以统一用 [baseNameOfFile] 归一。
     */
    fun rememberMeta(song: Song, fileName: String) {
        val key = baseNameOfFile(fileName)
        if (key.isEmpty()) return
        metas[key] = DownloadMeta(
            mid = song.mid,
            songId = song.songId,
            name = song.name,
            singer = song.singer,
            albumMid = song.albumMid,
            albumName = song.albumName,
            durationSec = song.durationSec
        )
        persistMeta()
    }

    /**
     * 读音频时长 (秒)。读不到返回 0。
     *
     * 老版本下载下来的文件没有元信息, 只能真的解析一遍文件头; 结果按路径缓存,
     * 同一个文件不会每刷新一次列表就再解一次。
     */
    fun durationOf(f: File): Int {
        synchronized(durations) { durations[f.absolutePath]?.let { return it } }
        var sec = 0
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                sec = (ms / 1000L).toInt()
            } finally {
                runCatching { r.release() }
            }
        } catch (t: Throwable) {
            Log.w("DownloadManager.dur", t)
        }
        synchronized(durations) { durations[f.absolutePath] = sec }
        return sec
    }

    private fun ensureMetaLoaded() {
        if (metaLoaded) return
        val ctx = appContext ?: return
        synchronized(metaLock) {
            if (metaLoaded) return
            metaLoaded = true
            runCatching {
                val f = metaFile(ctx)
                if (!f.exists() || f.length() == 0L) return@synchronized
                val json = JSONObject(f.readText())
                for (key in json.keys()) {
                    val o = json.optJSONObject(key) ?: continue
                    metas[key] = DownloadMeta(
                        mid = o.optString("mid"),
                        songId = o.optLong("songId"),
                        name = o.optString("name"),
                        singer = o.optString("singer"),
                        albumMid = o.optString("albumMid"),
                        albumName = o.optString("albumName"),
                        durationSec = o.optInt("dur")
                    )
                }
                Diag.i("DownloadManager", "读取下载元信息 ${metas.size} 条")
            }.onFailure { Log.w("DownloadManager.meta", it) }
        }
    }

    private fun persistMeta() {
        val ctx = appContext ?: return
        val snapshot = HashMap(metas)
        Task.io {
            runCatching {
                val json = JSONObject()
                for ((key, m) in snapshot) {
                    json.put(
                        key,
                        JSONObject().apply {
                            put("mid", m.mid)
                            put("songId", m.songId)
                            put("name", m.name)
                            put("singer", m.singer)
                            put("albumMid", m.albumMid)
                            put("albumName", m.albumName)
                            put("dur", m.durationSec)
                        }
                    )
                }
                metaFile(ctx).writeText(json.toString())
            }.onFailure { Log.w("DownloadManager.meta", it) }
        }
    }

    private fun metaFile(context: Context): File = File(context.filesDir, "download_meta.json")

    private fun forgetMeta(fileName: String) {
        if (fileName.isEmpty()) return
        if (metas.remove(baseNameOfFile(fileName)) != null) persistMeta()
    }

    fun addListener(l: (DownloadTask) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (DownloadTask) -> Unit) {
        listeners.remove(l)
    }

    private fun notify(t: DownloadTask) {
        val snapshot = listeners.toList()
        Task.main { snapshot.forEach { it(t) } }
    }

    fun isDownloaded(song: Song): Boolean {
        if (completed.containsKey(song.key())) {
            val p = completed[song.key()]
            if (p != null && File(p).exists()) return true
        }
        val p = byBaseName[baseNameOf(song)] ?: return false
        return File(p).exists()
    }

    /**
     * 文件被用户从列表里删掉后同步清掉记录。
     * 不清的话 `isDownloaded()` 会一直按内存里那份表说"它还在", 列表删了又回来,
     * 更糟的是重新下载时会被这几行残留记录直接 `continue` 掉 —— 表现就是
     * 「删掉再下, 加不进下载列表」。
     */
    fun forget(song: Song) {
        completed.remove(song.key())
        byBaseName.remove(baseNameOf(song))
        val path = song.localPath
        if (path.isNotEmpty()) forgetByPath(path)
        tasks.removeAll { it.song.key() == song.key() }
    }

    /** 按绝对路径清掉所有指向它的记录 (同一个文件可能被多个 song key 记着) */
    fun forgetByPath(path: String) {
        if (path.isEmpty()) return
        completed.entries.removeAll { it.value == path }
        byBaseName.entries.removeAll { it.value == path }
        tasks.removeAll { it.targetFile.absolutePath == path }
        forgetMeta(File(path).name)
    }

    fun localPath(song: Song): String? {
        val direct = completed[song.key()]
        if (direct != null && File(direct).exists()) return direct
        val byName = byBaseName[baseNameOf(song)] ?: return null
        return byName.takeIf { File(it).exists() }
    }

    /**
     * 队列里还没结束的任务。
     *
     * 注意「正在下载」页现在用 [allTasks]: 曾经这里只返回 [queue], 而正在下载的
     * 那一项已经被 pump 移出队列, 已完成/失败的也立刻消失, 于是那个页面永远是空的。
     */
    fun pendingTasks(): List<DownloadTask> = synchronized(queue) { queue.toList() }

    /**
     * 本次会话出现过的全部下载任务 (排队中/下载中/已完成/失败/已取消), 按「进行中优先」排序。
     *
     * 「正在下载」页用它显示每首歌的实时进度与状态。
     */
    fun allTasks(): List<DownloadTask> {
        val weight = mapOf(
            Status.DOWNLOADING to 0,
            Status.QUEUED to 1,
            Status.FAILED to 2,
            Status.DONE to 3,
            Status.CANCELLED to 4
        )
        val now = System.currentTimeMillis()
        // 已完成/失败/取消的任务只在列表里闪一下 (1 秒), 随即消失 ——
        // 那个页面是「正在下载」, 不是第二个「已下载」。
        val visible = tasks.filter {
            when (it.status) {
                Status.DONE, Status.CANCELLED, Status.FAILED ->
                    it.finishedAt == 0L || now - it.finishedAt < DONE_KEEP_MS
                else -> true
            }
        }
        return visible.sortedBy { weight[it.status] ?: 9 }
    }

    /**
     * 删掉某首歌在下载目录里的副本 (含公共目录模式下多出来的那份镜像)。
     *
     * 媒体库和下载目录是两套索引, 只删一边的话另一边下次扫描又会把它带回来 ——
     * 表现就是「删了之后返回再进又出现」。返回删掉的文件数。
     */
    fun purgeCopies(song: Song, context: Context): Int {
        val base = baseNameOf(song)
        if (base.isEmpty()) return 0
        var n = 0
        val dir = Storage.downloadDir(context)
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (baseNameOfFile(f.name) != base) return@forEach
            forgetByPath(f.absolutePath)
            if (f.delete()) n++
        }
        return n
    }

    /** 入队下载 */
    fun enqueue(context: Context, songs: List<Song>) {
        val quality = Settings.downloadQuality()
        com.portable.qqmusic.core.Diag.i(
            "DownloadManager",
            "enqueue ${songs.size} 首 quality=$quality dir=${Storage.downloadDir(context).absolutePath}"
        )
        val dir = Storage.downloadDir(context)
        for (song in songs) {
            // 已经在排队/正在下: 不重复入队
            val busy = tasks.any {
                it.song.key() == song.key() &&
                    (it.status == Status.QUEUED || it.status == Status.DOWNLOADING)
            }
            if (busy) continue
            // 清掉这首歌之前留下的终态任务, 免得列表里堆一排重复项
            tasks.removeAll { it.song.key() == song.key() }

            val target = File(dir, buildFileName(song, quality))
            val existPath = localPath(song)
                ?: target.absolutePath.takeIf { target.exists() && target.length() > 0 }
            if (existPath != null && File(existPath).exists()) {
                // 文件确实还在: 给出一个「已完成」任务, 让界面有反馈。
                // 以前这里是静默 continue, 用户看到的就是「点了下载没反应 / 加不进列表」。
                completed[song.key()] = existPath
                byBaseName[baseNameOf(song)] = existPath
                rememberMeta(song, File(existPath).name)
                val done = DownloadTask(song, quality, File(existPath))
                done.status = Status.DONE
                done.progress = 100
                tasks.add(done)
                notify(done)
                continue
            }
            // 记录过期 (文件已被删掉): 清掉, 否则重新下载会被它挡住
            forget(song)

            val t = DownloadTask(song, quality, target)
            tasks.add(t)
            synchronized(queue) { queue.addLast(t) }
            notify(t)
        }
        com.portable.qqmusic.core.Diag.i("DownloadManager", "enqueue 后队列长度=${queue.size}")
        pump(context.applicationContext)
    }

    fun cancel(song: Song) {
        tasks.firstOrNull {
            it.song.key() == song.key() && it.status == Status.QUEUED
        }?.let {
            it.status = Status.CANCELLED
            synchronized(queue) { queue.remove(it) }
            notify(it)
        }
    }

    private fun pump(context: Context) {
        if (running) return
        running = true
        Task.serial {
            try {
                while (true) {
                    val task = synchronized(queue) {
                        if (queue.isEmpty()) null else queue.removeFirst()
                    } ?: break
                    if (task.status == Status.CANCELLED) continue
                    runTask(context, task)
                }
            } finally {
                running = false
            }
        }
    }

    private fun runTask(context: Context, task: DownloadTask) {
        task.status = Status.DOWNLOADING
        notify(task)

        // 用 resolvePlayable 而不是 resolveDownloadUrl: 后者只调 vkey 接口拿直链,
        // 而服务端给的 purl 经常是 404 的死链 (尤其 Hi-Res), 直接下必然失败。
        // resolvePlayable 会对直链发 Range 探测, 不通就自动降档, 拿到的才是真能下的。
        val resolved = PlayUrlApi.resolvePlayable(task.song, task.quality)
        val url = resolved?.first
        com.portable.qqmusic.core.Diag.i(
            "DownloadManager",
            "取下载地址 《${task.song.name}》 url=${url?.url?.take(80)} playable=${url?.isPlayable}"
        )
        if (url == null || !url.isPlayable) {
            task.status = Status.FAILED
            task.error = "无法获取下载地址 (可能无版权或需要会员)"
            task.finishedAt = System.currentTimeMillis()
            notify(task)
            return
        }

        val useMediaStore = Storage.needMediaStore()
        val ok = if (useMediaStore) {
            downloadViaMediaStore(context, url.url, task)
        } else {
            PlayUrlApi.downloadToFile(url.url, task.targetFile, { done, total ->
                if (total > 0) {
                    val p = (done * 100 / total).toInt()
                    if (p != task.progress) {
                        task.progress = p
                        notify(task)
                    }
                }
            })
        }

        if (ok) {
            task.status = Status.DONE
            task.progress = 100
            val path = task.targetFile.absolutePath
            completed[task.song.key()] = path
            byBaseName[baseNameOf(task.song)] = path
            // 记住原曲元信息: 「本地」里才有真实时长/封面, 播放页也才能按 mid 拉到歌词
            rememberMeta(task.song, task.targetFile.name)
        } else {
            task.status = Status.FAILED
            task.error = "下载失败, 请检查网络或存储权限"
        }
        task.finishedAt = System.currentTimeMillis()
        com.portable.qqmusic.core.Diag.i(
            "DownloadManager",
            "完成 《${task.song.name}》 status=${task.status} target=${task.targetFile.absolutePath} " +
                "exists=${task.targetFile.exists()} len=${task.targetFile.length()}"
        )
        notify(task)
    }

    /** Android 10+ 公共目录下载: 先写应用内部临时文件, 再通过 MediaStore 落地 */
    private fun downloadViaMediaStore(context: Context, url: String, task: DownloadTask): Boolean {
        val tmp = File(context.cacheDir, "dl_${System.currentTimeMillis()}.tmp")
        val ok = PlayUrlApi.downloadToFile(url, tmp, { done, total ->
            if (total > 0) {
                val p = (done * 100 / total).toInt()
                if (p != task.progress) {
                    task.progress = p
                    notify(task)
                }
            }
        })
        if (!ok || !tmp.exists()) {
            tmp.delete()
            return false
        }
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, task.targetFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(task.targetFile.name))
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Storage.mediaStoreRelativePath())
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            // 记录一个可读的镜像路径, 供本地音乐列表展示
            task.targetFile.parentFile?.mkdirs()
            val mirror = File(task.targetFile.parentFile, task.targetFile.name)
            try {
                tmp.inputStream().use { input -> mirror.outputStream().use { input.copyTo(it) } }
            } catch (t: Throwable) {
                Log.w("DownloadManager", t)
            }
            true
        } catch (t: Throwable) {
            Log.w("DownloadManager.mstore", t)
            false
        } finally {
            tmp.delete()
        }
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".flac") -> "audio/flac"
        name.endsWith(".m4a") -> "audio/mp4"
        else -> "audio/mpeg"
    }

    /** 规范化后的「歌手 - 歌名」; 下载文件名与已下载索引都以它为基准 */
    fun baseNameOf(song: Song): String = "${song.singer} - ${song.name}"
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
        .trim()
        .take(80)

    /**
     * 下载文件名: 歌手 - 歌名 + 扩展名。
     *
     * 音质**不进文件名**。早先会拼一个 `_HQ` / `_SQ` / `_HiRes` 后缀, 结果媒体库
     * 拿不到 ID3 标题时退回用文件名当 TITLE, 列表里就显示成「歌名_HQ」。
     */
    fun buildFileName(song: Song, quality: com.portable.qqmusic.core.Quality): String =
        baseNameOf(song) + quality.ext

    /** 从磁盘文件名反推 [baseNameOf] (去掉扩展名与老版本留下的音质后缀) */
    fun baseNameOfFile(name: String): String =
        stripQualitySuffix(name.substringBeforeLast('.', name))

    /** 去掉老版本文件名里的 `_HiRes` / `_HQ` / `_SQ` 尾巴 (媒体库 TITLE 也有同样问题) */
    fun stripQualitySuffix(name: String): String {
        for (suffix in arrayOf("_HiRes", "_HQ", "_SQ")) {
            if (name.endsWith(suffix)) return name.dropLast(suffix.length)
        }
        return name
    }

    /** 扫描下载目录, 恢复已下载列表 (冷启动时调用) */
    fun scanDownloaded(context: Context) {
        init(context)
        Task.io {
            ensureMetaLoaded()
            val dir = Storage.downloadDir(context)
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() > 0 && !f.name.endsWith(".part") && isAudio(f.name)) {
                    // 用「文件名」当 key: 冷启动后没有 song.mid 可反查, 只能靠它认出
                    // 「这个歌手-歌名已经下过了」。之前把路径当 key, 结果 isDownloaded()
                    // 永远为 false, 于是删掉再下时又会被 enqueue 里的重复判断弄乱。
                    byBaseName[baseNameOfFile(f.name)] = f.absolutePath
                }
            }
            Log.d("DownloadManager", "scanned ${byBaseName.size} downloaded files in ${dir.absolutePath}")
        }
    }

    /** 遍历下载目录得到本地歌曲 (供「本地」卡片使用) */
    fun downloadedFiles(context: Context): List<File> {
        val dir = Storage.downloadDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && isAudio(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun isAudio(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a") ||
            n.endsWith(".aac") || n.endsWith(".wav") || n.endsWith(".ogg")
    }

    /** 公共目录是否可用 (判断存储位置是否可写) */
    fun isExternalAvailable(): Boolean = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
}
