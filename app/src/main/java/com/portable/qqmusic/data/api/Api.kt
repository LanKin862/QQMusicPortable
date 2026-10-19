package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.J
import org.json.JSONArray
import org.json.JSONObject

/** 搜索 */
object SearchApi {

    private const val MODULE = "music.search.SearchCgiService"
    private const val METHOD = "DoSearchForQQMusicDesktop"

    /**
     * 综合搜索。
     *
     * ⚠️ param 必须是**平铺**的这几个字段 (与 Mconnect `search()` 一致):
     * `{ num_per_page, page_num, query, search_type }`。
     * 早期我把它们包在 `body`/`data` 两层里, 服务端虽回 200 但取不到 song.list。
     *
     * @param type 0 单曲 / 1 歌手 / 2 专辑 / 3 歌单 / 7 歌词
     */
    fun search(keyword: String, page: Int = 1, pageSize: Int = 20, type: Int = 0): List<Song> {
        val param = JSONObject()
            .put("num_per_page", pageSize)
            .put("page_num", page)
            .put("query", keyword)
            .put("search_type", type)

        val resp = MusicU.call(MODULE, METHOD, param)
        val mod = resp.mod(MusicU.DEFAULT_KEY)
        val data = mod.data
        if (mod.code != 0 || data == null) {
            Diag.i("SearchApi", "搜索 '$keyword' code=${mod.code}, 原始=[${(resp.root?.toString() ?: "null").take(200)}]")
            return emptyList()
        }
        val list = resolveList(data, type)
        val songs = Song.listFrom(list)
        Diag.i("SearchApi", "搜索 '$keyword' page=$page -> ${songs.size} 首 (data键=[${data.keys().asSequence().joinToString(",")}])")
        return songs
    }

    fun searchPlaylist(keyword: String, page: Int = 1, pageSize: Int = 20): List<Playlist> =
        searchPlaylistLegacy(keyword, page, pageSize)

    /**
     * 歌手搜索 (search_type=1)。
     *
     * 与单曲搜索是同一个接口, 只是 `search_type` 不同 —— 服务端每次都会把
     * `song`/`singer`/`songlist`/`album`... 九个键都塞进 body, 但只有对应的那个有数据,
     * 所以三个 tab 必须各发一次请求, 不能一次拿全。
     */
    fun searchSingers(keyword: String, page: Int = 1, pageSize: Int = 20): List<Singer> {
        val data = searchBody(keyword, page, pageSize, TYPE_SINGER) ?: return emptyList()
        val list = resolveList(data, TYPE_SINGER)
        val singers = Singer.listFrom(list)
        Diag.i("SearchApi", "歌手搜索 '$keyword' page=$page -> ${singers.size} 个")
        return singers
    }

    /** 歌单搜索 (search_type=3), 走 musicu 而不是 legacy 网页接口 —— 字段都能对上 [Playlist.fromJson] */
    fun searchPlaylists(keyword: String, page: Int = 1, pageSize: Int = 20): List<Playlist> {
        val data = searchBody(keyword, page, pageSize, TYPE_PLAYLIST) ?: return emptyList()
        val list = resolveList(data, TYPE_PLAYLIST)
        val playlists = Playlist.listFrom(list)
        Diag.i("SearchApi", "歌单搜索 '$keyword' page=$page -> ${playlists.size} 个")
        return playlists
    }

    /** 发一次搜索请求并取回 `body`; 失败返回 null */
    private fun searchBody(keyword: String, page: Int, pageSize: Int, type: Int): JSONObject? {
        val param = JSONObject()
            .put("num_per_page", pageSize)
            .put("page_num", page)
            .put("query", keyword)
            .put("search_type", type)
        val resp = MusicU.call(MODULE, METHOD, param)
        val mod = resp.mod(MusicU.DEFAULT_KEY)
        if (mod.code != 0) {
            Diag.i("SearchApi", "搜索 '$keyword' type=$type code=${mod.code}")
            return null
        }
        val data = mod.data ?: return null
        return data.optJSONObject("body") ?: data
    }

