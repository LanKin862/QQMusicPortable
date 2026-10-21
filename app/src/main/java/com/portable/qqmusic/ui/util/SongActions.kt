package com.portable.qqmusic.ui.util

import android.content.Context
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.api.FavoriteResult
import com.portable.qqmusic.data.api.Login
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.data.api.UserApi

/**
 * 歌曲的两个写操作: 加入「我喜欢」、加入自建歌单。
 *
 * 歌单页的「更多」菜单和播放页的按钮都走这里, 免得两处各写一遍 toast 文案。
 */
object SongActions {

    /**
     * 弹「加入到哪个歌单」的选择框, 选中后直接添加。
     *
     * 选择框是异步拉来的: 自建歌单列表要走一次网络 (`createdPlaylists` 本身有缓存)。
     * 添加前会先翻目标歌单确认没重复 —— 服务端对重复添加不一定回错误码。
     */
    fun addToPlaylist(context: Context, song: Song) {
        if (!Login.isLoggedIn()) {
            Tip.toast(context, context.getString(R.string.need_login))
            return
        }
        if (!song.hasRealMid) {
            Tip.toast(context, context.getString(R.string.playlist_add_failed))
            return
        }
        Task.io {
            val lists = runCatching {
                UserApi.createdPlaylists().filter { it.dirid > 0L }
            }.getOrElse {
                Diag.e("SongActions", "取自建歌单失败", it)
                emptyList()
            }
            Task.main {
                if (lists.isEmpty()) {
                    Tip.toast(context, context.getString(R.string.no_created_playlist))
                    return@main
                }
                Menus.show(
                    context,
                    context.getString(R.string.add_to_playlist_title),
                    lists.map { it.title }
                ) { which ->
                    addTo(context, lists[which].dirid, lists[which].title, song)
                }
            }
        }
    }

    /**
     * 批量版「加入歌单」: 多选态下的功能键走这里。
     *
     * 逐首调同一条写接口 (服务端没有批量参数), 只统计成功数, 失败不中断其余歌曲。
     */
    fun addToPlaylist(context: Context, songs: List<Song>) {
        if (!Login.isLoggedIn()) {
            Tip.toast(context, context.getString(R.string.need_login))
            return
        }
        val picked = songs.filter { it.hasRealMid }
        if (picked.isEmpty()) {
            Tip.toast(context, context.getString(R.string.playlist_add_failed))
            return
        }
        Task.io {
            val lists = runCatching {
                UserApi.createdPlaylists().filter { it.dirid > 0L }
            }.getOrElse {
                Diag.e("SongActions", "取自建歌单失败", it)
                emptyList()
            }
            Task.main {
                if (lists.isEmpty()) {
                    Tip.toast(context, context.getString(R.string.no_created_playlist))
                    return@main
                }
                Menus.show(
                    context,
                    context.getString(R.string.add_to_playlist_title),
                    lists.map { it.title }
                ) { which ->
                    addAllTo(context, lists[which].dirid, picked)
                }
            }
        }
    }

    private fun addAllTo(context: Context, dirId: Long, songs: List<Song>) {
        Task.io {
            var ok = 0
            for (song in songs) {
                val added = runCatching {
                    UserApi.playlistHasSong(dirId, song) || UserApi.addSongToPlaylist(dirId, song)
                }.getOrDefault(false)
                if (added) ok++
            }
            val done = ok
            Task.main {
                Tip.toast(
                    context,
                    context.getString(R.string.batch_added_to_playlist, done)
                )
            }
        }
    }

    private fun addTo(context: Context, dirId: Long, title: String, song: Song) {
        Task.io {
            val exists = runCatching { UserApi.playlistHasSong(dirId, song) }.getOrDefault(false)
            if (exists) {
                Task.main {
                    Tip.toast(context, context.getString(R.string.playlist_song_exists, title))
                }
                return@io
            }
            val ok = runCatching { UserApi.addSongToPlaylist(dirId, song) }.getOrDefault(false)
            Task.main {
                if (ok) {
                    Tip.toast(context, context.getString(R.string.playlist_song_added, title))
                } else {
                    Tip.toast(context, context.getString(R.string.playlist_add_failed))
                }
            }
        }
    }

    /**
     * 收藏 / 取消收藏。
     *
     * 调用方一律「动画先行」: 先把爱心切过去, 这里只负责静默写入, 成功不打扰用户;
     * 失败时回调 [onRollback] 让调用方把图标翻回来, 并弹一句短提示。
     *
     * 「取消」走明文接口就够了 (`DelSonglist dirId=201` 是允许的)。
     * 「添加」明文会被服务端回 `80105`, 所以先试一次快的, 失败就转 [WebFavorite]
     * 借网页版的签名通道 —— 那条路要加载一个网页, 大约 6~9 秒, 期间界面完全不受影响。
     */
    fun setFavorite(
        context: Context,
        song: Song,
        favorite: Boolean,
        onDone: (Boolean) -> Unit = {},
        onRollback: () -> Unit = {}
    ) {
        if (!Login.isLoggedIn()) {
            Tip.toast(context, context.getString(R.string.need_login))
            onRollback()
            return
        }
        if (!song.hasRealMid) {
            onRollback()
            return
        }

        // 乐观更新: 点下去的这一刻就把状态写进全局缓存并广播出去。
        // 服务端答复要 6~9 秒, 这期间用户可能退出播放页再进来 —— 没有这一句,
        // 重进时看到的还是旧状态 (用户反馈的「状态混乱」)。
        UserApi.markFavorite(song.mid, favorite)

        // 失败路径统一回滚: 把全局状态改回原样, 再让调用方把图标翻回来。
        val revert: () -> Unit = {
            UserApi.markFavorite(song.mid, !favorite)
            onRollback()
        }

        Task.io {
            val result = runCatching { UserApi.setFavorite(song, favorite) }.getOrElse {
                Diag.e("SongActions", "收藏失败 ${song.mid}", it)
                FavoriteResult(false, FavoriteResult.CODE_NETWORK)
            }
            when {
                result.ok -> Task.main { onDone(favorite) }

                // 明文添加被挡 (80105): 这是唯一值得转网页版兜底的失败 —— 别的失败再走
                // 一遍网页签名, 只是让用户白等 6~9 秒。
                result.needsWebSign && favorite -> Task.main {
                    val activity = context as? android.app.Activity
                    if (activity == null) {
                        Tip.toast(context, context.getString(R.string.favorite_failed))
                        revert()
                        return@main
                    }
                    WebFavorite.addFavorite(activity, song) { added ->
                        if (added) onDone(true) else revert()
                    }
                }

                result.notLoggedIn -> Task.main {
                    Tip.toast(context, context.getString(R.string.need_login))
                    revert()
                }

                result.badArgument -> Task.main {
                    Tip.toast(context, context.getString(R.string.favorite_failed))
                    revert()
                }

                result.networkError -> Task.main {
                    Tip.toast(context, context.getString(R.string.favorite_failed_network))
                    revert()
                }

                // 服务端驳回: 把码透出来, 用户报问题时能直接对上日志。
                else -> Task.main {
                    Tip.toast(context, context.getString(R.string.favorite_failed_code, result.code))
                    revert()
                }
            }
        }
    }
}
