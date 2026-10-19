package com.portable.qqmusic.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.data.LocalMeta
import com.portable.qqmusic.core.Config
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.player.PlayerController

/**
 * 封面加载。
 *
 * 统一走 Glide 的磁盘缓存 (默认策略), 并限制内存缓存比例,
 * 避免在小内存设备上因封面缓存导致频繁 GC。
 */
object ImageLoader {

    private val roundOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .placeholder(R.drawable.ic_music_placeholder)
        .error(R.drawable.ic_music_placeholder)

    fun load(context: Context, url: String?, target: ImageView) {
        if (url.isNullOrEmpty()) {
            target.setImageResource(R.drawable.ic_music_placeholder)
            return
        }
        try {
            Glide.with(context.applicationContext)
                .load(url)
                .apply(roundOptions)
                .into(target)
        } catch (t: Throwable) {
            Log.w("ImageLoader", t)
            target.setImageResource(R.drawable.ic_music_placeholder)
        }
    }

    fun loadSong(context: Context, song: Song?, target: ImageView) {
        if (song == null) {
            load(context, null, target)
            return
        }
        val url = song.coverUrl()
        if (!song.isLocal) {
            load(context, url, target)
            return
        }
        /*
         * 本地歌一律先问借到的封面。
         *
         * 下载留下的 albumMid 有时是空的、有时指向一张已经下架的图(直接 404),
         * 光看 coverUrl() 非空就以为有封面, 结果是空白占位图 —— 之前就是这个坑。
         * 借来的 albumMid 是按歌名重新搜出来的, 命中率更高。
         */
        val borrowedAlbum = LocalMeta.cached(context, song)?.albumMid.orEmpty()
        if (borrowedAlbum.isNotEmpty()) {
            load(context, String.format(Config.IMG_SONG, borrowedAlbum), target)
            return
        }
        if (url.isNotEmpty()) {
            load(context, url, target)
            return
        }
        target.setImageResource(R.drawable.ic_music_placeholder)
        LocalMeta.resolve(context, song) { borrowed ->
            val albumMid = borrowed?.albumMid.orEmpty()
            if (albumMid.isNotEmpty()) {
                load(context, String.format(Config.IMG_SONG, albumMid), target)
            }
            // 借到封面时同一份结果里就带着真歌手, 顺手把「未知歌手」覆盖掉
            val singer = borrowed?.singer.orEmpty()
            if (singer.isNotEmpty()) PlayerController.applySinger(song.mid, singer)
        }
    }

    fun loadPlaylist(context: Context, playlist: Playlist?, target: ImageView) {
        load(context, playlist?.coverUrl(), target)
    }

    fun loadRound(context: Context, url: String?, target: ImageView, radiusPx: Int) {
        if (url.isNullOrEmpty()) {
            target.setImageResource(R.drawable.ic_music_placeholder)
            return
        }
        try {
            Glide.with(context.applicationContext)
                .load(url)
                .apply(RequestOptions().transform(RoundedCorners(radiusPx)))
                .into(target)
        } catch (t: Throwable) {
            target.setImageResource(R.drawable.ic_music_placeholder)
        }
    }

    /**
     * 只取 Bitmap 不上屏, 供封面取色使用。
     *
     * 走的是同一份 Glide 磁盘/内存缓存: 封面已经在界面上加载过时, 这次几乎立即回调,
     * 不会额外下载。解码尺寸压在 [width] 见方以内 —— 取色只关心大色块, 不需要原图。
     */
    fun loadBitmap(
        context: Context,
        url: String?,
        width: Int = 256,
        onReady: (Bitmap) -> Unit,
        onFail: () -> Unit = {}
    ) {
        if (url.isNullOrEmpty()) {
            onFail()
            return
        }
        try {
            Glide.with(context.applicationContext)
                .asBitmap()
                .load(url)
                .apply(RequestOptions().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC))
                .into(object : CustomTarget<Bitmap>(width, width) {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        onReady(resource)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        onFail()
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        } catch (t: Throwable) {
            Log.w("ImageLoader", t)
            onFail()
        }
    }

    /** 列表快速滑动时暂停加载, 停止后恢复, 降低滑动掉帧 */
    fun pauseRequests(context: Context) {
        try {
            Glide.with(context.applicationContext).pauseRequests()
        } catch (t: Throwable) {
        }
    }

    fun resumeRequests(context: Context) {
        try {
            Glide.with(context.applicationContext).resumeRequests()
        } catch (t: Throwable) {
        }
    }

    fun clear(target: ImageView) {
        try {
            Glide.with(target.context.applicationContext).clear(target)
        } catch (t: Throwable) {
        }
    }
}