    /** 搜索结果解析。
     *
     * musicu 返回结构 (req_0.data):
     * `{ "body": { "song": { "list": [...] }, "songlist": {...}, ... }, "meta": {...} }`
     */
    private fun resolveList(data: JSONObject, type: Int): JSONArray? {
        val body = data.optJSONObject("body") ?: data
        return when (type) {
            TYPE_SONG -> body.optJSONObject("song")?.optJSONArray("list")
                ?: body.optJSONArray("list")
            TYPE_SINGER -> body.optJSONObject("singer")?.optJSONArray("list")
                ?: body.optJSONArray("list")
            TYPE_ALBUM -> body.optJSONObject("album")?.optJSONArray("list")
                ?: body.optJSONArray("list")
            TYPE_PLAYLIST -> body.optJSONObject("songlist")?.optJSONArray("list")
                ?: body.optJSONArray("list")
            else -> body.optJSONArray("list")
        }
    }

    /*
     * 搜索类型。实测 (2026-01) `search_type` 0~9 的返回:
     * 0 单曲 / 1 歌手 / 2 专辑 / 3 歌单 / 4 MV / 7 歌词命中 / 8 用户, 5/6/9 为空。
     * 请求里 body 九个键都在, 但只有对应类型那个数组有数据。
     */
    const val TYPE_SONG = 0
    const val TYPE_SINGER = 1
    const val TYPE_ALBUM = 2
    const val TYPE_PLAYLIST = 3

    /** 搜索歌单 (legacy 接口, 返回 data.list[]) */
    fun searchPlaylistLegacy(keyword: String, page: Int = 1, pageSize: Int = 20): List<Playlist> {
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist" +
            "?remoteplace=txt.yqq.playlist&page_no=${page - 1}&num_per_page=$pageSize" +
            "&query=$encoded&format=json"
        val resp = com.portable.qqmusic.core.Http.get(url, referer = "https://y.qq.com")
        val root = J.obj(resp.body) ?: return emptyList()
        val list = J.obj(root, "data")?.optJSONArray("list") ?: return emptyList()
        return Playlist.listFrom(list)
    }
}

/** 歌曲相关 */
object SongApi {

    /**
     * 批量获取歌曲详情 (补齐 album / interval / 音质文件大小等字段)。
     * 单次最多 100 首。
     */
    fun detail(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return songs
        val batch = songs.take(100)
        val param = JSONObject()
            .put("songinfo", songInfoArray(batch))
            .put("comm", JSONObject().put("ct", 24).put("cv", 0))

        val resp = MusicU.call("music.pf_song_detail_svr", "get_song_detail_yqq", param, "detail")
        val data = resp.data("detail")
        val trackInfo = data?.optJSONObject("track_info") ?: data?.optJSONObject("trackInfo")
        val list = trackInfo?.optJSONArray("songinfo")
            ?: trackInfo?.optJSONArray("songInfo")
            ?: data?.optJSONArray("songinfo")
        if (list == null) return songs

        val byMid = HashMap<String, Song>(list.length())
        for (s in Song.listFrom(list)) byMid[s.mid] = s
        return songs.map { byMid[it.mid] ?: it }
    }

    private fun songInfoArray(songs: List<Song>): JSONArray {
        val arr = JSONArray()
        for (s in songs) {
            arr.put(
                JSONObject().put("songmid", s.mid).put("songid", s.songId)
                    .put("songtype", 0).put("filename", "C400${s.mid}${s.mid}.m4a")
            )
        }
        return arr
    }

