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
 * 匹配策略（用户定的，2026-10-21 收紧，10-22 再调）：
 * 1. 搜索词按「歌名 歌手」的格式发（同名歌太多，带上歌手才能把目标顶上来）；
 *    歌名**全字匹配**（规范化后整串相等，不再用包含关系），命中后还要过两道校验：
 *    - 作者对应：两边都按 `/` 等分隔符拆开，只要存在一个「包含」关系就算对上；
 *    - 时长对应：与本地文件时长相差不超过 [DURATION_TOLERANCE_SEC] 秒（任一侧读不到就跳过这关）。
 * 2. 第 1 步没命中，就把歌名里的 `Original Mix` / `Remix` 之类版本词剥掉，再按第 1 步重来。
 * 3. 两步都失败 → 匹配失败，这首跳过；宁可没封面，也不配错封面。
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

    /**
     * 缓存键前缀。
     *
     * 2026-10-21 匹配规则收紧（全字匹配 + 作者 + 时长），旧缓存里存的是按旧规则挑出来的
     * 结果，不能再信 —— 换一个前缀让它们自然失效，老文件到期后由 MetaCache 自己清。
     */
    private const val PREFIX = "local_meta_v2_"
    private val TTL_MS = 90L * 24 * 60 * 60 * 1000

    /**
     * 时长容差（秒）。
     *
     * 本地文件时长来自 MediaStore / 文件头，和在线返回的 `interval` 常有 1~3 秒出入
     * （编码裁剪、静音头尾）。超过这个值基本就是另一个版本（remix / live / 伴奏）。
     */
    private const val DURATION_TOLERANCE_SEC = 5

    /** 未知歌手没有作者可对，只剩「歌名 + 时长」；容差同样按 5 秒（用户定的规则） */
    private const val DURATION_TOLERANCE_UNKNOWN_SEC = 5

    /** 未知歌手只比对搜索结果的前 5 条：更靠后的基本都是不相关的东西 */
    private const val UNKNOWN_PAGE_SIZE = 5

    /** 候选清单日志的总量上限: 批量匹配时不然会把诊断日志写到截断 */
    private const val MAX_CANDIDATE_LOGS = 40

    private val candidateLogs = java.util.concurrent.atomic.AtomicInteger(0)
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

    /** 只按歌名算的键：歌手是「借来的」时，结果当初是按这个键存进去的 */
    private fun unknownKey(song: Song): String =
        PREFIX + Integer.toHexString((song.name + "|").lowercase().hashCode())

    /**
     * 匹配结果的版本号：批量匹配改过缓存之后，列表靠它知道该重新读一遍。
     *
     * 只在内存里递增，进程重启就回到 0 —— 那时列表本来也要重建，不需要跨进程。
     */
    @Volatile var revision: Int = 0
        private set

    private fun bumpRevision() {
        revision++
    }

    /**
     * 清空所有匹配结果（内存 + 磁盘）。
     *
     * 设置里「匹配本地歌曲信息」开跑之前调用：既然用户要求重新匹配，
     * 旧结论就不能再当命中，否则改了规则也看不到效果。
     */
    fun clearCache(context: Context) {
        memory.clear()
        MetaCache.clearPrefix(context.applicationContext, "local_meta")
        bumpRevision()
    }

    /** 只读缓存，命中就同步返回，没命中返回 null（调用方自己决定要不要去搜） */
    fun cached(context: Context, song: Song): Borrowed? {
        val key = keyOf(song)
        memory[key]?.let { return it }
        readDisk(context, key)?.let { return it }
        // 歌手是从这份缓存里借来的话，下一轮 key 会多出歌手那段，只剩「歌名」的
        // 旧条目就再也读不到了 —— 播放页于是每次都重新搜一遍（还经常搜不到）。
        // 这里退回去按歌名再找一次。
        if (singerOf(song).isNotEmpty()) {
            val alt = unknownKey(song)
            memory[alt]?.let { return it }
            readDisk(context, alt)?.let { return it }
        }
        return null
    }

    private fun readDisk(context: Context, key: String): Borrowed? {
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
                remember(app, song, found)
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
        bumpRevision()
        Diag.i("LocalMeta", "匹配 ${song.name} / ${song.singer} -> ${found.mid} album=${found.albumMid}")
    }
    /**
     * 找这首歌的在线元信息。
     *
     * 两步（见类注释）：全字匹配 → 剥掉版本词再来一次。两步都没命中就返回 null，
     * 调用方保持占位图，不再退化成「随便找个同名」。
     */
    private fun search(context: Context, song: Song): Borrowed? {
        val duration = song.durationSec
        val singer = singerOf(song)
        val local = cleanName(song.name)
        if (local.isEmpty()) return null

        // 未知歌手走另一条路: 文件名整串就是「歌名-歌手」, 直接拿它搜, 再按前 5 条
        // 逐条核对 (见 [searchUnknown])。规则是用户定的, 别再拿它去套「重建名字」。
        if (singer.isEmpty()) return searchUnknown(local, duration)

        // 零档 (用户定的, 2026-10-21 晚): 歌名先别清洗 ——
        // 文件名里已经带着歌手时 (`宇多田ヒカル - Fly Me To The Moon (…)`), 直接拿
        // 整串去搜最准; 没带歌手才拼成「歌名 歌手」。这一步用原始名字比对, 命中率最高。
        // 名字本来就干净 (没被 cleanName 改动、也没带歌手) 就跳过 —— 那种情况和下面
        // 一档发出的搜索词一模一样, 白跑一次请求。
        val raw = song.name.trim()
        if (raw.isNotEmpty() && (raw != local || containsArtist(raw, singer))) {
            val query = if (containsArtist(raw, singer)) raw else "$raw $singer"
            pick(SearchApi.search(query, 1, 12), raw, singer, duration) { c, n -> rawNameHit(c, n, singer) }
                ?.let {
                    Diag.i("LocalMeta", "零档命中 《${song.name}》 (按 '$query') -> ${it.mid}")
                    return it
                }
        }

        // 一档: 先拿整串名字搜一次 (这一次的结果后面拆词补搜还要用), 命中就直接返回。        // 一档: 先拿整串名字搜一次 (这一次的结果后面拆词补搜还要用), 命中就直接返回。
        val first = SearchApi.search(local, 1, 12)
        pick(first, local, singer, duration)?.let {
            Diag.i("LocalMeta", "一档命中 《${song.name}》 -> ${it.mid}")
            return it
        }

        // 一档续: 按「歌名 歌手」的格式再搜。同名歌太多 (Aurora / Water / Fire 这种),
        // 只搜歌名时排在前面的经常是别人的版本, 带上歌手才能把目标顶上来。
        // 歌名从本地名字里拆 —— 「歌名-歌手」「歌手 - 歌名」「歌名 歌手」都认。
        for (guess in titleQueries(local, singer)) {
            hit(local, guess, singer, duration)?.let {
                Diag.i("LocalMeta", "一档命中 《${song.name}》 (按 '$guess 歌手') -> ${it.mid}")
                return it
            }
        }

        // 二档: 去掉 Original Mix / Remix 这类版本词再来一次
        val stripped = stripMixWords(local)
        if (stripped.isNotEmpty() && stripped != local) {
            hit(stripped, stripped, singer, duration)?.let {
                Diag.i("LocalMeta", "二档命中 《${song.name}》 (按 '$stripped') -> ${it.mid}")
                return it
            }
        }

        // 补搜: 整串搜不到时 (搜索接口对「歌名-歌手」这种长串经常直接跑偏),
        // 把破折号两边拆开分别当歌名再搜。哪边是歌名看第一轮候选的作者:
        // 作者出现在左边说明左边是歌手、歌名在右边, 反之亦然; 都没线索就两边都试。
        for (guess in titleGuesses(local, first)) {
            hit(guess, guess, singer, duration)?.let {
                Diag.i("LocalMeta", "补搜命中 《${song.name}》 (按 '$guess') -> ${it.mid}")
                return it
            }
        }

        Diag.i("LocalMeta", "全部未命中 《${song.name}》 / ${song.singer} dur=$duration")
        return null
    }

    /**
     * 未知歌手的匹配：整串文件名搜索 → 只看前 5 条 → 逐条核对。
     *
     * 判定（用户定的规则）：
     * - 名字里有破折号：把两边拆开，候选要「完全重合」—— 歌名等于其中一边、
     *   作者里恰好有一项等于另一边（哪个在前都行）；
     * - 名字里没有破折号：候选歌名要整串相等；
     * - 时长差不超过 [DURATION_TOLERANCE_UNKNOWN_SEC] 秒。
     *
     * 多条都满足时取搜索结果里排在最前面的那条（QQ 音乐把最相关的排在前面）；
     * 一条都对不上就跳过，不猜。
     */
    private fun searchUnknown(local: String, duration: Int): Borrowed? {
        val splits = splitTitleArtist(local)
        val list = SearchApi.search(local, 1, UNKNOWN_PAGE_SIZE).take(UNKNOWN_PAGE_SIZE)
        if (list.isEmpty()) {
            Diag.i("LocalMeta", "未知歌手未命中 《$local》 (搜索无结果)")
            return null
        }
        for (c in list) {
            if (!durationHit(c.durationSec, duration, DURATION_TOLERANCE_UNKNOWN_SEC)) continue
            // 候选名同样去掉括号注释再比 —— 本地名在一开始就 cleanName 过了,
            // 不这样「Asphyxia (窒息)」这种带译名的上传版永远对不上。
            val cn = norm(cleanName(c.name))
            val ok = if (splits.isEmpty()) {
                // 文件名可能把歌手直接黏在歌名后面 (《Faded (Instrumental)  Alan Walker》),
                // 除了整串相等, 也认「候选歌名 + 候选歌手」的两种拼法。
                cn == norm(local) ||
                    norm(local) == cn + norm(c.singer) ||
                    norm(local) == norm(c.singer) + cn
            } else {
                splits.any { (a, b) ->
                    // 另一半既可能是歌手, 也可能是歌名的译名, 所以两边都试;
                    // 作者写法不同 (鷺巣詩郎 / 鹭巣诗郎、凛として時雨 / 凛冽时雨) 也认。
                    (cn == norm(a) && (artistOverlaps(c.singer, b) || artistLooksSame(c.singer, b))) ||
                        (cn == norm(b) && (artistOverlaps(c.singer, a) || artistLooksSame(c.singer, a)))
                }
            }
            if (ok) {
                Diag.i("LocalMeta", "未知歌手命中 《$local》 -> ${c.name}/${c.singer}/${c.durationSec}s")
                return c.toBorrowed()
            }
        }
        Diag.i(
            "LocalMeta",
            "未知歌手未命中 《$local》/${duration}s (前 ${list.size} 条: " +
                list.take(5).joinToString("; ") { "${it.name}/${it.singer}/${it.durationSec}s" } + ")"
        )
        return null
    }

    /**
     * 把所有可能的「歌名|歌手」切法都列出来。
     *
     * 不能只切最后一个破折号：歌手名里自己也可能有破折号
     * （`Bluish Light-塞壬唱片-MSR,…`），只切一处会把歌名切成半个。
     */
    private fun splitTitleArtist(name: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(2)
        name.forEachIndexed { i, ch ->
            if (ch == '-' || ch == '–' || ch == '—') {
                val left = name.substring(0, i).trim()
                val right = name.substring(i + 1).trim()
                if (left.isNotEmpty() && right.isNotEmpty()) out.add(left to right)
            }
        }
        return out
    }

    /** 把「歌名 - 歌手」拆成两半，供补搜用；没有破折号返回空 */
    private fun titleGuesses(local: String, cands: List<Song>): List<String> {
        val idx = local.lastIndexOfAny(charArrayOf('-', '–', '—'))
        if (idx <= 0 || idx >= local.length - 1) return emptyList()
        val left = local.substring(0, idx).trim()
        val right = local.substring(idx + 1).trim()
        if (left.isEmpty() || right.isEmpty()) return emptyList()
        val artists = cands.flatMap { splitArtists(it.singer) }
        val out = LinkedHashSet<String>()
        if (artists.any { it.isNotEmpty() && norm(left).contains(it) }) out.add(right)
        if (artists.any { it.isNotEmpty() && norm(right).contains(it) }) out.add(left)
        if (out.isEmpty()) {
            out.add(left)
            out.add(right)
        }
        out.remove(local)
        return out.take(2)
    }

    /**
     * 从本地名字里拆出「歌名」的几种写法，供「歌名 歌手」格式的搜索用。
     *
     * 认三种拼法：`歌名-歌手`、`歌手 - 歌名`、`歌名 歌手`（空格分隔）。拆不出来就
     * 返回整串 —— 至少还有一次不带歌手的兜底搜索。顺序就是尝试顺序：拆出来的在前。
     */
    private fun titleQueries(local: String, singer: String): List<String> {
        val out = LinkedHashSet<String>(4)
        if (singer.isNotEmpty()) {
            val s = norm(singer)
            for (i in local.indices) {
                val ch = local[i]
                if (ch != '-' && ch != '–' && ch != '—') continue
                val left = local.substring(0, i).trim()
                val right = local.substring(i + 1).trim()
                if (left.isEmpty() || right.isEmpty()) continue
                if (norm(left) == s) out.add(right)
                else if (norm(right) == s) out.add(left)
            }
            if (local.endsWith(singer, ignoreCase = true)) {
                val head = local.dropLast(singer.length).trim().trim('-', '–', '—', '_', '.', ' ')
                if (head.isNotEmpty()) out.add(head)
            } else if (local.startsWith(singer, ignoreCase = true)) {
                val tail = local.drop(singer.length).trim().trim('-', '–', '—', '_', '.', ' ')
                if (tail.isNotEmpty()) out.add(tail)
            }
        }
        out.remove(local)
        out.add(local)
        return out.toList()
    }

    /** 文件名里是不是本来就带着歌手（`宇多田ヒカル - Fly Me To The Moon`、`魂のルフラン 高橋洋子`） */
    private fun containsArtist(raw: String, singer: String): Boolean {
        val nr = norm(raw)
        return splitArtists(singer).any { a ->
            val na = norm(a)
            na.length >= 2 && nr.contains(na)
        }
    }

    /**
     * 零档用的比对：本地名**不做清洗**，只把「文件名里带的歌手」剥掉再和候选名比。
     *
     * 例：本地 `宇多田ヒカル - Fly Me To The Moon (In Other Words) (2007 MIX)`
     * vs 线上 `Fly Me To The Moon (In Other Words) (2007 MIX)` —— 剥掉歌手和分隔符后相等。
     */
    private fun rawNameHit(candidate: Song, raw: String, singer: String): Boolean {
        val n = norm(candidate.name)
        if (n.isEmpty()) return false
        val nr = norm(raw)
        if (nr.isEmpty()) return false
        if (nr == n) return true
        val variants = LinkedHashSet<String>(4)
        val s = norm(singer)
        if (s.isNotEmpty()) {
            for (i in raw.indices) {
                val ch = raw[i]
                if (ch != '-' && ch != '–' && ch != '—') continue
                val left = raw.substring(0, i).trim()
                val right = raw.substring(i + 1).trim()
                if (left.isEmpty() || right.isEmpty()) continue
                if (norm(left) == s) variants.add(norm(right))
                else if (norm(right) == s) variants.add(norm(left))
            }
            if (nr.contains(s)) variants.add(nr.replace(s, "").trim('-', '–', '—', '_', ' ', '.'))
        }
        for (a in splitArtists(candidate.singer)) {
            val an = norm(a)
            if (an.isEmpty()) continue
            if (nr == n + "-" + an || nr == an + "-" + n) return true
        }
        return variants.any { it.isNotEmpty() && it == n }
    }

    /**
     * 用某个搜索词跑一轮：先带歌手搜，没有合格项再只搜歌名。
     *
     * [local] 是拿来比对的名字（整串里可能含歌手），[query] 是实际搜索词。
     * 带歌手的搜索**本身就搜不到东西**时才改用歌名单搜 —— 有结果但都对不上，
     * 说明这批候选里就是没有，再搜一次只是白花时间（批量匹配时这一步很贵）。
     */
    private fun hit(local: String, query: String, singer: String, duration: Int): Borrowed? {
        if (singer.isNotEmpty()) {
            val bySinger = SearchApi.search("$query $singer", 1, 12)
            pick(bySinger, local, singer, duration)?.let { return it }
            // 有结果、且其中确实有同名候选 → 这批就是没有合格的, 再按歌名搜一遍是白花时间。
            // 但如果连一条同名都没有, 说明「歌名 + 歌手」这个长串把搜索带偏了
            // (本地歌手常带罗马音/「ft.」/上传者名), 这时必须再按歌名搜一次。
            if (bySinger.isNotEmpty() && bySinger.any { nameHit(it, local) }) return null
        }
        return pick(SearchApi.search(query, 1, 12), local, singer, duration)
    }

    /**
     * 从候选里挑一个：歌名全字相等，且作者与时长都对得上。
     *
     * 先挑带专辑的 —— 没有 albumMid 的结果借不来封面（QQ 音乐有一部分数据就是空的），
     * 同一个歌名往往有好几条，排在前面那条恰好是空的。
     */
    private fun pick(
        list: List<Song>,
        name: String,
        singer: String,
        duration: Int,
        matcher: (Song, String) -> Boolean = { c, n -> nameHit(c, n) }
    ): Borrowed? {
        if (list.isEmpty()) return null
        // 硬条件只有两条: 歌名对得上、时长对得上。
        // 歌手只是「加分项」—— 同一个歌手在两边写法常常不同 (宇多田ヒカル / 宇多田光、
        // 凛として時雨 / 凛冽时雨、HOPE-T / HOPE T), 拿它当门槛会把明明能匹配的整片漏掉。
        val base = list.filter { matcher(it, name) && durationHit(it.durationSec, duration) }
        if (base.isNotEmpty()) {
            // 歌手也对得上的优先
            val strict = if (singer.isEmpty()) base else base.filter { artistHit(it.singer, singer) }
            if (strict.isNotEmpty()) {
                val best = strict.firstOrNull { it.albumMid.isNotEmpty() } ?: strict.first()
                return best.toBorrowed()
            }
            // 歌手不符: 歌名 + 时长 5 秒内仍然放行 (用户定的规则: 歌手写法差异太大,
            // 歌名和时长都对上就基本是同一首), 但记一条日志方便回查
            val near = base.filter {
                duration <= 0 || it.durationSec <= 0 ||
                    kotlin.math.abs(it.durationSec - duration) <= DURATION_TOLERANCE_SEC
            }
            if (near.isNotEmpty()) {
                // 同一条歌名+时长往往有好几个版本 (原唱、翻奏、上传者的重制), 挑的时候
                // 先看作者像不像, 再看有没有专辑封面, 都没有才退回第一条。
                val best = near.firstOrNull { it.albumMid.isNotEmpty() && artistLooksSame(it.singer, singer) }
                    ?: near.firstOrNull { artistLooksSame(it.singer, singer) }
                    ?: near.firstOrNull { it.albumMid.isNotEmpty() }
                    ?: near.first()
                Diag.i(
                    "LocalMeta",
                    "仅歌名时长命中 《$name》/$singer -> ${best.name}/${best.singer}/${best.durationSec}s"
                )
                return best.toBorrowed()
            }
        }
        // 没命中时把候选打出来, 方便查「是搜不到、还是被时长挡掉了」。
        // 批量匹配一次上千首, 全打会把 boot.log 撑爆 (触发截断反而丢证据), 限个总量。
        if (candidateLogs.incrementAndGet() <= MAX_CANDIDATE_LOGS) {
            Diag.i(
                "LocalMeta",
                "候选(${list.size}) 目标《$name》/$singer/${duration}s: " +
                    list.take(5).joinToString("; ") { "${it.name}/${it.singer}/${it.durationSec}s" }
            )
        }
        return null
    }

    private fun Song.toBorrowed(): Borrowed? {
        if (albumMid.isEmpty() && mid.isEmpty()) return null
        // 在线结果也可能没有歌手 (纯音乐/伴奏), 那种就不要覆盖本地的占位名
        return Borrowed(mid, albumMid, albumName, if (isUnknownSinger) "" else singer.trim())
    }

    /**
     * 候选歌名和本地名字是否「全字」对得上。
     *
     * 除了完全相同，还认两种拼法：`歌名 - 歌手` 与 `歌手 - 歌名` —— 本地文件名、
     * 没有 ID3 的标题常常是这两种（《The Fear-The Score》《高橋洋子 - 魂のルフラン》），
     * 直接比会永远比不过干净的候选名。破折号粘连、两边留空格、全角半角、大小写都不计较。
     */
    private fun nameHit(candidate: Song, local: String): Boolean {
        // 候选名也要先 cleanName: 线上歌名常带版本注释
        // (《Fly Me To The Moon (In Other Words) (2007 MIX / 2021 Remastered)》),
        // 本地名在 search() 开头已经清过一遍, 两边不清成同一个样子就永远对不上。
        val n = norm(cleanName(candidate.name))
        val l = norm(local)
        if (n.isEmpty() || l.isEmpty()) return false
        if (n == l) return true
        for (a in splitArtists(candidate.singer)) {
            val an = norm(a)
            if (an.isEmpty()) continue
            if (l == n + "-" + an || l == an + "-" + n) return true
        }
        return false
    }

    /**
     * 比对用的规范化：小写、去重音、统一破折号与引号、去掉所有空白（含全角空格）。
     *
     * 去重音是必需的：文件名里常写成 `Isak`，线上是 `ISÁK`；`Cö shu Nie` 同理。
     * 撇号也要统一：标签里写的是 `I’m a piano`（U+2019），线上是 `I'm a piano`
     * （U+0027），不折叠就是差一个字符、整首歌永远对不上。
     */
    private fun norm(raw: String): String = java.text.Normalizer
        .normalize(raw, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[’‘`´ʼ]"), "'")
        .replace(Regex("[“”‟]"), "\"")
        .replace(Regex("[–—－]"), "-")
        .replace(Regex("[／]"), "/")
        .replace(Regex("[\\s\\u3000]+"), "")

    /**
     * 作者是否对应。
     *
     * 本地没有歌手（未知歌手）时不拿它当条件 —— 这种情况正是「借歌手」要解决的；
     * 本地有歌手、候选没歌手则判失败。两边都按 `/`、`、`、`&`、`,` 拆开，
     * 只要存在一对「包含」关系就算对上（`Aimer` vs `Aimer/澤野弘之`）。
     */
    private fun artistHit(candidate: String, local: String): Boolean {
        if (local.isEmpty()) return true
        if (candidate.isEmpty()) return false
        val a = splitArtists(candidate)
        val b = splitArtists(local)
        return a.any { c -> b.any { w -> c.contains(w) || w.contains(c) } }
    }

    private fun splitArtists(raw: String): List<String> =
        raw.split('/', '\\', '_', '|', '、', '&', ',', '，', ';', '；')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    /**
     * 本地那半边（可能是多个歌手）和候选作者是否对得上。
     *
     * `Don't Say-The Chainsmokers,Emily Warren` 这种把多位作者用逗号写在一起的文件名，
     * 只拿整串去比单个作者永远比不上；这里两边都拆开，**任意一项命中**就算
     * 歌手对上了 —— 文件名里的作者列表常比线上多写上一位（
     * `Alone-Alan Walker_Noonie Bao` 对 `Alone/Alan Walker`），名字和时长已经把
     * 范围收得足够窄，这里只负责确认「确实是这些作者之一」。
     */
    private fun artistOverlaps(candidate: String, local: String): Boolean {
        val c = splitArtists(candidate).map { norm(it) }.filter { it.isNotEmpty() }
        val l = splitArtists(local).map { norm(it) }.filter { it.isNotEmpty() }
        if (c.isEmpty() || l.isEmpty()) return false
        return l.any { it in c }
    }

    /**
     * 两边的作者名是不是「同一个人换个写法」。
     *
     * 只看有没有共同汉字：`宇多田ヒカル` / `宇多田光` 共享「宇多田」，`鷺巣詩郎` / `鹭巣诗郎`
     * 共享「巣」「郎」；而 `三轮学` / `市川淳`、`陈亮` / `Lionad` 一个字都不共享，直接否掉。
     * 共同汉字至少两个才认，单个字（`李`）太容易误判。
     */
    private fun artistLooksSame(candidate: String, local: String): Boolean {
        val c = splitArtists(candidate).map { norm(it) }.filter { it.isNotEmpty() }
        val l = splitArtists(local).map { norm(it) }.filter { it.isNotEmpty() }
        for (a in c) for (b in l) {
            val common = a.toSet().intersect(b.toSet())
            val han = common.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            if (han >= 2) return true
            // 拉丁名要求「高度重合」而不是「有几个相同字母」: `Steerner` 和
            // `Imagine Dragons` 也有 4 个共同字母, 但那是两回事。
            val latin = common.count { it.isLetterOrDigit() && it.code < 128 }
            val shorter = kotlin.math.min(a.length, b.length)
            if (shorter > 0 && latin.toDouble() / shorter >= 0.8) return true
        }
        return false
    }

    /** 时长是否对应；任一侧读不到（0）就跳过这一关，不能因为拿不到时长就判死 */
    private fun durationHit(
        candidate: Int,
        local: Int,
        tolerance: Int = DURATION_TOLERANCE_SEC
    ): Boolean {
        if (local <= 0 || candidate <= 0) return true
        return kotlin.math.abs(candidate - local) <= tolerance
    }

    /** 版本词：出现这些词基本可以确定是同一首歌的另一个混音/剪辑版本 */
    private val MIX_WORDS = Regex(
        """(?i)\b(original\s+mix|original\s+version|remix|remixed|rmx|extended\s+mix|extended|radio\s+edit|club\s+mix|dub\s+mix|bootleg|mashup|rework|vip\s+mix)\b"""
    )

    /**
     * 剥掉歌名里的混音/版本词，用于第二步匹配。
     *
     * 括号里的整段直接删（`（Original Mix）`），括号外的单词也删（`Song Remix`），
     * 最后清掉残留的「- 」和多余空格。
     */
    fun stripMixWords(name: String): String {
        var s = name.replace(
            Regex("""(?i)[（(\[【][^）)\]】]*\b(mix|remix|rmx|version|edit|bootleg|rework)\b[^）)\]】]*[）)\]】]"""),
            " "
        )
        s = s.replace(MIX_WORDS, " ")
        s = s.replace(Regex("""(?i)\s*[-–—]\s*$"""), "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 去掉版本后缀和文件名的多余装饰。
     *
     * 处理的形态：`(Remastered 2011)`、`[Live]`、`（伴奏）`、`- Single`、
     * `feat. xxx` / `ft. xxx`、末尾的 `MV` / `Official`、以及 `01.` `01 -` 这种序号前缀。
     */
    fun cleanName(raw: String): String {
        var s = raw.trim()
        // 序号前缀: 必须跟标点 (`01.` `03-`) 或「空格 + 破折号」(`01 - 歌名`)。
        // 只跟空格的不算 —— 《0 (zero)-LMYK》的 0 是歌名的一部分, 吃掉就对不上了。
        s = s.replace(Regex("""^\d{1,3}(?:[.\-_]\s*|\s+[-–—]\s+)"""), "")
        // 括号里的版本说明，中英文括号都算
        s = s.replace(Regex("""[（(\[【][^）)\]】]*[）)\]】]"""), " ")
        // feat. / ft. 往后全砍。点号可省 —— 文件名里 `Apologize Feat OneRepublic` 很常见,
        // 只认带点的话整串会留在歌名里, 后面怎么比都对不上。
        s = s.replace(Regex("""(?i)\s(feat|ft)\.?\s+.*$"""), " ")
        s = s.replace(Regex("""(?i)\s(feat|ft)\..*$"""), " ")
        // 破折号后面是版本说明（- Single / - Live / - Remastered），但保留「歌手 - 歌名」里的歌名本身
        s = s.replace(Regex("""(?i)\s+-\s+(single|live|remaster(ed)?|demo|acoustic|instrumental|karaoke|off\s?vocal)\b.*$"""), " ")
        s = s.replace(Regex("""(?i)\s+(official|mv|m/v|audio|lyric\s?video|hd|hq)\s*$"""), " ")
        // 有些文件把同一个名字写了两遍: `作曲家;作曲家`、`自拍;自拍`
        run {
            val parts = s.split(';', '；').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2 && parts.distinct().size == 1) s = parts[0]
        }
        return s.replace(Regex("""\s+"""), " ").trim()
    }
}
