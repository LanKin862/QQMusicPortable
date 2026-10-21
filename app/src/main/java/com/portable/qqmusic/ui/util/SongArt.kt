package com.portable.qqmusic.ui.util

import android.content.Context
import com.portable.qqmusic.core.Config
import com.portable.qqmusic.data.LocalMeta
import com.portable.qqmusic.data.api.Song

/**
 * 状态栏 / 锁屏媒体卡片该用哪张封面。
 *
 * 和 [ImageLoader.loadSong] 的顺序保持一致, 但不能直接用 `song.coverUrl()`:
 * 本地歌的 `coverUrl()` 优先返回 MediaStore 的 albumart URI —— 查不到内嵌封面的专辑,
 * 那个 URI 给的是**系统媒体库自带的默认专辑图**, 于是状态栏永远显示那张系统图,
 * 而不是我们在线匹配到的封面。
 *
 * 这里对本地歌优先用「借到的在线专辑封面」; 没有借到才退回已经解出来的内嵌封面文件;
 * 两者都没有就返回空, 宁可不显示, 也不再拿系统默认图凑数。
 */
object SongArt {

    private val memo = HashMap<String, Pair<Int, String>>()
    private const val MEMO_MAX = 128

    fun coverUrl(context: Context, song: Song?): String {
        if (song == null) return ""
        if (!song.isLocal) return song.coverUrl()
        val key = song.mid
        val rev = LocalMeta.revision
        memo[key]?.let { if (it.first == rev) return it.second }
        val url = compute(context.applicationContext, song)
        if (memo.size > MEMO_MAX) memo.clear()
        memo[key] = rev to url
        return url
    }

    private fun compute(context: Context, song: Song): String {
        // 1. 借到的在线专辑封面
        val albumMid = try {
            LocalMeta.cached(context, song)?.albumMid.orEmpty()
        } catch (t: Throwable) {
            ""
        }
        if (albumMid.isNotEmpty()) return String.format(Config.IMG_SONG, albumMid)
        // 2. 文件里已经解出来的内嵌封面
        val path = song.localPath
        if (path.isNotEmpty()) {
            val f = ImageLoader.localArtFile(context, path)
            if (f.exists() && f.length() > 0) return f.toURI().toString()
        }
        return ""
    }
}
