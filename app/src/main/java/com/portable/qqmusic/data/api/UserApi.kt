package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Crypto
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Http
import com.portable.qqmusic.core.J
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.MetaCache
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户资料库相关接口: 收藏、自建歌单、收藏歌单、我喜欢。
 *
 * 接口选择依据 (均已实测):
 *  - 自建歌单: legacy `c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss` → `data.disslist[]`
 *  - 收藏音乐: musicu `music.musicasset.SongFavRead / GetUserFavSongList`
 *  - 我喜欢的音乐: musicu `playlist.PlayListPlayDetailService / GetPlayListDetail` (disstid=201)
 *  - 收藏的歌单: legacy `c.y.qq.com/folder/fcgi-bin/fcg_qm_order_diss.fcg`
 *
 * 全部需要登录态 (Cookie + g_tk)。
 */

/**
 * 「我喜欢」/ 歌单写操作的结果。
 *
 * 以前只回 Boolean, 失败时调用方只能笼统弹一句「操作失败」。带上 [code] 之后才能分开:
 * 客户端侧的原因 (没登录、参数不全、网络异常) 给不同提示, 服务端的驳回码直接把数字透出来。
 * 唯一特殊对待的是 [CODE_NEED_WEB_SIGN] —— 它是明文 `AddSonglist` 被挡的码, 只有这种失败
 * 才值得换网页签名通道重试 (那条路要等 6~9 秒, 不能什么失败都去走)。
 */
class FavoriteResult(val ok: Boolean, val code: Int) {

    val notLoggedIn: Boolean get() = code == CODE_NOT_LOGGED_IN

    val badArgument: Boolean get() = code == CODE_BAD_ARGUMENT

    val networkError: Boolean get() = code == CODE_NETWORK

    val needsWebSign: Boolean get() = code == CODE_NEED_WEB_SIGN

    companion object {
        const val CODE_OK = 0
        const val CODE_NOT_LOGGED_IN = -1

        /** dirId / songId 不合法, 发出去也没意义 */
        const val CODE_BAD_ARGUMENT = -2

        /** 请求抛异常 (超时、断网、JSON 解析失败) */
        const val CODE_NETWORK = -3

        /** 历史注释里的 80105: g_tk 算错时 `AddSonglist` 恒回这个码 */
        const val CODE_NEED_WEB_SIGN = 80105
    }
}

object UserApi {

    /** 我喜欢歌单的固定 id */
    const val FAVORITE_DISSTID = 201L

    /** 「我喜欢」在用户目录中的 dirid */
    const val FAVORITE_DIRID = 201L

    /** 系统目录「QZone背景音乐」, 只读、不参与「加入歌单」, 展示层隐藏 */
    private const val DIRID_QZONE_BGM = 205L

    /** 系统目录「本地上传」, 同上 */
    private const val DIRID_LOCAL_UPLOAD = 206L

    /** 翻「我喜欢」/ 查歌单内容时的分页大小 */
    private const val FAV_PAGE = 500

    // ---------------- 资料库聚合 ----------------

    class LibraryData(
        val created: List<Playlist>,
        val collected: List<Playlist>,
        val favoriteSongCount: Int
    )

    fun library(useCache: Boolean = true): LibraryData? {
        if (!Login.isLoggedIn()) return null
        val created = createdPlaylists(useCache = useCache)
        val collected = collectedPlaylists(useCache = useCache)
        // 「我喜欢」的歌曲数来自自建歌单列表里的 dirid=201 项
        val favCount = created.firstOrNull { it.dirid == FAVORITE_DIRID }?.songCount ?: 0
        return LibraryData(created, collected, favCount)
    }

    /** 元数据本地缓存的 key (见 [MetaCache]) */
    private const val KEY_CREATED = "user_created_playlists"    // v2: 加上了 tid 字段。旧缓存里 tid 恒为 0, 拿它去取歌曲必然失败, 所以换 key
    // 让它自然失效, 而不是读出来再逐项判空 (那样每次冷启动都要白读一次大 JSON)。
    private const val KEY_COLLECTED = "user_collected_playlists_v2"

    private fun appContext(): android.content.Context? =
        com.portable.qqmusic.App.instance

