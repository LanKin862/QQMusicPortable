package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Config
import com.portable.qqmusic.core.J
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Quality

/**
 * 数据模型。
 *
 * 统一使用方法 [Song.fromJson] 解析, 因为 QQ 音乐在不同接口里
 * 用 `songmid` / `mid` / `songMid` 三种写法指同一个字段, 这里做了归一。
 */
data class Song(
    val mid: String,
    val name: String,
    val singer: String,
    val singerMids: List<String> = emptyList(),
    val albumMid: String = "",
    val albumName: String = "",
    val durationSec: Int = 0,
    val songId: Long = 0,
    /** 是否仅能试听 (无版权 / 非会员) */
    val vipOnly: Boolean = false,
    val payPlay: Int = 0,
    val fileSizeFlac: Long = 0,
    val fileSizeMp3: Long = 0,
    val index: Int = 0,
    val type: Int = 0,
    /**
     * 本地音乐的真实文件路径 (`/storage/emulated/0/Music/x.mp3`)。
     *
     * 必须在这里显式保存, 不能等播放时再拿 MediaStore 的 id 去拼 URI ——
     * `MediaPlayer.setDataSource(String)` 只接受**文件路径**,
     * 传 `content://` URI 会被当成不存在的路径直接失败。
     * MediaStore 的 `DATA` 列在 `query()` 时一次读出, 避免每次播放都再查一次库。
     */
    val localPath: String = "",
    /**
     * 本地音乐的封面 URI (`content://media/external/audio/albumart/<albumId>`)。
     *
     * 本地歌没有 `albumMid`, 走不了在线封面模板, 但 MediaStore 的
     * `ALBUM_ID` 能拼出系统内置的专辑封面 URI, Glide 可以直接加载。
     */
    val localCover: String = ""
) {
    val isLocal: Boolean get() = type == TYPE_LOCAL

    /** 歌手是否为空/占位名。借到在线元信息后要拿真名覆盖掉 */
    val isUnknownSinger: Boolean
        get() = singer.isBlank() || singer.trim() == UNKNOWN_SINGER || singer.trim() == "未知艺术家"

    /**
     * mid 是否指向线上曲目。
     *
     * 本地扫描出来的 mid 是 `local_<媒体库id>` / `file_<路径hash>` 这种假的,
     * 拿它去请求歌词/封面只会得到空结果。下载的歌虽然是本地文件, 但带着**原曲 mid**,
     * 所以不能用 [isLocal] 判断 —— 那正是「下载后既没封面也没歌词」的原因。
     */
    val hasRealMid: Boolean get() = mid.isNotEmpty() && !mid.startsWith("local_") && !mid.startsWith("file_")

    fun coverUrl(): String {
        if (localCover.isNotEmpty()) return localCover
        return if (albumMid.isNotEmpty()) String.format(Config.IMG_SONG, albumMid) else ""
    }

    fun key(): String = "$mid#$songId"

    companion object {
        const val TYPE_ONLINE = 0
        const val TYPE_LOCAL = 1

        /** 本地文件读不出歌手时的占位名 (MediaStore 与下载兜底共用) */
        const val UNKNOWN_SINGER = "未知歌手"

        fun fromJson(o: org.json.JSONObject?): Song? {
            if (o == null) return null
            val mid = J.str(o, "songmid").ifEmpty { J.str(o, "mid") }.ifEmpty { J.str(o, "songMid") }
            if (mid.isEmpty()) return null

            // 歌手: 优先 singer 数组, 其次 singerName
            val singers = ArrayList<String>(2)
            val singerMids = ArrayList<String>(2)
            val singerArr = J.arr(o, "singer")
            if (singerArr != null) {
                for (i in 0 until singerArr.length()) {
                    val s = singerArr.optJSONObject(i) ?: continue
                    J.str(s, "name").takeIf { it.isNotEmpty() }?.let { singers.add(it) }
                    J.str(s, "mid").takeIf { it.isNotEmpty() }?.let { singerMids.add(it) }
                }
            }
            if (singers.isEmpty()) {
                J.str(o, "singerName").ifEmpty { J.str(o, "singername") }
                    .takeIf { it.isNotEmpty() }?.let { singers.add(it) }
            }

            var albumMid = J.str(o, "albummid")
            var albumName = J.str(o, "albumname")
            if (albumMid.isEmpty() || albumName.isEmpty()) {
                val album = J.obj(o, "album")
                if (album != null) {
                    if (albumMid.isEmpty()) albumMid = J.str(album, "mid")
                    if (albumName.isEmpty()) albumName = J.str(album, "name")
                }
            }

            var duration = J.int(o, "interval")
            if (duration <= 0) duration = J.int(o, "songInterval")

            val songId = J.long(o, "songid").let { if (it != 0L) it else J.long(o, "id") }

            return Song(
                mid = mid,
                name = J.str(o, "songname").ifEmpty { J.str(o, "name") }.ifEmpty { J.str(o, "title") },
                singer = if (singers.isEmpty()) UNKNOWN_SINGER else singers.joinToString("/"),
                singerMids = singerMids,
                albumMid = albumMid,
                albumName = albumName,
                durationSec = duration,
                songId = songId,
                vipOnly = J.boolLoose(o, "vip") || J.int(o, "pay", 0) != 0,
                payPlay = J.int(o, "pay_play", J.int(o, "payplay", 0)),
                index = J.int(o, "index", 0)
            )
        }

        /**
         * 解析歌曲数组。
         * 部分接口 (如 songlist) 会把歌曲放在 `data` 字段里, 这里自动下钻一层。
         */
        fun listFrom(arr: org.json.JSONArray?): List<Song> {
            if (arr == null) return emptyList()
            val out = ArrayList<Song>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val real = o.optJSONObject("data") ?: o
                val s = fromJson(real)
                if (s != null) out.add(s.copy(index = out.size))
            }
            return out
        }

        /** 序列化, 供 [com.portable.qqmusic.data.MetaCache] 做本地持久化 */
        fun toJson(s: Song): org.json.JSONObject = org.json.JSONObject()
            .put("mid", s.mid)
            .put("name", s.name)
            .put("singer", s.singer)
            // singerMids 必须存: 播放页点歌手名靠它跳转, 丢了就只能退回搜索页
            .put("singerMids", org.json.JSONArray(s.singerMids))
            .put("albumMid", s.albumMid)
            .put("albumName", s.albumName)
            .put("duration", s.durationSec)
            .put("songId", s.songId)
            .put("vip", s.vipOnly)
            .put("type", s.type)
            .put("localPath", s.localPath)

        fun listToJson(list: List<Song>): org.json.JSONArray {
            val arr = org.json.JSONArray()
            list.forEach { arr.put(toJson(it)) }
            return arr
        }

        /**
         * 从 [listToJson] 写出的 JSON 还原。
         *
         * 只留展示与播放需要的字段 (歌手 mid、文件大小等重新拉一次就有了),
         * 这样 500 首的列表也就几十 KB。
         */
        fun listFromCache(json: String?): List<Song> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val arr = org.json.JSONArray(json)
                val out = ArrayList<Song>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val mid = o.optString("mid")
                    if (mid.isEmpty()) continue
                    out.add(
                        Song(
                            mid = mid,
                            name = o.optString("name"),
                            singer = o.optString("singer"),
                            singerMids = o.optJSONArray("singerMids")?.let { a ->
                                (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
                            } ?: emptyList(),
                            albumMid = o.optString("albumMid"),
                            albumName = o.optString("albumName"),
                            durationSec = o.optInt("duration"),
                            songId = o.optLong("songId"),
                            vipOnly = o.optBoolean("vip"),
                            index = out.size,
                            type = o.optInt("type"),
                            localPath = o.optString("localPath")
                        )
                    )
                }
                out
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }
}

