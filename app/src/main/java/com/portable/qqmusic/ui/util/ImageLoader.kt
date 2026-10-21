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
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.LocalMeta
import com.portable.qqmusic.core.Config
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.player.PlayerController
import java.io.File

/**
 * 封面加载。
 *
 * 统一走 Glide 的磁盘缓存 (默认策略), 并限制内存缓存比例,
 * 避免在小内存设备上因封面缓存导致频繁 GC。
 */
object ImageLoader {

    /** 已经确认「文件里没有内嵌封面」的路径, 避免每次绑定都重新抠一遍 */
    private val noArt = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 正在抠封面的 ImageView → 路径, 列表复用时防止旧结果盖到新行上 */
    private val pendingArt = java.util.WeakHashMap<ImageView, String>()

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
        if (!song.isLocal) {
            load(context, song.coverUrl(), target)
            return
        }
        /*
         * 本地歌的封面顺序: 借到的在线专辑封面 → 文件内嵌封面 → 应用占位图。
         *
         * 以前这里用的是 MediaStore 的 albumart URI, 查不到内嵌封面的专辑会拿到
         * 系统 MediaProvider 自带的「默认专辑图」—— 于是同一份列表里有的行是系统默认图、
         * 有的行(albumId=0)是应用占位图, 看起来像「随机分配了一个通用封面」,
         * 而且清应用数据也不会变(图存在系统媒体库那边)。现在统一自己解内嵌封面,
         * 解不出来就一定是应用占位图。
         */
        val borrowedAlbum = LocalMeta.cached(context, song)?.albumMid.orEmpty()
        if (borrowedAlbum.isNotEmpty()) {
            load(context, String.format(Config.IMG_SONG, borrowedAlbum), target)
            return
        }
        val path = song.localPath
        if (path.isNotEmpty()) {
            val art = artFile(context, path)
            if (art.exists() && art.length() > 0) {
                load(context, art.absolutePath, target)
                return
            }
            if (!noArt.contains(path)) {
                target.setImageResource(R.drawable.ic_music_placeholder)
                pendingArt[target] = path
                Task.io {
                    val ok = extractArt(path, art)
                    if (!ok) noArt.add(path)
                    Task.main {
                        if (pendingArt[target] == path) {
                            pendingArt.remove(target)
                            if (ok) load(context, art.absolutePath, target)
                        }
                    }
                }
                return
            }
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

    /** 内嵌封面解出来缓存成的图片文件; 用 路径+大小+修改时间 做键, 换文件自动失效 */
    private fun artFile(context: Context, path: String): File {
        val f = File(path)
        val key = Integer.toHexString((path + "|" + f.length() + "|" + f.lastModified()).hashCode())
        return File(File(context.cacheDir, "localart"), "$key.img")
    }

    /** 从音频文件里抠内嵌封面写进缓存目录; 没有封面返回 false */
    private fun extractArt(path: String, out: File): Boolean = try {
        val r = android.media.MediaMetadataRetriever()
        val bytes = try {
            r.setDataSource(path)
            r.embeddedPicture
        } finally {
            try {
                r.release()
            } catch (_: Throwable) {
            }
        }
        if (bytes == null || bytes.isEmpty()) {
            false
        } else {
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            true
        }
    } catch (t: Throwable) {
        Log.w("ImageLoader.art", t)
        false
    }

    fun loadPlaylist(context: Context, playlist: Playlist?, target: ImageView) {
        // 「我喜欢」是虚拟歌单, 接口不给封面 —— 用固定的米白底红心, 列表与详情页保持一致
        if (playlist != null && playlist.dirid == com.portable.qqmusic.data.api.UserApi.FAVORITE_DIRID) {
            target.setImageResource(R.drawable.bg_favorite_cover)
            return
        }
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