    /**
     * 用户目录歌单 (我喜欢 / 自建歌单) 的歌曲。
     *
     * 正确调用: `music.srfDissInfo.DissInfo / CgiGetDiss`
     * ```
     * param = { "disstid": 0, "dirid": 201, "song_begin": 0, "song_num": N, "tag": 1 }
     * ```
     * **关键: `disstid` 必须是 0** —— 它表示「用户目录」, 具体歌单由 `dirid` 定位。
     * 传 `disstid=201` 只会得到 `code=10004` (param error)。
     * 实测 `dirid=201` 返回 50 首(该账号共 1480 首收藏)。
     */
    fun userDirSongs(dirid: Long, limit: Int = 300): List<Song> {
        if (!Login.isLoggedIn()) return emptyList()
        return try {
            val param = JSONObject()
                .put("disstid", 0)
                .put("dirid", dirid)
                .put("song_begin", 0)
                .put("song_num", limit)
                .put("tag", 1)
            val resp = MusicU.call("music.srfDissInfo.DissInfo", "CgiGetDiss", param)
            val mod = resp.mod(MusicU.DEFAULT_KEY)
            val arr = mod.data?.optJSONArray("songlist")
            Diag.i("UserApi", "用户目录 dirid=$dirid code=${mod.code} 歌曲=${arr?.length() ?: 0}")
            if (arr != null) Song.listFrom(arr) else emptyList()
        } catch (t: Throwable) {
            Diag.e("UserApi", "用户目录 dirid=$dirid 失败", t)
            emptyList()
        }
    }

    /** 收藏的音乐 = 「我喜欢」(dirid=201) */
    fun favoriteSongs(limit: Int = 300): List<Song> = userDirSongs(FAVORITE_DIRID, limit)

    // ---------------- 自建歌单 ----------------