/** 歌单 / 专辑 / 排行榜 (结构一致) */
data class Playlist(
    val id: Long,
    /**
     * 用户目录歌单的 dirid (我喜欢=201、自建歌单等)。
     * 取歌曲时必须用 `CgiGetDiss{disstid:0, dirid:dirid}` —— 见 [UserApi.userDirSongs]。
     */
    val dirid: Long = 0L,
    /** 排行榜与专辑使用字符串 id */
    val disstid: String = "",
    val title: String,
    val cover: String = "",
    val songCount: Int = 0,
    val playCount: Long = 0,
    val creator: String = "",
    val creatorUin: String = "",
    val desc: String = "",
    val isSelfCreated: Boolean = false,
    val type: Int = 0,
    /**
     * 收藏歌单的 tid。
     *
     * 「收藏的别人歌单」只有 legacy `fcg_ucc_getcdinfo_byids_cp.fcg?disstid=<tid>`
     * 认; `CgiGetDiss` 对它回 param error, 而 legacy 又**不认歌单真实的 dissid**。
     * 两个 id 语义不同, 必须分开存 —— 混用一个字段正是「收藏歌单点进去没有歌曲」
     * 的根因 (详情页拿着 dissid 去问 legacy, 于是 cdlist 为空)。
     */
    val tid: Long = 0L
) {
    fun coverUrl(): String = coverUrlOf(cover)

    companion object {
        /** 封面 key → 完整 URL (已经是 http 的原样返回) */
        fun coverUrlOf(cover: String): String {
            if (cover.isEmpty()) return ""
            if (cover.startsWith("http")) return cover
            return String.format(Config.IMG_PLAYLIST, cover)
        }

        const val TYPE_PLAYLIST = 0
        const val TYPE_ALBUM = 1
        const val TYPE_TOP = 2
        const val TYPE_RADIO = 3

        fun fromJson(o: org.json.JSONObject?, type: Int = TYPE_PLAYLIST): Playlist? {
            if (o == null) return null
            /*
             * 歌单字段在不同接口里有三套命名, 必须都认:
             *  A. 用户目录 legacy (fcg_user_created_diss): diss_name / song_cnt / dirid
             *  B. 用户目录 musicu (PlaylistBaseRead):     dirName  / songNum  / dirId
             *  C. 普通歌单 (CgiGetDiss / 广场):           dissname / songnum  / dissid
             */
            val id = J.long(o, "dissid")
                .let { if (it != 0L) it else J.long(o, "disstid") }
                .let { if (it != 0L) it else J.long(o, "dirId") }
                .let { if (it != 0L) it else J.long(o, "dirid") }
                .let { if (it != 0L) it else J.long(o, "id") }
            val dirId = J.long(o, "dirId").let { if (it != 0L) it else J.long(o, "dirid") }

            val title = J.str(o, "dissname")
                .ifEmpty { J.str(o, "diss_name") }
                .ifEmpty { J.str(o, "dirName") }
                .ifEmpty { J.str(o, "title") }
                .ifEmpty { J.str(o, "name") }
                .ifEmpty { J.str(o, "albumName") }
            val cover = J.str(o, "imgurl")
                .ifEmpty { J.str(o, "diss_cover") }
                .ifEmpty { J.str(o, "picUrl") }
                .ifEmpty { J.str(o, "logo") }
                .ifEmpty { J.str(o, "cover") }
                .ifEmpty { J.str(o, "pic") }
                .ifEmpty { J.str(o, "picurl") }
                .ifEmpty { J.str(o, "albumMid") }
            if (title.isEmpty() && cover.isEmpty() && id == 0L) return null

            var creator = J.str(o, "nickname").ifEmpty { J.str(o, "nick") }.ifEmpty { J.str(o, "creator") }
            var creatorUin = J.str(o, "uin")
            val creatorObj = J.obj(o, "creator")
            if (creatorObj != null) {
                // 搜索接口 (search_type=3) 用的是 creator.name, 不是 nick
                if (creator.isEmpty()) creator = J.str(creatorObj, "nick").ifEmpty { J.str(creatorObj, "name") }
                if (creatorUin.isEmpty()) creatorUin = J.str(creatorObj, "uin")
            }

            val songCount = J.int(o, "songnum")
                .let { if (it != 0) it else J.int(o, "song_cnt") }
                .let { if (it != 0) it else J.int(o, "songNum") }
                .let { if (it != 0) it else J.int(o, "song_count") }
                .let { if (it != 0) it else J.int(o, "song_count_total") }

            return Playlist(
                id = id,
                dirid = dirId,
                disstid = J.str(o, "dissid").ifEmpty { J.str(o, "disstid") },
                title = title,
                cover = cover,
                songCount = songCount,
                playCount = J.long(o, "listennum")
                    .let { if (it != 0L) it else J.long(o, "listen_num") }
                    .let { if (it != 0L) it else J.long(o, "play_cnt") }
                    .let { if (it != 0L) it else J.long(o, "play_count") },
                creator = creator,
                creatorUin = creatorUin,
                desc = J.str(o, "desc").ifEmpty { J.str(o, "introduction") },
                isSelfCreated = dirId != 0L || J.boolLoose(o, "isSelfCreated"),
                type = type
            )
        }

        fun listFrom(arr: org.json.JSONArray?, type: Int = TYPE_PLAYLIST): List<Playlist> {
            if (arr == null) return emptyList()
            val out = ArrayList<Playlist>(arr.length())
            for (i in 0 until arr.length()) {
                val p = fromJson(arr.optJSONObject(i), type)
                if (p != null) out.add(p)
            }
            return out
        }

        /** 序列化, 供 [com.portable.qqmusic.data.MetaCache] 做本地持久化 */
        fun toJson(p: Playlist): org.json.JSONObject = org.json.JSONObject()
            .put("id", p.id)
            .put("dirid", p.dirid)
            .put("disstid", p.disstid)
            .put("title", p.title)
            .put("cover", p.cover)
            .put("songCount", p.songCount)
            .put("playCount", p.playCount)
            .put("creator", p.creator)
            .put("creatorUin", p.creatorUin)
            .put("desc", p.desc)
            .put("isSelfCreated", p.isSelfCreated)
            .put("type", p.type)
            .put("tid", p.tid)

        /**
         * 从 [toJson] 写出的 JSON 还原。
         *
         * 刻意不复用 [fromJson]: 那边认的是服务端的三套字段命名, 而这里存的是
         * 已经归一化过的 camelCase, 直接赋值比再猜一遍字段名可靠。
         */
        fun fromCache(o: org.json.JSONObject?): Playlist? {
            if (o == null) return null
            val title = o.optString("title")
            if (title.isEmpty()) return null
            return Playlist(
                id = o.optLong("id"),
                dirid = o.optLong("dirid"),
                disstid = o.optString("disstid"),
                title = title,
                cover = o.optString("cover"),
                songCount = o.optInt("songCount"),
                playCount = o.optLong("playCount"),
                creator = o.optString("creator"),
                creatorUin = o.optString("creatorUin"),
                desc = o.optString("desc"),
                isSelfCreated = o.optBoolean("isSelfCreated"),
                type = o.optInt("type"),
                tid = o.optLong("tid")
            )
        }

        fun listToJson(list: List<Playlist>): org.json.JSONArray {
            val arr = org.json.JSONArray()
            list.forEach { arr.put(toJson(it)) }
            return arr
        }

        fun listFromCache(json: String?): List<Playlist> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val arr = org.json.JSONArray(json)
                val out = ArrayList<Playlist>(arr.length())
                for (i in 0 until arr.length()) {
                    fromCache(arr.optJSONObject(i))?.let { out.add(it) }
                }
                out
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }
}