    /**
     * 歌词。
     *
     * ⚠️ `PlayLyricInfo` 返回的 `lyric`/`trans` 是 **base64 编码**的 LRC。
     * 即使带了 `nobase64=1`, 服务端仍然给 base64 —— 直接把它当文本喂给
     * LRC 解析器会一行都解析不出来, 表现就是「歌词页空白」。
     */
    fun lyric(songMid: String): Pair<String, String> {
        val param = JSONObject()
            .put("songmid", songMid)
            .put("songtype", 0)
            .put("format", "json")
            .put("nobase64", 1)
            // trans=1 才会要官方翻译; 不传这个, trans 字段永远是空串
            .put("trans", 1)
            .put("trans_t", 0)
        /*
         * 这里**不能**加 qrc=1 / qrc_t=0。
         *
         * 加了之后服务端会把整个 lyric 字段也一并加密成二进制密文, 于是
         * `Lyrics.parseLines` 一行都解析不出来、界面全是「暂无歌词」——
         * 而且这个失败是静默的(长度还有 4000 多字, 只是内容全是乱码)。
         * 逐字歌词走下面那次带 crypt=1 的独立请求, 那条路不会污染 LRC。
         */
        val resp = MusicU.call(MODULE_LYRIC, METHOD_LYRIC, param)
        val mod = resp.mod(MusicU.DEFAULT_KEY)
        val data = mod.data ?: return "" to ""
        if (mod.code != 0) {
            Diag.i("SongApi", "歌词 code=${mod.code} mid=$songMid")
            return "" to ""
        }
        val lrc = decodeLyric(data.optString("lyric", ""))
        val trans = decodeLyric(data.optString("trans", ""))
        Diag.i("SongApi", "歌词 mid=$songMid 原文=${lrc.length}字 翻译=${trans.length}字")
        /*
         * QRC 逐字歌词不在这里取。
         *
         * 它要带 crypt=1 再发一次请求, 而且那条路上 lyric/trans 都会变成密文 ——
         * 混在同一个函数里既容易污染 LRC, 也让「解码方式变了」这件事散落在两处。
         * 统一走 [com.portable.qqmusic.data.api.qrc.QrcSource]。
         */
        return lrc to trans
    }

    /**
     * 解码歌词。
     *
     * 服务端可能返回 base64 或已是 LRC 明文, 这里两种都兼容:
     * 含 `[` 与 `]` 的判为 LRC 明文直接用, 否则按 base64 解码。
     */
    private fun decodeLyric(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        if (raw.contains('[') && raw.contains(']')) return raw
        return try {
            val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            Diag.i("SongApi", "歌词 base64 解码失败, 按原文处理")
            raw
        }
    }

    private const val MODULE_LYRIC = "music.musichallSong.PlayLyricInfo"
    private const val METHOD_LYRIC = "GetPlayLyricInfo"
}

/** 发现 / 推荐 */
object DiscoverApi {

    /**
     * 每日推荐 —— QQ 音乐推荐页顶部的「每日30首」。
     *
     * 它**不是**雷达接口, 而是一个真实歌单, 要两步:
     *  1. `music.recommend.RecommendFeed / get_recommend_feed`
     *     → `data.v_shelf[].v_niche[].v_card[]`, 找 `title` 含「每日30首」的卡片,
     *       其 `id` 即歌单 disstid (实测 `4797214348`, 每天变化)。
     *  2. `CgiGetDiss { disstid: <id> }` → `songlist` (实测 30 首)。
     *
     * 兜底: 雷达 → 热歌榜 (保证区域不空)。
     */
    fun dailyRecommend(): List<Song> {
        dailyThirtySongs().takeIf { it.isNotEmpty() }?.let {
            Diag.i("Discover", "每日30首 ${it.size} 首")
            return it
        }
        radarSongs().takeIf { it.isNotEmpty() }?.let {
            Diag.i("Discover", "每日推荐: 回退雷达 ${it.size} 首")
            return it
        }
        val hot = topListSongs(TOP_HOT, 30)
        Diag.i("Discover", "每日推荐: 回退热歌榜 ${hot.size} 首")
        return hot
    }

    /** 从推荐货架里取出「每日30首」的歌单 id (找不到返回 0) */
    private fun dailyThirtyId(): Long {
        return try {
            val resp = MusicU.call(
                "music.recommend.RecommendFeed", "get_recommend_feed", JSONObject()
            )
            val shelves = resp.data(MusicU.DEFAULT_KEY)?.optJSONArray("v_shelf") ?: return 0L
            for (i in 0 until shelves.length()) {
                val niches = shelves.optJSONObject(i)?.optJSONArray("v_niche") ?: continue
                for (j in 0 until niches.length()) {
                    val cards = niches.optJSONObject(j)?.optJSONArray("v_card") ?: continue
                    for (k in 0 until cards.length()) {
                        val card = cards.optJSONObject(k) ?: continue
                        if (card.optString("title").contains("每日30首")) {
                            val id = card.optLong("id", 0L)
                            Diag.i(
                                "Discover",
                                "每日30首 卡片: id=$id dirid=${J.str(J.obj(card, "miscellany"), "dirid")}"
                            )
                            if (id != 0L) return id
                        }
                    }
                }
            }
            Diag.i("Discover", "推荐货架里没找到「每日30首」卡片")
            0L
        } catch (t: Throwable) {
            Diag.e("Discover", "解析「每日30首」卡片失败", t)
            0L
        }
    }

