package com.portable.qqmusic.data

import android.content.Context
import com.portable.qqmusic.core.Config
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.api.SearchApi
import com.portable.qqmusic.data.api.Song
import org.json.JSONObject

/**
 * 本地音乐的「借来的元信息」。
 *
 * 本地文件只有歌名/歌手/专辑名，没有 QQ 音乐的 mid，所以拿不到封面和歌词。
 * 这里按名字去搜一次、把匹配上的封面专辑 mid（以及真 mid，留给歌词用）记下来，
 * 之后同一首再来就直接命中缓存，不再发请求。
 *
 * 匹配策略（用户定的）：
 * 1. 先拿「歌名 + 歌手」整串搜；
 * 2. 没匹配上就退化成只搜歌名 —— 同一首歌名 + 同一歌手，基本不会认错。
 *
 * 歌名先清一遍后缀：`(Remastered 2011)`、`[Live]`、`- Single`、`feat. xxx` 这些
 * 在本地文件名里满地都是，直接搜会把结果全带偏。
 */
object LocalMeta {

    /** 借到的元信息 */
    data class Borrowed(
        val mid: String,
        val albumMid: String,
        val albumName: String,
        /**
         * 在线结果里的真歌手。
         *
         * 本地文件 TAG 里没有歌手时我们只能写「未知歌手」, 借到 mid 的同时把这个字段
         * 一起带回来, 界面就能把那行占位名换成真的 (见 [Song.isUnknownSinger])。
         */
        val singer: String = ""
    )

    private const val PREFIX = "local_meta_"
    private val TTL_MS = 90L * 24 * 60 * 60 * 1000
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Borrowed>()

    /**
     * 参与匹配与缓存键的歌手名。
     *
     * 「未知歌手」不是真的歌手: 拿它拼进搜索词会把结果带偏, 拼进缓存键又会让
     * 覆盖歌手之后的同一首歌换一个键、白白重搜一次。这里统一折成空串。
     */
    fun singerOf(song: Song): String = if (song.isUnknownSinger) "" else song.singer.trim()

    /** 缓存键带上歌手：同名不同歌手的文件不会互相串 (歌手未知时只用歌名) */
    fun keyOf(song: Song): String =
        PREFIX + Integer.toHexString((song.name + "|" + singerOf(song)).lowercase().hashCode())