    /**
     * 自建歌单 (legacy 接口, 实测返回 `data.disslist[]`)。
     *
     * ⚠️ 该接口字段是**下划线**命名且用 `dirid` 标识:
     * `diss_name` / `diss_cover` / `song_cnt` / `listen_num` / `dirid`
     * (`dissid` 与 `dissname` 均为 null)。解析见 [Playlist.fromJson]。
     */
    fun createdPlaylists(limit: Int = 200, useCache: Boolean = true): List<Playlist> {
        if (!Login.isLoggedIn()) return emptyList()
        val ctx = appContext()
        // 先看本地: 冷启动/断网时这是唯一能立刻出内容的地方
        if (useCache && ctx != null) {
            val cached = Playlist.listFromCache(MetaCache.get(ctx, KEY_CREATED))
            if (cached.isNotEmpty()) {
                Diag.i("UserApi", "自建歌单命中本地缓存 ${cached.size} 个")
                return visiblePlaylists(cached)
            }
        }
        val uin = Login.uin()
        val gtk = com.portable.qqmusic.core.Crypto.gtk(Login.skey())
        val url = "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss" +
            "?hostUin=0&hostuin=$uin&sin=0&size=$limit&g_tk=$gtk" +
            "&loginUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0" +
            "&platform=yqq.json&needNewCode=0"
        return try {
            val resp = Http.get(url, referer = "https://y.qq.com/portal/profile.html")
            val root = J.obj(resp.body) ?: return emptyList()
            val data = J.obj(root, "data")
            val arr = data?.optJSONArray("disslist")
                ?: data?.optJSONArray("list")
                ?: J.arr(root, "disslist")
            if (arr == null) {
                Diag.i("UserApi", "自建歌单无 disslist, 顶层键=[${root.keys().asSequence().joinToString(",")}]")
                return emptyList()
            }
            val out = ArrayList<Playlist>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                Playlist.fromJson(o)?.let { out.add(it.copy(isSelfCreated = true)) }
            }
            Diag.i(
                "UserApi",
                "自建歌单 ${out.size} 个: " +
                    out.joinToString(", ") { "${it.title}(dirid=${it.dirid},${it.songCount}首)" }.take(200)
            )
            if (ctx != null && out.isNotEmpty()) {
                MetaCache.put(ctx, KEY_CREATED, Playlist.listToJson(out).toString())
            }
            visiblePlaylists(out)
        } catch (t: Throwable) {
            Diag.e("UserApi", "自建歌单请求失败", t)
            emptyList()
        }
    }

    /**
     * 去掉两个「系统自带、但不是用户歌单」的目录。
     *
     * `dirid=205` 是「QZone背景音乐」、`206` 是「本地上传」—— 它们混在自建歌单列表里,
     * 既不能删也不该作为「加入歌单」的目标, 所以展示层统一过滤掉。
     * 缓存里仍然存全量, 将来要展示随时能拿回来。
     */
    private fun visiblePlaylists(list: List<Playlist>): List<Playlist> =
        list.filterNot { it.dirid == DIRID_QZONE_BGM || it.dirid == DIRID_LOCAL_UPLOAD }

    // ---------------- 收藏的歌单 (他人歌单) ----------------

    /**
     * 收藏的歌单 —— 两步走 (均已实测):
     *
     * 1. `music.musicasset.PlaylistFavRead / GetPlaylistFavList{uin,offset,size}`
     *    → `data.v_tids[]`, 是一串**纯 tid 数字** (该账号 26 个), 不含歌单信息。
     * 2. 用 tid 走 legacy `fcg_ucc_getcdinfo_byids_cp.fcg?disstid=<tid>`
     *    → `cdlist[0]` 有歌单名 / 封面 / 歌曲 (实测某 tid 返回 51 首)。
     *
     * 注意: `CgiGetDiss` 与 `PlayListPlayDetailService` 都**不接受 tid**
     * (分别回 param error / 500003), 只有上面这个 legacy 接口认。
     */
    fun collectedPlaylists(limit: Int = 60, useCache: Boolean = true): List<Playlist> {
        if (!Login.isLoggedIn()) return emptyList()
        val ctx = appContext()
        // 收藏歌单要逐个 tid 打 4 个并发请求, 是首页最慢的一环, 缓存收益最大
        if (useCache && ctx != null) {
            val cached = Playlist.listFromCache(MetaCache.get(ctx, KEY_COLLECTED))
            if (cached.isNotEmpty()) {
                Diag.i("UserApi", "收藏歌单命中本地缓存 ${cached.size} 个")
                return cached
            }
        }
        val tids = favoritePlaylistTids(limit)
        if (tids.isEmpty()) return emptyList()

        // 逐个取详情: legacy 单次一个 tid, 用线程池并行以免切页卡住
        val out = java.util.Collections.synchronizedList(ArrayList<Playlist>(tids.size))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val futures = tids.map { tid ->
                pool.submit { legacyPlaylist(tid)?.let { out.add(it) } }
            }
            futures.forEach { f ->
                try {
                    f.get(15, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Throwable) {
                }
            }
        } finally {
            pool.shutdownNow()
        }
        Diag.i("UserApi", "收藏歌单 tid=${tids.size} 成功解析=${out.size} 个")
        if (ctx != null && out.isNotEmpty()) {
            MetaCache.put(ctx, KEY_COLLECTED, Playlist.listToJson(out).toString())
        }
        return out
    }

    /** 收藏歌单的 tid 列表 */
    private fun favoritePlaylistTids(limit: Int): List<Long> {
        return try {
            val param = JSONObject()
                .put("uin", Login.uin())
                .put("offset", 0)
                .put("size", limit)
            val resp = MusicU.call("music.musicasset.PlaylistFavRead", "GetPlaylistFavList", param)
            val mod = resp.mod(MusicU.DEFAULT_KEY)
            val arr = mod.data?.optJSONArray("v_tids")
            Diag.i("UserApi", "收藏歌单 tid code=${mod.code} v_tids=${arr?.length() ?: 0}")
            if (arr == null) return emptyList()
            val out = ArrayList<Long>(arr.length())
            for (i in 0 until arr.length()) {
                val t = arr.optLong(i, 0L)
                if (t != 0L) out.add(t)
            }
            out
        } catch (t: Throwable) {
            Diag.e("UserApi", "收藏歌单 tid 失败", t)
            emptyList()
        }
    }

    /** legacy 歌单详情 (接受 tid 作为 disstid) */
    private fun legacyPlaylist(tid: Long): Playlist? {
        return try {
            val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
                "?type=1&json=1&utf8=1&onlysong=0&disstid=$tid&format=json" +
                "&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0"
            val resp = Http.get(url, referer = "https://y.qq.com/")
            val root = J.obj(resp.body) ?: return null
            val cd = root.optJSONArray("cdlist")?.optJSONObject(0) ?: return null
            // tid 存进 [Playlist.tid], 不要塞进 disstid: 那个字段是专辑/榜单的字符串 id。
            // 混用会让详情页拿 tid 去问 CgiGetDiss (不认 tid), 或者拿 dissid 去问
            // legacy (不认 dissid), 两边都拿不到歌曲。
            Playlist.fromJson(cd)?.copy(isSelfCreated = false, tid = tid)
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------- 写操作 ----------------

    /**
     * 收藏 / 取消收藏歌曲。
     *
     * 走「往 dirid=201 的歌单加/删歌」:
     * `music.musicasset.PlaylistDetailWrite / AddSonglist|DelSonglist { dirId: 201 }`。
     *
     * 这条请求一度恒回 `code=80105`, 排查了很久才发现是 **g_tk 算错**:
     * 早先 `Login.skey()` 优先取 `p_skey`(QQ 空间的票据), 并且 `Crypto.gtk()` 还多算了一个
     * `% 1000000`。网页版实际用的是 `hash33(qm_keyst)` 的**完整值**(实测 `759472775`),
     * 两个 `g_tk` 字段都填它。改对之后同一个请求直接 `code=0`, 歌是真的进「我喜欢」了
     * (`IsSongFanByMid` 复查为 `true`, 歌单条数 1480 → 1481)。
     *
     * 走明路的 `AddSonglist` 对自建歌单和「我喜欢」都可用, 不需要碰网页版的
     * `musics.fcg?encoding=ag-1&sign=...` 那条 VMP 签名通道。
     *
     * ⚠️ 2026-10-19 复测: 同一个账号下 `DelSonglist` 仍回 `code=0`, 但 `AddSonglist`
     * 又稳定回 `80105` —— 说明不只是 g_tk 的问题, 添加这条多半还认别的字段
     * (官方文档里是 `songId/songType/tid` 三个一起传)。见 6.67 末段的待验证清单。
     */
    fun setFavorite(song: Song, favorite: Boolean): FavoriteResult {
        if (!Login.isLoggedIn()) return FavoriteResult(false, FavoriteResult.CODE_NOT_LOGGED_IN)
        val method = if (favorite) "AddSonglist" else "DelSonglist"
        val result = writeSongToPlaylist(FAVORITE_DIRID, song, method)
        Diag.i(
            "UserApi",
            "$method 我喜欢 mid=${song.mid} songId=${song.songId} ok=${result.ok} code=${result.code}"
        )
        // 成功后就地改缓存里这一个 mid, 不再整体清空 (否则下一首歌又要翻三页)。
        if (result.ok) markFavorite(song.mid, favorite)
        return result
    }

    /**
     * 把一首歌加进自建歌单。
     *
     * `dirId` 要的是**目录 id**(dirid)。自建歌单的 disstid 与 dirid 通常相同,
     * 但收藏的别人歌单不同, 调用方自己保证传对。
     *
     * `songType` 固定 0 (普通歌曲): `Song.type` 是本项目自定义的在线/本地标记,
     * 不是服务端那个 songType。
     */
    fun addSongToPlaylist(dirId: Long, song: Song): Boolean {
        val ok = writeSongToPlaylist(dirId, song, "AddSonglist").ok
        // 从「加入歌单」里选到「我喜欢」时, 服务端效果和收藏一样, 本地状态也得跟上。
        if (ok && dirId == FAVORITE_DIRID) markFavorite(song.mid, true)
        return ok
    }

    /** 从自建歌单里移除一首歌。只解除歌单与歌的关系, 不动文件。 */
    fun removeSongFromPlaylist(dirId: Long, song: Song): Boolean {
        val ok = writeSongToPlaylist(dirId, song, "DelSonglist").ok
        // 在「我喜欢」列表里点「从歌单中移除」等同于取消收藏, 同样要广播出去,
        // 否则播放页的爱心还亮着 (用户反馈的现象)。
        if (ok && dirId == FAVORITE_DIRID) markFavorite(song.mid, false)
        return ok
    }

    /**
     * 单曲是否在「我喜欢」里。
     *
     * `music.musicasset.SongFavRead / IsSongFanByMid { v_songMid: [mid] }` →
     * `data.m_fan[mid]`。比 [favoriteMids] 翻 1476 首的列表快得多, 用来复查
     * 网页版那条路有没有真把歌加进去。
     */
    fun isSongFan(mid: String): Boolean {
        if (mid.isEmpty() || !Login.isLoggedIn()) return false
        return try {
            val param = JSONObject().put("v_songMid", JSONArray().put(mid))
            val mod = MusicU.call("music.musicasset.SongFavRead", "IsSongFanByMid", param)
                .mod(MusicU.DEFAULT_KEY)
            val fan = mod.data?.optJSONObject("m_fan") ?: return false
            val value = fan.optBoolean(mid, false)
            Diag.i("UserApi", "IsSongFanByMid mid=$mid code=${mod.code} fan=$value")
            value
        } catch (t: Throwable) {
            Diag.e("UserApi", "IsSongFanByMid 失败 mid=$mid", t)
            false
        }
    }

    private fun writeSongToPlaylist(dirId: Long, song: Song, method: String): FavoriteResult {
        if (!Login.isLoggedIn()) return FavoriteResult(false, FavoriteResult.CODE_NOT_LOGGED_IN)
        if (dirId <= 0L || song.songId <= 0L) {
            return FavoriteResult(false, FavoriteResult.CODE_BAD_ARGUMENT)
        }
        return try {
            val info = JSONObject().put("songType", 0).put("songId", song.songId)
            val param = JSONObject().put("dirId", dirId).put("v_songInfo", JSONArray().put(info))
            val mod = MusicU.call("music.musicasset.PlaylistDetailWrite", method, param)
                .mod(MusicU.DEFAULT_KEY)
            Diag.i("UserApi", "$method dirId=$dirId song=${song.name} code=${mod.code} ok=${mod.isOk}")
            // 写操作只认 code == 0: 这两个方法成功时 data 可能是空对象甚至缺省,
            // 用 Mod.isOk (还要求 data 非 null) 会把成功误判成失败。
            FavoriteResult(mod.code == FavoriteResult.CODE_OK, mod.code)
        } catch (t: Throwable) {
            Diag.e("UserApi", "$method 失败 dirId=$dirId", t)
            FavoriteResult(false, FavoriteResult.CODE_NETWORK)
        }
    }

    /**
     * 这个歌单里是否已经有这首歌 —— 「加入歌单」不允许重复添加, 靠它拦。
     *
     * 只能自己翻页比 mid: 服务端没有「单曲是否在某歌单」的查询, 而 `AddSonglist`
     * 对重复项未必回错误码, 不能指望它去重。
     */
    fun playlistHasSong(dirId: Long, song: Song, maxPages: Int = 8): Boolean {
        if (dirId <= 0L || song.mid.isEmpty()) return false
        var begin = 0
        for (i in 0 until maxPages) {
            val (list, raw) = PlaylistApi.playlistSongsPage(0L, dirId, begin, FAV_PAGE)
            if (list.any { it.mid == song.mid }) return true
            if (raw < FAV_PAGE) return false
            begin += FAV_PAGE
        }
        return false
    }

    @Volatile
    private var favoriteMidsCache: Set<String>? = null

    /**
     * 这次进程里用户亲手改过的收藏状态。
     *
     * 背景预热是全量拉取的, 拉回来时可能已经落后于用户刚才那一下点击;
     * 建表时把这里的改动盖上去, 才不会出现「刚收藏完, 切走再回来又变未收藏」。
     */
    private val localFavoriteEdits = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private val favoriteWarming = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 收藏状态变了几次。
     *
     * 「我喜欢」列表页靠它判断「我离开的这段时间里收藏有没有变过」—— 变了就重新拉,
     * 否则取消收藏之后回到列表还是旧内容 (用户反馈的正是这个)。
     */
    @Volatile
    var favoriteRevision: Int = 0
        private set

    /** 收藏变化统一走这里, 界面各自把爱心/列表同步过来。回调可能在 IO 线程。 */
    interface FavoriteListener {
        fun onFavoriteChanged(mid: String, favorite: Boolean)
    }

    private val favoriteListeners =
        java.util.concurrent.CopyOnWriteArrayList<FavoriteListener>()

    fun addFavoriteListener(l: FavoriteListener) {
        favoriteListeners.addIfAbsent(l)
    }

    fun removeFavoriteListener(l: FavoriteListener) {
        favoriteListeners.remove(l)
    }

    /**
     * 只读缓存。返回 null 表示还没查过 —— 弹菜单这类「不能等网络」的地方用它,
     * 命中就直接判状态, 没命中再自己决定要不要异步补。
     */
    fun favoriteMidsCached(): Set<String>? = favoriteMidsCache

    /**
     * 单曲收藏状态 —— 界面判断爱心亮不亮的唯一入口。
     *
     * 顺序是「本地刚改的 → 整表缓存 → 单查这一首」。第一档很重要: 点下去之后、服务端
     * 还没答复的那几秒里, 用户退出去再进播放页也应该看到新状态, 而不是一次旧查询的结果。
     */
    fun favoriteState(mid: String): Boolean {
        if (mid.isEmpty() || !Login.isLoggedIn()) return false
        localFavoriteEdits[mid]?.let { return it }
        favoriteMidsCache?.let { return it.contains(mid) }
        warmUpFavoriteMids()
        return isSongFan(mid)
    }

    /**
     * 收藏状态变了, 就地增删缓存里的这一个 mid, 并把新状态广播出去。
     *
     * 以前是 [invalidateFavoriteMids] 整体清空, 于是收藏一首之后, 下一首歌又得重新翻三页。
     * 缓存还没建好 (null) 时只记进 [localFavoriteEdits], 等全量拉回来再盖上去。
     *
     * 这个方法同时承担「乐观更新」: 点击时先按目标状态写一遍, 失败再写回来。
     */
    fun markFavorite(mid: String, favorite: Boolean) {
        if (mid.isEmpty()) return
        localFavoriteEdits[mid] = favorite
        favoriteMidsCache?.let { cur ->
            favoriteMidsCache = if (favorite) cur + mid else cur - mid
        }
        favoriteRevision++
        favoriteListeners.forEach { runCatching { it.onFavoriteChanged(mid, favorite) } }
    }

    /** 后台把「我喜欢」整表拉一次, 让后续的单曲判断都走缓存; 已经在拉就不重复触发。 */
    private fun warmUpFavoriteMids() {
        if (favoriteMidsCache != null || !Login.isLoggedIn()) return
        if (!favoriteWarming.compareAndSet(false, true)) return
        Task.io {
            try {
                favoriteMids()
            } catch (t: Throwable) {
                Diag.e("UserApi", "预热我喜欢失败", t)
            } finally {
                favoriteWarming.set(false)
            }
        }
    }

    /**
     * 「我喜欢」里所有歌的 mid。
     *
     * 进程内缓存, 只给预热和需要整表的场景用; 单纯判断一首歌用 [favoriteState]。
     */
    fun favoriteMids(force: Boolean = false): Set<String> {
        favoriteMidsCache?.let { if (!force) return it }
        val out = HashSet<String>()
        var begin = 0
        for (i in 0 until 8) {
            val (list, raw) = PlaylistApi.playlistSongsPage(0L, FAVORITE_DIRID, begin, FAV_PAGE)
            list.forEach { out.add(it.mid) }
            if (raw < FAV_PAGE) break
            begin += FAV_PAGE
        }
        // 拉取期间用户可能刚收藏/取消过, 以本地改动为准。
        localFavoriteEdits.forEach { (mid, fav) -> if (fav) out.add(mid) else out.remove(mid) }
        Diag.i("UserApi", "我喜欢 mid 集合 ${out.size} 个")
        favoriteMidsCache = out
        return out
    }

    /** 整表作废, 下次 [favoriteMids] 重新拉。单曲收藏变化不需要它, 用 [markFavorite]。 */
    fun invalidateFavoriteMids() {
        favoriteMidsCache = null
    }

    /**
     * 删除自建歌单。
     *
     * ⚠️ 参数是 `dirId` (目录 id) 而不是 `disstid` / `tid`。服务端把歌单拆成两个 id:
     * `dirId` 是「我的音乐」目录里的位置, 自建歌单的 `dissid` 与它**不相等**
     * (实测 temp 1: dirid=15, disstid=2236337901)。传歌单 id 会失败,
     * 参考实现 github.com/baoozak/qqmusic-mcp 的 `delete_playlist` 用的也是 dirId。
     *
     * @return true 表示服务端接受了删除
     */
    fun deletePlaylist(dirid: Long): Boolean {
        Diag.i("UserApi", "删除歌单 开始 dirid=$dirid loggedIn=${Login.isLoggedIn()}")
        if (!Login.isLoggedIn()) return false
        if (dirid <= 0L) return false
        return try {
            val param = JSONObject().put("dirId", dirid)
            val resp = MusicU.call("music.musicasset.PlaylistBaseWrite", "DelPlaylist", param)
            val mod = resp.mod(MusicU.DEFAULT_KEY)
            Diag.i("UserApi", "删除歌单 dirid=$dirid code=${mod.code} ok=${mod.isOk}")
            mod.isOk
        } catch (t: Throwable) {
            Diag.e("UserApi", "删除歌单失败 dirid=$dirid", t)
            false
        }
    }

    /**
     * 收藏 / 取消收藏别人创建的歌单。
     *
     * 接口来自 QQ 音乐网页版自己的前端代码 (`/ryqq/js/common.chunk.*.js`),
     * 网页版里就是同一段逻辑的两个分支:
     * ```js
     * PlaylistFavWrite / (取消 ? "CancelFavPlaylist" : "FavPlaylist")
     * param: { uin, v_playlistId: [歌单 tid] }
     * ```
     *
     * ⚠️ 早期实现猜的是 `DelPlaylistFav` + `v_tids`, 服务端一律回 `code=40000`
     * —— 根因是方法名与参数名都不对, 不是权限问题。
     * 这里**不能**退回 `DelPlaylist`: 收藏的歌单不属于自己, 删它只会报错,
     * 而且 `DelPlaylist` 要 dirId, 手里只有 tid, 本来也对不上。
     *
     * @param tid 歌单 id (收藏列表里那一串数字, 不是 dirid)
     * @return true 表示服务端接受了这次操作
     */
    fun setPlaylistFavorite(tid: Long, favorite: Boolean): Boolean =
        writePlaylistFav(tid, favorite)

    fun favoritePlaylist(tid: Long): Boolean = writePlaylistFav(tid, true)

    fun unfavoritePlaylist(tid: Long): Boolean = writePlaylistFav(tid, false)

    private fun writePlaylistFav(tid: Long, favorite: Boolean): Boolean {
        Diag.i(
            "UserApi", "${if (favorite) "收藏" else "取消收藏"}歌单 开始 tid=$tid " +
                "loggedIn=${Login.isLoggedIn()}"
        )
        if (!Login.isLoggedIn()) return false
        if (tid <= 0L) return false
        return try {
            val param = JSONObject()
                .put("uin", Login.uin())
                .put("v_playlistId", org.json.JSONArray().put(tid))
            val mod = MusicU.call(
                "music.musicasset.PlaylistFavWrite",
                if (favorite) "FavPlaylist" else "CancelFavPlaylist",
                param
            ).mod(MusicU.DEFAULT_KEY)
            // 服务端成功时返回 { code: 0, data: { result: 0, v_failedPlaylistId: [] } }
            val failed = mod.data?.optJSONArray("v_failedPlaylistId")?.length() ?: 0
            val ok = mod.isOk && failed == 0
            Diag.i("UserApi", "${if (favorite) "收藏" else "取消收藏"}歌单 tid=$tid code=${mod.code} ok=$ok")
            ok
        } catch (t: Throwable) {
            Diag.e("UserApi", "${if (favorite) "收藏" else "取消收藏"}歌单失败 tid=$tid", t)
            false
        }
    }

    /**
     * 新建歌单。
     *
     * 走 musicu 的 `AddPlaylist` 而不是老的 `create_playlist.fcg` ——
     * 后者在真机上一直返回失败, 而且它要的 `g_tk` 与 musicu 不是同一套算法。
     * 新建与删除是同一组接口 (`PlaylistBaseWrite`), 已登录状态下鉴权方式一致。
     *
     * @return true 表示服务端接受了新建
     */
    fun createPlaylist(name: String): Boolean {
        Diag.i("UserApi", "新建歌单 开始 name=$name loggedIn=${Login.isLoggedIn()}")
        if (!Login.isLoggedIn()) return false
        if (name.isBlank()) return false
        return try {
            val param = JSONObject()
                .put("dirName", name)
                .put("dirDesc", "")
                .put("dirPicUrl", "")
                .put("taglist", org.json.JSONArray())
            val resp = MusicU.call("music.musicasset.PlaylistBaseWrite", "AddPlaylist", param)
            val mod = resp.mod(MusicU.DEFAULT_KEY)
            Diag.i("UserApi", "新建歌单 name=$name code=${mod.code} ok=${mod.isOk}")
            mod.isOk
        } catch (t: Throwable) {
            Diag.e("UserApi", "新建歌单失败 name=$name", t)
            false
        }
    }
}