    private fun dailyThirtySongs(): List<Song> {
        val id = dailyThirtyId()
        if (id == 0L) return emptyList()
        return try {
            val param = JSONObject()
                .put("disstid", id)
                .put("dirid", 0)
                .put("song_begin", 0)
                .put("song_num", 50)
                .put("tag", 1)
            val resp = MusicU.call("music.srfDissInfo.DissInfo", "CgiGetDiss", param)
            val mod = resp.mod(MusicU.DEFAULT_KEY)
            Song.listFrom(mod.data?.optJSONArray("songlist"))
        } catch (t: Throwable) {
            Diag.e("Discover", "取「每日30首」歌曲失败", t)
            emptyList()
        }
    }

    /** 猜你喜欢 —— 个人电台 */
    fun guessYouLike(): List<Song> {
        radioSongs(30).takeIf { it.isNotEmpty() }?.let {
            Diag.i("Discover", "猜你喜欢: 电台 ${it.size} 首")
            return it
        }
        val newSongs = topListSongs(TOP_NEW, 30)
        Diag.i("Discover", "猜你喜欢: 回退新歌榜 ${newSongs.size} 首")
        return newSongs
    }

    private fun radioSongs(num: Int): List<Song> {
        val param = JSONObject().put("id", 99).put("num", num)
        val resp = MusicU.call("music.radioProxy.MbTrackRadioSvr", "get_radio_track", param)
        val data = resp.data(MusicU.DEFAULT_KEY) ?: return emptyList()
        val tracks = data.optJSONArray("tracks")
            ?: data.optJSONObject("data")?.optJSONArray("tracks")
        return Song.listFrom(tracks)
    }

    private fun radarSongs(): List<Song> {
        if (!Login.isLoggedIn()) return emptyList()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val param = JSONObject()
            .put("PageSize", 20)
            .put("RequestTime", System.currentTimeMillis() / 1000)
            .put("Uin", Login.uin())
            .put("GetTaste", 1)
            .put("Scene", 1)
            .put("Data", today)
        val resp = MusicU.call("music.recommend.RecommendFeed", "get_recommend_feed", param, "feed")
        val data = resp.data("feed") ?: return emptyList()
        val vList = data.optJSONArray("v_list") ?: data.optJSONArray("vecList") ?: return emptyList()
        val songs = ArrayList<Song>(vList.length())
        for (i in 0 until vList.length()) {
            val item = vList.optJSONObject(i) ?: continue
            val track = item.optJSONObject("track") ?: item
            Song.fromJson(track)?.let { songs.add(it.copy(index = songs.size)) }
        }
        return songs
    }

    /**
     * 排行榜歌曲。
     *
     * 服务端把歌曲放在 `songInfoList`, 榜单元信息在 `data`(内层 name 也叫 data)。
     * 早期实现按 `song` / `songlist` 取值, 因此排行榜永远为空。
     */
    fun topListSongs(topId: Int, limit: Int = 30): List<Song> {
        val param = JSONObject()
            .put("topListId", topId)
            .put("offset", 0)
            .put("num", limit)
        val resp = MusicU.call("musicToplist.ToplistInfoServer", "GetDetail", param, "top")
        val data = resp.data("top") ?: return emptyList()
        val arr = data.optJSONArray("songInfoList")
            ?: data.optJSONArray("song")
            ?: data.optJSONObject("data")?.optJSONArray("song")
            ?: data.optJSONArray("songlist")
            ?: return emptyList()
        return Song.listFrom(arr).take(limit)
    }

    /**
     * 首页推荐卡片 (QQ 音乐首页的卡片流)。
     * 每张卡片是一组歌曲, 分别作为「每日推荐」「猜你喜欢」的横向滑动内容。
     */
    fun homeCards(): List<RecommendCard> {
        val param = JSONObject()
            .put("direction", 0)
            .put("page", 1)
            .put("num", 10)
        val resp = MusicU.call("music.recommend.RecommendFeed", "get_recommend_feed", param, "feed")
        val data = resp.data("feed") ?: return emptyList()
        val cards = ArrayList<RecommendCard>(4)

        val vList = data.optJSONArray("v_list")
        if (vList != null) {
            for (i in 0 until vList.length()) {
                val item = vList.optJSONObject(i) ?: continue
                val title = item.optString("title", "")
                val cover = item.optString("cover", "")
                val id = item.optString("id", "card_$i")
                val songs = Song.listFrom(item.optJSONArray("tracks") ?: item.optJSONArray("songlist"))
                if (title.isEmpty() && songs.isEmpty()) continue
                cards.add(RecommendCard(id, title, item.optString("subtitle", ""), cover, 0, songs))
            }
        }
        return cards
    }