/** 登录用户信息 */
data class UserProfile(
    val uin: String,
    val nick: String,
    val avatar: String,
    val vipType: Int = 0
) {
    val isVip: Boolean get() = vipType > 0

    companion object {
        fun fromJson(root: org.json.JSONObject?): UserProfile? {
            if (root == null) return null
            // GetLoginUserInfo 的实际结构: { code, data: { info: { nick, logo, ... } } }
            val data = J.obj(root, "data") ?: root
            val info = J.obj(data, "info")
                ?: J.obj(data, "creator")
                ?: J.obj(data, "profile")
                ?: J.obj(data, "userInfo")
                ?: data

            val uin = J.str(info, "uin").ifEmpty { J.str(data, "uin") }
                .ifEmpty { J.str(info, "encrypt_uin") }
            val nick = J.str(info, "nick").ifEmpty { J.str(info, "nickname") }
            val avatar = J.str(info, "logo")
                .ifEmpty { J.str(info, "headurl") }
                .ifEmpty { J.str(info, "avatarUrl") }
            val vip = J.int(info, "vip_type")
                .let { if (it != 0) it else J.int(info, "vipType") }
                .let { if (it != 0) it else J.int(data, "vip_type") }
            if (uin.isEmpty() && nick.isEmpty()) return null
            return UserProfile(uin, nick, avatar, vip)
        }
    }
}

