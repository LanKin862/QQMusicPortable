package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.J
import org.json.JSONObject

/**
 * 歌手相关: 全部歌曲 / 全部专辑。
 *
 * 曾经以为 QQ 音乐没有可用的「按歌手取歌」接口 —— legacy
 * `c.y.qq.com/v8/fcg-bin/fcg_v8_singer_track_cp.fcg` 确实 404 了,
 * musicu 里 `music.musichallSong.SingerInfoInter` / `music.musichallSinger.SingerInfo`
 * 这些 module 名也全回 500003。
 *
 * 真正的 module 名是 **`musichall.song_list_server`** —— 不带 `music.` 前缀,
 * method 是 `GetSingerSongList`, 参数是 `singerMid` / `order` / `number` / `begin`
 * (注意是 `number` 不是 `num`)。专辑同理, 是
 * `music.musichallAlbum.AlbumListServer` + `GetAlbumList`。
 *
 * 之前那套「用歌手名搜单曲再按 mid 过滤」的做法只能拿到搜索前 60 首里的十几首,
 * 歌手作品一多就明显缺歌, 已弃用。
 */
object SingerApi {

    private const val MODULE_SONG = "musichall.song_list_server"
    private const val METHOD_SONG = "GetSingerSongList"

    private const val MODULE_ALBUM = "music.musichallAlbum.AlbumListServer"
    private const val METHOD_ALBUM = "GetAlbumList"

    /**
     * 单页条目数。
     *
     * 服务端硬上限就是 30 —— 实测传 `number=100` 也只回 30 条,
     * 所以别指望靠加大页码来少发请求。
     */
    const val PAGE_SIZE = 30

    /** 按热度排 (默认) */
    const val ORDER_HOT = 1

    /** 按时间排 (最新的在前) */
    const val ORDER_LATEST = 0

    data class SongPage(val songs: List<Song>, val total: Int)

    data class SingerAlbum(
        val mid: String,
        val name: String,
        val publishDate: String,
        val type: String
    )

    data class AlbumPage(val albums: List<SingerAlbum>, val total: Int)

    /**
     * 某位歌手的歌曲, 分页取。
     *
     * @param begin 起始下标 (不是页码)
     * @param total 由返回里的 `totalNum` 给出; 失败时是 0
     */
    fun songs(
        singermid: String,
        begin: Int = 0,
        number: Int = PAGE_SIZE,
        order: Int = ORDER_HOT
    ): SongPage {
        if (singermid.isEmpty()) return SongPage(emptyList(), 0)

        val param = JSONObject()
            .put("singerMid", singermid)
            .put("order", order)
            .put("number", number)
            .put("begin", begin)

        val mod = MusicU.call(MODULE_SONG, METHOD_SONG, param).mod(MODULE_SONG)
        if (mod == null || mod.code != 0 || mod.data == null) {
            Diag.i("SingerApi", "GetSingerSongList mid=$singermid begin=$begin code=${mod?.code}")
            return SongPage(emptyList(), 0)
        }

        val data = mod.data
        val arr = data.optJSONArray("songList")
        val songs = ArrayList<Song>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                // 每条是 { songInfo: {...}, ... }, 真正的歌曲对象在 songInfo 里
                val info = arr.optJSONObject(i)?.optJSONObject("songInfo") ?: continue
                Song.fromJson(info)?.let { songs.add(it.copy(index = songs.size)) }
            }
        }
        val total = J.int(data, "totalNum", 0)
        Diag.i("SingerApi", "歌手 $singermid begin=$begin -> ${songs.size} 首 / 共 $total")
        return SongPage(songs, total)
    }

    /** 某位歌手的专辑, 分页取。`begin` 同样是起始下标。 */
    fun albums(singermid: String, begin: Int = 0, number: Int = 30, order: Int = ORDER_HOT): AlbumPage {
        if (singermid.isEmpty()) return AlbumPage(emptyList(), 0)

        val param = JSONObject()
            .put("singerMid", singermid)
            .put("order", order)
            .put("number", number)
            .put("begin", begin)

        val mod = MusicU.call(MODULE_ALBUM, METHOD_ALBUM, param).mod(MODULE_ALBUM)
        if (mod == null || mod.code != 0 || mod.data == null) {
            Diag.i("SingerApi", "GetAlbumList mid=$singermid begin=$begin code=${mod?.code}")
            return AlbumPage(emptyList(), 0)
        }

        val data = mod.data
        val arr = data.optJSONArray("albumList")
        val albums = ArrayList<SingerAlbum>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mid = J.str(o, "albumMid")
                if (mid.isEmpty()) continue
                albums.add(
                    SingerAlbum(
                        mid = mid,
                        name = J.str(o, "albumName"),
                        publishDate = J.str(o, "publishDate"),
                        type = J.str(o, "albumType")
                    )
                )
            }
        }
        val total = J.int(data, "total", 0)
        Diag.i("SingerApi", "歌手 $singermid 专辑 begin=$begin -> ${albums.size} 张 / 共 $total")
        return AlbumPage(albums, total)
    }

    /** 名字 -> 歌手 的进程内缓存, 同一个歌手点第二次不再发请求 */
    private val singerCache = java.util.concurrent.ConcurrentHashMap<String, Singer>()

    /**
     * 从歌手名反查歌手。
     *
     * 推荐/每日推荐这些接口给的 `singer` 数组只有名字没有 mid, 直接拿 `song.singerMids`
     * 去跳歌手页会扑空。这里用搜索接口按名字找第一条, 顺带把头像一起带回来 ——
     * 歌手页的封面就靠它, 不用再单独调一次「歌手详情」。
     * 名字里的多人用 `/` 拼过, 只取第一个查。
     */
    fun findByName(singer: String): Singer? {
        val name = singer.split("/").first().trim()
        if (name.isEmpty()) return null
        singerCache[name]?.let { return it }
        val found = try {
            val list = SearchApi.searchSingers(name, 1, 5)
            list.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: list.firstOrNull()
        } catch (t: Throwable) {
            Diag.i("SingerApi", "反查歌手失败 name=$name: ${t.message}")
            null
        } ?: return null
        if (found.mid.isNotEmpty()) {
            singerCache[name] = found
            Diag.i("SingerApi", "反查歌手 $name -> ${found.mid} avatar=${found.avatar}")
        }
        // 注意: 不能用 `T001R300x300M000{mid}.jpg` 拼头像 —— 搜索结果的 mid 和头像文件名里的
        // mid 经常不是同一个 (实测 003tgy5B1CIWVv 拼出来 404, 而它自带的 avatar 是 200)。
        // avatar 字段本身就是一个完整可用的 URL, 原样用。
        return found
    }
}