    /**
     * 推荐歌单: legacy 歌单广场接口。
     *
     * 实测 (未登录也返回数据):
     * `c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg` → `data.list[]`
     *
     * 早期使用的 `music.srfDissInfo.DissInfo{id,num,page,order}` 会返回
     * `param error` (code -100008), 导致推荐歌单永远为空。
     */
    fun recommendPlaylists(limit: Int = 12): List<Playlist> {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg" +
            "?picmid=1&rnd=0.1&g_tk=5381&loginUin=0&hostUin=0&format=json" +
            "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0" +
            "&categoryId=10000000&sortId=5&sin=0&ein=${(limit - 1).coerceAtLeast(0)}"
        return try {
            val resp = com.portable.qqmusic.core.Http.get(url, referer = "https://y.qq.com/")
            val root = J.obj(resp.body)
            val list = J.obj(root, "data")?.optJSONArray("list")
            if (list == null) {
                Diag.i("Discover", "歌单广场无 data.list, 顶层键=[${root?.keys()?.asSequence()?.joinToString(",")}]")
                emptyList()
            } else {
                val out = Playlist.listFrom(list)
                Diag.i("Discover", "歌单广场返回 ${out.size} 个")
                out
            }
        } catch (t: Throwable) {
            Diag.e("Discover", "歌单广场请求失败", t)
            emptyList()
        }
    }

    /** 排行榜目录: `GetAll` → `data.group[].toplist[]` (实测可用) */
    fun topLists(): List<Playlist> {
        val resp = MusicU.call(
            "musicToplist.ToplistInfoServer", "GetAll", JSONObject()
        )
        val data = resp.data(MusicU.DEFAULT_KEY) ?: return emptyList()
        val groups = data.optJSONArray("group") ?: return emptyList()
        val out = ArrayList<Playlist>(12)
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val list = g.optJSONArray("toplist") ?: continue
            for (j in 0 until list.length()) {
                val o = list.optJSONObject(j) ?: continue
                val id = o.optLong("topId", 0L)
                out.add(
                    Playlist(
                        id = id,
                        disstid = id.toString(),
                        title = o.optString("title", ""),
                        cover = o.optString("frontPicUrl", "").ifEmpty { o.optString("picUrl", "") },
                        songCount = o.optInt("songCnt", 0),
                        playCount = o.optLong("listenNum", 0L),
                        type = Playlist.TYPE_TOP
                    )
                )
            }
        }
        Diag.i("Discover", "排行榜目录 ${out.size} 个")
        return out
    }

    /** 热门歌单: 复用歌单广场 */
    fun topPlaylists(limit: Int = 12): List<Playlist> = recommendPlaylists(limit)

    const val TOP_HOT = 26     // 热歌榜 (注意是 26, 不是 4)
    const val TOP_NEW = 27     // 新歌榜
}

/** 歌单 / 专辑 / 榜单详情 */
object PlaylistApi {

    /**
     * 歌单详情与全部歌曲。
     *
     * 三种来源, 按 [dirid] 与 [disstid] 自动选择:
     *  - `dirid > 0`: 用户目录歌单 (我喜欢 / 自建) → `CgiGetDiss{disstid:0, dirid}`
     *  - `disstid > 0`: 普通歌单 → `CgiGetDiss{disstid, dirid:0}`
     *  - 都失败: 回退 legacy `fcg_ucc_getcdinfo_byids_cp.fcg`
     */
    fun playlistDetail(
        disstid: Long,
        songLimit: Int = 0,
        dirid: Long = 0L
    ): Pair<Playlist?, List<Song>> {
        dissInfoDetail(disstid, dirid, songLimit)?.let { (pl, songs) ->
            if (songs.isNotEmpty() || pl != null) return pl to songs
        }
        return legacyDetail(disstid)
    }

