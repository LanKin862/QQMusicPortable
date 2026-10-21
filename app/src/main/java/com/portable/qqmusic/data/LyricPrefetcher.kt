package com.portable.qqmusic.data

import android.content.Context
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.data.api.Lyrics
import com.portable.qqmusic.data.api.qrc.QrcSource
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.data.api.SongApi
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 歌词的前置预热。
 *
 * 播放器每起播一首歌, 就把「当前 + 后面几首」的歌词先拉下来 (含服务端自带的译文)。
 * 于是用户点开歌词页 / 切到下一首时不用再等一次网络往返, 直接命中缓存。
 *
 * 刻意用**独立单线程**而不是 [com.portable.qqmusic.core.Task] 的 IO 池:
 * 预热是纯后台的, 占着 4 个 IO 线程里的一个会拖慢直链解析和音频预缓存 ——
 * 那是用户马上能感知到的卡顿。
 */
object LyricPrefetcher {

    /** 歌词文档的内存缓存上限 (首) */
    private const val MAX_CACHED_DOCS = 24

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lyric-prefetch").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }

    /** 已经处理过 (或正在处理) 的 mid, 避免同一首歌被反复排队 */
    private val seen = Collections.synchronizedSet(HashSet<String>())

    /**
     * 预热好的歌词文档 (LRU, 最多 24 首)。
     *
     * 歌词页打开时先看这里: 命中就不用再发一次歌词请求, 切歌回来的体验会明显快一截。
     * 只存解析后的结果, 不存原始 LRC, 免得每次都重新 parse。
     */
    private val docs = object : LinkedHashMap<String, Lyrics.Document>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics.Document>): Boolean =
            size > MAX_CACHED_DOCS
    }

    /**
     * 原始歌词的磁盘缓存前缀与有效期。
     *
     * 歌词是静态资源, 不会自己变; 过期只会让下次进歌词页再等一次网络。
     * 这里给 0 = 永不过期 (MetaCache 里 ttl <= 0 直接跳过时间判定),
     * 空间不够时由 CacheManager 按「歌词与译文」那一档配额统一清。
     */
    private const val SRC_PREFIX = "lyric_src_"
    private const val SRC_TTL_MS = 0L

    /**
     * 取歌词 (原文 LRC, 服务端译文 LRC), 带磁盘缓存。
     *
     * 没有这一步时原文只活在 [docs] 内存里, 进程一被杀就得重新请求 ——
     * 表现为「清后台重进, 歌词和翻译又都空着, 要等一遍接口」。
     * 歌词是不会变的静态资源, 落盘 30 天完全够。
     *
     * 调用方需在 IO 线程。
     */
    fun lyric(context: Context, mid: String): Pair<String, String> {
        val app = context.applicationContext
        val key = SRC_PREFIX + mid
        MetaCache.get(app, key, SRC_TTL_MS)?.let { raw ->
            try {
                val o = org.json.JSONObject(raw)
                val lrc = o.optString("lrc")
                if (lrc.isNotEmpty()) {
                    Diag.i("LyricPrefetcher", "歌词命中磁盘 $mid")
                    return lrc to o.optString("trans")
                }
            } catch (t: Throwable) {
                Log.w("LyricPrefetcher.lyric", t)
            }
        }
        val pair = SongApi.lyric(mid)
        if (pair.first.isNotEmpty()) {
            MetaCache.put(
                app, key,
                org.json.JSONObject().put("lrc", pair.first).put("trans", pair.second).toString()
            )
        }
        return pair
    }

    /** 取预热好的歌词; 没有返回 null */
    fun cachedDoc(mid: String): Lyrics.Document? {
        if (mid.isEmpty()) return null
        return synchronized(docs) { docs[mid] }
    }

    /**
     * 预热 [songs] 的歌词。
     *
     * 只在线程池里做, 调用方可以在主线程直接调。失败会把它从 [seen] 里摘掉,
     * 给下一次播放 (或切歌回来) 留一次重试机会。
     */
    fun prefetch(context: Context, songs: List<Song>) {
        if (songs.isEmpty()) return
        val app = context.applicationContext
        for (song in songs) {
            val mid = song.mid
            // 下载下来的歌是本地文件但有原曲 mid, 同样要预热; 只有 local_/file_ 这种假 mid 才跳过
            if (!song.hasRealMid) continue
            if (!seen.add(mid)) continue
            executor.execute { runOne(app, song) }
        }
    }

    private fun runOne(app: Context, song: Song) {
        val localMid = song.mid
        try {
            /*
             * 本地文件没有真 mid, 拿它去请求歌词只会返回空。
             * 先按「歌名 + 歌手」借一个 QQ 音乐的 mid 用来取词 —— 借来的 mid 只服务歌词,
             * 不参与播放直链(见 Song.hasRealMid 的注释), 所以不会把本地播放带跑偏。
             */
            val mid = if (song.isLocal && !song.hasRealMid) {
                val borrowed = LocalMeta.resolveBlocking(app, song)
                if (borrowed == null || borrowed.mid.isEmpty()) {
                    Log.d("LyricPrefetcher", "《${song.name}》 本地歌没借到 mid, 跳过歌词")
                    seen.remove(localMid)
                    return
                }
                borrowed.mid
            } else {
                localMid
            }
            /*
             * 先同步取 QRC, 再取 LRC。
             *
             * 顺序不能反: QRC 优先级更高, 有它就用它的文本 + 逐字时间轴; LRC 只是保底。
             * 而且这一步必须在这里做完(而不是等界面进来再补), 否则第一次进某首歌的歌词页
             * 永远是「有歌词但扫色还是整行推」, 退出重进才正常 —— 那就是之前那个已知限制。
             */
            val qrc = QrcSource.fetch(app, mid)
            val (lrc, trans) = lyric(app, mid)
            val parsed = if (qrc.isNotEmpty()) {
                Diag.i("LyricPrefetcher", "《${song.name}》 用 QRC 当歌词源 ${qrc.size} 行")
                Lyrics.fromQrc(qrc, trans)
            } else {
                if (lrc.isEmpty()) {
                    Log.d("LyricPrefetcher", "《${song.name}》 没有歌词")
                    seen.remove(mid)
                    return
                }
                val p = Lyrics.parse(lrc, trans)
                if (p.isEmpty) {
                    seen.remove(mid)
                    return
                }
                Lyrics.applyTranslation(p)
            }
            // 缓存键必须是本地歌自己的 mid —— 界面查出歌词靠的是 song.mid, 不是借来的那个
            synchronized(docs) { docs[localMid] = parsed }
        } catch (t: Throwable) {
            Log.w("LyricPrefetcher", t)
            seen.remove(localMid)
        }
    }
}