/** 播放地址结果 */
data class SongUrl(
    val url: String,
    val quality: Quality,
    /** 服务端返回的纯文件名, 作为缓存 key */
    val fileName: String,
    val fileSize: Long = 0,
    val isTrial: Boolean = false
) {
    val isPlayable: Boolean get() = url.isNotEmpty()
}

/** 首页推荐卡片 */
data class RecommendCard(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val cover: String = "",
    /** 点击卡片进入的歌单/专辑 id */
    val playlistId: Long = 0,
    val songs: List<Song> = emptyList()
)

/**
 * 歌手。
 *
 * 只用于搜索结果与歌手页的展示, 不参与播放 —— 播放走的还是 [Song]。
 * 字段名取自 `music.search.SearchCgiService` 的 `singer` 列表 (search_type=1)。
 */
data class Singer(
    val mid: String,
    val name: String,
    val avatar: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val mvCount: Int = 0
) {
    companion object {
        fun fromJson(o: org.json.JSONObject?): Singer? {
            if (o == null) return null
            val mid = J.str(o, "singerMID")
                .ifEmpty { J.str(o, "singerMid") }
                .ifEmpty { J.str(o, "mid") }
            val name = J.str(o, "singerName")
                .ifEmpty { J.str(o, "singer_name") }
                .ifEmpty { J.str(o, "name") }
            if (mid.isEmpty() || name.isEmpty()) return null
            // 搜索结果带的是搜索高亮后的名字 (含 <em> 标签), 只用来展示, 保留原始串更好
            return Singer(
                mid = mid,
                name = name,
                avatar = hiResAvatar(
                    J.str(o, "singerPic")
                        .ifEmpty { J.str(o, "singer_pic") }
                        .ifEmpty { J.str(o, "pic") }
                        .replace("http://", "https://")
                ),
                songCount = J.int(o, "songNum").let { if (it != 0) it else J.int(o, "song_num") },
                albumCount = J.int(o, "albumNum").let { if (it != 0) it else J.int(o, "album_num") },
                mvCount = J.int(o, "mvNum").let { if (it != 0) it else J.int(o, "mv_num") }
            )
        }

        fun listFrom(arr: org.json.JSONArray?): List<Singer> {
            if (arr == null) return emptyList()
            val out = ArrayList<Singer>(arr.length())
            for (i in 0 until arr.length()) {
                fromJson(arr.optJSONObject(i))?.let { out.add(it) }
            }
            return out
        }

        /**
         * 头像换成大图。
         *
         * 搜索接口给的是 `T001R150x150M000{hash}_{n}.jpg` —— 150 见方, 摆到歌手详情页
         * 那种整屏宽的位置上糊得没法看。photo_new 这套 URL 的尺寸段是可以直接改的,
         * 换成 500 之后同一张图依然命中缩略图服务; 只动 `R{宽}x{高}M` 这一段,
         * 保留下划线后面的编号 (那是同一歌手的不同头像, 换掉会变成另一张脸)。
         */
        private fun hiResAvatar(url: String): String {
            if (url.isEmpty()) return url
            return url
                .replace("R150x150M", "R500x500M")
                .replace("R300x300M", "R500x500M")
        }
    }
}