    /**
     * 歌单分页取歌曲。
     *
     * ⚠️ `CgiGetDiss` **单次最多只返回 500 首** (实测歌单歌曲数卡在 499),
     * 超过这个数量的歌单必须靠 `song_begin` 翻页继续取 —— 用户反馈的
     * 「歌单歌曲上限到 499」就是这个原因, 不是歌单本身只有这么点歌。
     *
     * @param begin 本次起始下标
     * @param num   本次取多少首 (服务端上限 500)
     * @return (有效歌曲, 服务端本次返回的原始条目数)。
     *
     * ⚠️ 一定要用**原始条目数**推进 `song_begin`, 不能用有效歌曲数: 有些条目
     * 没有 mid 会被 [Song.listFrom] 丢掉 (实测首屏 500 条里会少 1 条), 用
     * 有效数当下标会漏歌, 而且 `有效数 < num` 会被误判成「已到最后一页」——
     * 这正是大歌单卡在 499 首的直接原因。
     */
    fun playlistSongsPage(
        disstid: Long,
        dirid: Long,
        begin: Int,
        num: Int
    ): Pair<List<Song>, Int> {
        return try {
            val param = JSONObject()
                .put("disstid", if (dirid > 0) 0 else disstid)
                .put("dirid", dirid)
                .put("song_begin", begin)
                .put("song_num", num.coerceAtLeast(1))
                .put("tag", 1)
            val resp = MusicU.call("music.srfDissInfo.DissInfo", "CgiGetDiss", param)
            val mod = resp.mod(MusicU.DEFAULT_KEY)
            val data = mod.data
            if (mod.code != 0 || data == null) {
                Diag.i("PlaylistApi", "分页失败 code=${mod.code} begin=$begin")
                return emptyList<Song>() to 0
            }
            val arr = data.optJSONArray("songlist")
            val raw = arr?.length() ?: 0
            val list = Song.listFrom(arr)
            Diag.i("PlaylistApi", "分页 begin=$begin 原始 $raw 条, 有效 ${list.size} 首")
            list to raw
        } catch (t: Throwable) {
            Diag.e("PlaylistApi", "分页异常 begin=$begin", t)
            emptyList<Song>() to 0
        }
    }

    /**
     * `music.srfDissInfo.DissInfo / CgiGetDiss`。
     *
     * ⚠️ 取用户目录歌单时 **`disstid` 必须是 0**, 由 `dirid` 定位;
     * 传 `disstid=201` 会得到 `code=10004` (param error)。
     * 取普通歌单则相反: `disstid` 为歌单 id, `dirid=0`。
     */
    private fun dissInfoDetail(
        disstid: Long,
        dirid: Long,
        songLimit: Int
    ): Pair<Playlist?, List<Song>>? {
        return try {
            val param = JSONObject()
                .put("disstid", if (dirid > 0) 0 else disstid)
                .put("dirid", dirid)
                .put("song_begin", 0)
                .put("song_num", if (songLimit > 0) songLimit else 500)
                .put("tag", 1)
            val resp = MusicU.call("music.srfDissInfo.DissInfo", "CgiGetDiss", param)
            val mod = resp.mod(MusicU.DEFAULT_KEY)
            val data = mod.data
            if (mod.code != 0 || data == null) {
                Diag.i("PlaylistApi", "CgiGetDiss code=${mod.code} (disstid=$disstid dirid=$dirid), 回退 legacy")
                return null
            }
            val songs = Song.listFrom(data.optJSONArray("songlist"))
            val pl = Playlist.fromJson(data)
                ?: Playlist(
                    id = if (dirid > 0) dirid else disstid,
                    dirid = dirid,
                    title = "",
                    songCount = songs.size
                )
            Diag.i("PlaylistApi", "CgiGetDiss: ${songs.size} 首 (disstid=$disstid dirid=$dirid)")
            pl to songs
        } catch (t: Throwable) {
            Diag.e("PlaylistApi", "CgiGetDiss 异常", t)
            null
        }
    }

