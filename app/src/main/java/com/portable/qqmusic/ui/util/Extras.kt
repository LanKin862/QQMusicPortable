package com.portable.qqmusic.ui.util

import android.content.Context
import android.content.Intent
import com.portable.qqmusic.data.api.Song

/** 跨页面传递歌曲/歌单的 Intent 约定 */
object Extras {

    const val EXTRA_DISSTID = "disstid"
    /** 用户目录歌单的 dirid (我喜欢=201、自建歌单), 取歌曲时用它而不是 disstid */
    const val EXTRA_DIRID = "dirid"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SOURCE = "source"
    /**
     * 列表页已经拿到的封面。
     *
     * `CgiGetDiss` 返回的 data 里没有封面字段, 所以自建歌单进详情页会退化成通用
     * 音符图标 —— 而同一个歌单在列表页明明是有封面的, 没必要再问一次接口。
     */
    const val EXTRA_COVER = "cover"
    const val EXTRA_QUERY = "query"
    const val EXTRA_MODE = "mode"
    /** 专辑接口使用字符串 mid, 不能走 disstid */
    const val EXTRA_ALBUM_MID = "album_mid"

    /** 详情页来源类型 */
    const val SOURCE_PLAYLIST = 0
    const val SOURCE_FAVORITE = 1
    const val SOURCE_LOCAL = 2
    const val SOURCE_ALBUM = 3
    const val SOURCE_TOP = 4
    const val SOURCE_DOWNLOADED = 5

    /**
     * 收藏的别人创建的歌单。
     *
     * 与 [SOURCE_PLAYLIST] 的区别在于取歌曲的接口: 这类歌单在服务端只有
     * legacy `fcg_ucc_getcdinfo_byids_cp.fcg?disstid=<tid>` 认, `CgiGetDiss`
     * 会回 param error。所以详情页必须知道自己是哪一种, 不能只看 id > 0。
     */
    const val SOURCE_COLLECTED = 6

    /**
     * 歌手详情。
     *
     * 歌手用字符串 mid 定位 (`000Sp0Bz4JXH0H`), 塞不进 [EXTRA_DISSTID] 那个 Long,
     * 所以单独走 [EXTRA_SINGER_MID]。
     */
    const val SOURCE_SINGER = 7

    const val EXTRA_SINGER_MID = "singer_mid"

    fun playlistIntent(
        context: Context,
        disstid: Long,
        title: String,
        source: Int,
        dirid: Long = 0L,
        cover: String = ""
    ): Intent =
        Intent(context, com.portable.qqmusic.ui.PlaylistActivity::class.java)
            .putExtra(EXTRA_DISSTID, disstid)
            .putExtra(EXTRA_DIRID, dirid)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SOURCE, source)
            .putExtra(EXTRA_COVER, cover)

    /** 专辑详情: 使用字符串 mid。封面直接按 mid 拼, 免得调用方每次都要传 */
    fun albumIntent(context: Context, albumMid: String, title: String): Intent =
        Intent(context, com.portable.qqmusic.ui.PlaylistActivity::class.java)
            .putExtra(EXTRA_ALBUM_MID, albumMid)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(
                EXTRA_COVER,
                if (albumMid.isEmpty()) "" else "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
            )
            .putExtra(EXTRA_SOURCE, SOURCE_ALBUM)

    /** 歌手详情: 使用字符串 mid, 标题与头像从列表页带过去 */
    fun singerIntent(
        context: Context,
        singer: com.portable.qqmusic.data.api.Singer
    ): Intent =
        Intent(context, com.portable.qqmusic.ui.SingerActivity::class.java)
            .putExtra(EXTRA_SINGER_MID, singer.mid)
            .putExtra(EXTRA_TITLE, singer.name)
            .putExtra(EXTRA_COVER, singer.avatar)

    /** 播放页点歌手名用这个: 手上只有名字和一个 mid, 没有 Singer 对象 */
    fun singerIntent(context: Context, singerMid: String, name: String, cover: String = ""): Intent =
        Intent(context, com.portable.qqmusic.ui.SingerActivity::class.java)
            .putExtra(EXTRA_SINGER_MID, singerMid)
            .putExtra(EXTRA_TITLE, name)
            .putExtra(EXTRA_COVER, cover)

    fun searchIntent(context: Context, query: String = ""): Intent =
        Intent(context, com.portable.qqmusic.ui.SearchActivity::class.java)
            .putExtra(EXTRA_QUERY, query)

    fun nowPlayingIntent(context: Context): Intent =
        Intent(context, com.portable.qqmusic.ui.NowPlayingActivity::class.java)

    fun displayName(song: Song): String =
        if (song.singer.isEmpty()) song.name else "${song.singer} - ${song.name}"
}
