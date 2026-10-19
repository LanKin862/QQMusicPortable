package com.portable.qqmusic.ui.util

import android.content.Context
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Task
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
                // 自定义单行布局 (16sp) 代替 setItems 的默认样式, 长歌单名一屏能多放几行。
                val titles = lists.map { it.title }
                val adapter = ArrayAdapter(context, R.layout.item_dialog_text, titles)
                AlertDialog.Builder(context)
                    .setTitle(R.string.add_to_playlist_title)
                    .setAdapter(adapter) { _, which ->
                        addTo(context, lists[which].dirid, lists[which].title, song)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
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
        Task.io {
            val ok = runCatching { UserApi.setFavorite(song, favorite) }.getOrElse {
                Diag.e("SongActions", "收藏失败 ${song.mid}", it)
                false
            }
            if (ok || !favorite) {
                Task.main {
                    if (ok) {
                        onDone(favorite)
                    } else {
                        Tip.toast(context, context.getString(R.string.favorite_failed))
                        onRollback()
                    }
                }
                return@io
            }
            // 明文添加被挡 (80105): 转网页版兜底, 期间不弹任何「正在处理」的东西。
            Task.main {
                val activity = context as? android.app.Activity
                if (activity == null) {
                    Tip.toast(context, context.getString(R.string.favorite_failed))
                    onRollback()
                    return@main
                }
                WebFavorite.addFavorite(activity, song) { added ->
                    if (added) onDone(true) else onRollback()
                }
            }
        }
    }
}