    /** 只读缓存，命中就同步返回，没命中返回 null（调用方自己决定要不要去搜） */
    fun cached(context: Context, song: Song): Borrowed? {
        val key = keyOf(song)
        memory[key]?.let { return it }
        val raw = MetaCache.get(context, key, TTL_MS) ?: return null
        return try {
            val o = JSONObject(raw)
            val mid = o.optString("mid")
            if (mid.isEmpty()) return null
            val b = Borrowed(
                mid = mid,
                albumMid = o.optString("albumMid"),
                albumName = o.optString("albumName"),
                singer = o.optString("singer")
            )
            memory[key] = b
            b
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 用缓存里借到的在线歌手补上「未知歌手」。
     *
     * 列表每次构建时调用 (IO 线程): 这首歌以前匹配过的话, 这里就不该再显示占位名,
     * 否则用户明明在播放页看到真歌手, 回到列表又变回「未知歌手」。
     */
    fun withBorrowedSinger(context: Context, song: Song): Song {
        if (!song.isUnknownSinger) return song
        val singer = cached(context, song)?.singer.orEmpty()
        return if (singer.isEmpty()) song else song.copy(singer = singer)
    }

    /**
     * 去 QQ 音乐找这首歌。
     *
     * 一律走 IO 线程，回调在主线程；找不到就回 null，界面保持占位图。
     */
    fun resolve(context: Context, song: Song, onDone: (Borrowed?) -> Unit) {
        val app = context.applicationContext
        cached(app, song)?.let {
            onDone(it)
            // 老缓存是「加歌手字段之前」写的, 里面没有歌手; 这里补一次搜索,
            // 找到就把歌手回填进缓存并再回调一次, 界面才会把「未知歌手」换掉
            refreshSinger(app, song, it, onDone)
            return
        }
        Task.io {
            val found = search(app, song)
            if (found != null) {
                memory[keyOf(song)] = found
                MetaCache.put(
                    app, keyOf(song),
                    JSONObject()
                        .put("mid", found.mid)
                        .put("albumMid", found.albumMid)
                        .put("albumName", found.albumName)
                        .put("singer", found.singer)
                        .toString()
                )
                Diag.i("LocalMeta", "匹配 ${song.name} / ${song.singer} -> ${found.mid} album=${found.albumMid}")
            } else {
                Diag.i("LocalMeta", "未匹配 ${song.name} / ${song.singer}")
            }
            Task.main { onDone(found) }
        }
    }

    /**
     * 同步版：给已经在 IO 线程上的调用方用（预取、歌词加载）。
     *
     * 命中缓存直接返回；没命中就当场搜一次并落盘。不要在主线程调。
     */
    fun resolveBlocking(context: Context, song: Song): Borrowed? {
        val app = context.applicationContext
        cached(app, song)?.let { base ->
            if (!song.isUnknownSinger || base.singer.isNotEmpty()) return base
            // 老缓存里没有歌手, 当场补搜一次并合并回缓存
            val found = try {
                search(app, song)
            } catch (t: Throwable) {
                Diag.i("LocalMeta", "搜索失败 ${song.name}: ${t.message}")
                null
            } ?: return base
            if (found.singer.isEmpty()) return base
            val merged = merge(base, found)
            remember(app, song, merged)
            return merged
        }
        val found = try {
            search(app, song)
        } catch (t: Throwable) {
            Diag.i("LocalMeta", "搜索失败 ${song.name}: ${t.message}")
            null
        }
        if (found == null) {
            Diag.i("LocalMeta", "未匹配 ${song.name} / ${song.singer}")
            return null
        }
        remember(app, song, found)
        return found
    }

    /** 正在补歌手缓存的键, 防止列表滚动时同一首歌反复发搜索 */
    private val refreshing = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * 老缓存（加 [Borrowed.singer] 之前写的）里没有歌手时补搜一次。
     *
     * 只对「本地歌手未知」的歌做：有真歌手的歌不关心这个字段。
     */
    private fun refreshSinger(app: Context, song: Song, base: Borrowed, onDone: (Borrowed?) -> Unit) {
        if (!song.isUnknownSinger || base.singer.isNotEmpty()) return
        val key = keyOf(song)
        if (!refreshing.add(key)) return
        Task.io {
            try {
                val found = try {
                    search(app, song)
                } catch (t: Throwable) {
                    Diag.i("LocalMeta", "搜索失败 ${song.name}: ${t.message}")
                    null
                }
                if (found != null && found.singer.isNotEmpty()) {
                    val merged = merge(base, found)
                    remember(app, song, merged)
                    Task.main { onDone(merged) }
                }
            } finally {
                refreshing.remove(key)
            }
        }
    }

    /** 用新搜到的结果补全老缓存：mid/专辑沿用老值，只有歌手更新 */
    private fun merge(base: Borrowed, found: Borrowed) = Borrowed(
        mid = base.mid.ifEmpty { found.mid },
        albumMid = base.albumMid.ifEmpty { found.albumMid },
        albumName = base.albumName.ifEmpty { found.albumName },
        singer = found.singer
    )

    private fun remember(app: Context, song: Song, found: Borrowed) {
        memory[keyOf(song)] = found
        MetaCache.put(
            app, keyOf(song),
            JSONObject()
                .put("mid", found.mid)
                .put("albumMid", found.albumMid)
                .put("albumName", found.albumName)
                .put("singer", found.singer)
                .toString()
        )
        Diag.i("LocalMeta", "匹配 ${song.name} / ${song.singer} -> ${found.mid} album=${found.albumMid}")
    }
    private fun search(context: Context, song: Song): Borrowed? {
        val name = cleanName(song.name)
        if (name.isEmpty()) return null
        // 歌手未知时只按歌名搜: 把「未知歌手」拼进搜索词会让整个结果集偏掉
        val singer = singerOf(song)

        // 一档: 歌名 + 歌手
        if (singer.isNotEmpty()) {
            pick(SearchApi.search("$name $singer", 1, 12), name, singer)?.let { return it }
        }
        // 二档: 只搜歌名。同名同歌手几乎不会认错, 所以这一档仍然要过歌手校验;
        // 歌手完全对不上时再退一步接受「歌名完全相等」的结果。
        val wide = SearchApi.search(name, 1, 12)
        pick(wide, name, singer)?.let { return it }
        return wide.firstOrNull { sameName(it.name, name) }?.toBorrowed()
    }

    /**
     * 从候选里挑一个：歌名要能对上，歌手要有交集（其一包含另一个即可）。
     *
     * 歌名对不上的一律不要 —— 宁可没封面，也不要给用户配错一张。
     */
    private fun pick(list: List<Song>, name: String, singer: String): Borrowed? {
        fun ok(s: Song) = sameName(s.name, name) && (singer.isEmpty() || singerHit(s.singer, singer))
        // 先挑带专辑的: 没有 albumMid 的结果借不来封面(QQ 音乐有一部分数据就是空的),
        // 同一个歌名往往有好几条, 排在前面那条恰好是空的。
        list.firstOrNull { ok(it) && it.albumMid.isNotEmpty() }?.let { return it.toBorrowed() }
        val hit = list.firstOrNull { ok(it) }
        return hit?.toBorrowed()
    }

    private fun Song.toBorrowed(): Borrowed? {
        if (albumMid.isEmpty() && mid.isEmpty()) return null
        // 在线结果也可能没有歌手 (纯音乐/伴奏), 那种就不要覆盖本地的占位名
        return Borrowed(mid, albumMid, albumName, if (isUnknownSinger) "" else singer.trim())
    }

    private fun sameName(a: String, b: String): Boolean {
        val x = cleanName(a)
        val y = cleanName(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun singerHit(candidate: String, wanted: String): Boolean {
        if (candidate.isEmpty()) return false
        // 多歌手用 / 、& 分隔，逐个比
        val parts = candidate.split('/', '、', '&', ',', '，')
        return parts.any { p ->
            val c = p.trim().lowercase()
            val w = wanted.lowercase()
            c.isNotEmpty() && (c.contains(w) || w.contains(c))
        } || candidate.lowercase().contains(wanted.lowercase())
    }

    /**
     * 去掉版本后缀和文件名的多余装饰。
     *
     * 处理的形态：`(Remastered 2011)`、`[Live]`、`（伴奏）`、`- Single`、
     * `feat. xxx` / `ft. xxx`、末尾的 `MV` / `Official`、以及 `01.` `01 -` 这种序号前缀。
     */
    fun cleanName(raw: String): String {
        var s = raw.trim()
        // 序号前缀
        s = s.replace(Regex("""^\d{1,3}[.\-_\s]+"""), "")
        // 括号里的版本说明，中英文括号都算
        s = s.replace(Regex("""[（(\[【][^）)\]】]*[）)\]】]"""), " ")
        // feat. / ft. 往后全砍
        s = s.replace(Regex("""(?i)\s(feat|ft)\..*$"""), " ")
        // 破折号后面是版本说明（- Single / - Live / - Remastered），但保留「歌手 - 歌名」里的歌名本身
        s = s.replace(Regex("""(?i)\s+-\s+(single|live|remaster(ed)?|demo|acoustic|instrumental|karaoke|off\s?vocal)\b.*$"""), " ")
        s = s.replace(Regex("""(?i)\s+(official|mv|m/v|audio|lyric\s?video|hd|hq)\s*$"""), " ")
        return s.replace(Regex("""\s+"""), " ").trim()
    }
}