    /**
     * legacy 歌单详情, 返回 `cdlist[0]`。
     *
     * 收藏的别人创建的歌单**只能**走这个接口: 它的 `disstid` 参数认的是 tid,
     * 拿歌单真实的 dissid 去问会得到空 `cdlist`; 而 `CgiGetDiss` 反过来不认 tid
     * (回 param error)。两个 id 都试一遍也救不了, 必须由调用方说清楚手里是哪个。
     */
    fun legacyDetail(disstid: Long): Pair<Playlist?, List<Song>> {
        return try {
            val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
                "?type=1&json=1&utf8=1&onlysong=0&disstid=$disstid&format=json" +
                "&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0"
            val resp = com.portable.qqmusic.core.Http.get(url, referer = "https://y.qq.com/")
            val root = J.obj(resp.body) ?: return null to emptyList()
            val cd = J.arr(root, "cdlist")?.optJSONObject(0)
                ?: root.optJSONObject("data")?.let { d ->
                    J.arr(d, "cdlist")?.optJSONObject(0)
                }
            if (cd == null) {
                Diag.i("PlaylistApi", "legacy 歌单详情无 cdlist, 键=[${root.keys().asSequence().joinToString(",")}]")
                return null to emptyList()
            }
            val songs = Song.listFrom(cd.optJSONArray("songlist"))
            val pl = Playlist.fromJson(cd)
            Diag.i("PlaylistApi", "legacy 歌单详情: ${songs.size} 首")
            pl to songs
        } catch (t: Throwable) {
            Diag.e("PlaylistApi", "legacy 歌单详情异常", t)
            null to emptyList()
        }
    }

    /**
     * 专辑详情与歌曲。
     *
     * 两个坑:
     * 1. 元信息接口的参数名是 `albumMId`(大写 I), 写成 `albumMid` 服务端照样回 200,
     *    但 data 是空的 —— 表现为「专辑页永远 0 首」。
     * 2. 歌曲不在 GetAlbumDetail 的返回里, 得另外调 `GetAlbumSongList`。
     */
    fun albumDetail(albumMid: String): Pair<Playlist?, List<Song>> {
        if (albumMid.isEmpty()) return null to emptyList()

        var title = ""
        runCatching {
            val resp = MusicU.call(
                "music.musichallAlbum.AlbumInfoServer",
                "GetAlbumDetail",
                JSONObject().put("albumMId", albumMid)
            )
            val d = resp.data(MusicU.DEFAULT_KEY)
            title = d?.optJSONObject("basicInfo")?.optString("albumName").orEmpty()
        }

        val songs = runCatching {
            val resp = MusicU.call(
                "music.musichallAlbum.AlbumSongList",
                "GetAlbumSongList",
                JSONObject().put("albumMid", albumMid).put("begin", 0).put("num", 200)
            )
            val d = resp.data(MusicU.DEFAULT_KEY)
            val arr = d?.optJSONArray("songList")
            val out = ArrayList<Song>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    // songList 里每一项是 {"songInfo": {...}}, 要下钻一层
                    val info = arr.optJSONObject(i)?.optJSONObject("songInfo") ?: continue
                    Song.fromJson(info)?.let(out::add)
                }
            }
            out
        }.getOrDefault(emptyList())

        Diag.i("PlaylistApi", "专辑 $albumMid -> ${songs.size} 首 title=$title")
        val pl = Playlist(
            id = 0L,
            disstid = albumMid,
            title = title,
            cover = albumMid,
            songCount = songs.size,
            type = Playlist.TYPE_ALBUM
        )
        return pl to songs
    }

    /** 排行榜详情 */
    fun topDetail(topId: Int): Pair<Playlist?, List<Song>> {
        val param = JSONObject().put("topListId", topId).put("offset", 0).put("num", 200)
        val resp = MusicU.call("musicToplist.ToplistInfoServer", "GetDetail", param, "top")
        val root = resp.data("top") ?: return null to emptyList()
        // 元信息在外层同名的 data 里; 歌曲在 songInfoList
        val meta = root.optJSONObject("data") ?: root
        val songs = Song.listFrom(
            root.optJSONArray("songInfoList")
                ?: root.optJSONArray("song")
                ?: meta.optJSONArray("song")
                ?: root.optJSONArray("songlist")
        )
        val pl = Playlist(
            id = topId.toLong(),
            disstid = topId.toString(),
            title = meta.optString("title", root.optString("title", "")),
            cover = meta.optString("frontPicUrl", meta.optString("headPicUrl", "")),
            songCount = songs.size,
            playCount = meta.optLong("listenNum", 0L),
            desc = meta.optString("intro", ""),
            type = Playlist.TYPE_TOP
        )
        return pl to songs
    }
}
