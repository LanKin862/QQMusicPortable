package com.portable.qqmusic.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.data.api.Song
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地音乐扫描。
 *
 * 只读取系统媒体库 (MediaStore.Audio), 不递归遍历文件系统 ——
 * 后者在有大文件夹的设备上会带来明显的 IO 与耗时。
 * 下载管理器落在应用目录里的离线文件也一并并入, 见 [query]。
 */
object LocalMusic {

    /** MediaStore 条目的 mid 前缀, `local_<id>` */
    private const val PREFIX_LOCAL = "local_"

    /** 下载目录条目的 mid 前缀, `file_<hash>` */
    private const val PREFIX_FILE = "file_"

    /**
     * 查询本地音频。
     *
     * @param limit 最多返回条数 (默认 500, 避免一次性构建过大的列表)
     */
    fun query(context: Context, limit: Int = 500): List<Song> {
        val out = ArrayList<Song>(64)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 15000"
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                // DATA 列在个别 ROM 上可能缺失, 取不到就留空, 播放时再兜底查一次
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: continue
                    val artist = cleanTag(cursor.getString(artistCol)).ifEmpty { Song.UNKNOWN_SINGER }
                    val album = cleanTag(cursor.getString(albumCol))
                    val duration = (cursor.getLong(durCol) / 1000).toInt()
                    val path = if (dataCol >= 0 && !cursor.isNull(dataCol)) cursor.getString(dataCol) ?: "" else ""
                    // 媒体库里可能残留「文件已被删掉、记录还在」的脏行 (删除时只删了文件没删记录,
                    // 或者用户在别的应用/文件管理器里删了歌)。不过滤的话这首歌会反复出现,
                    // 点删除又会因为「文件不存在」而被当成删除失败。
                    if (path.isNotEmpty() && !File(path).exists()) continue
                    // 没有内嵌封面的文件 albumId 多为 0, 拼出来的 URI 打开会失败, 所以只在本专辑有封面时才用
                    val albumId =
                        if (albumIdCol >= 0 && !cursor.isNull(albumIdCol)) cursor.getLong(albumIdCol) else 0L
                    val cover = if (albumId > 0L) albumArtUri(albumId) else ""

                    out.add(
                        Song(
                            mid = "local_$id",
                            name = title,
                            singer = artist,
                            albumName = album,
                            durationSec = duration,
                            songId = id,
                            type = Song.TYPE_LOCAL,
                            localPath = path,
                            localCover = cover
                        ).copy(index = out.size).let { LocalMeta.withBorrowedSinger(context, it) }
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w("LocalMusic", t)
        }

        // 下载目录里的离线音乐也并入「本地音乐」。
        // 之前是「媒体库为空才兜底」, 于是下载好的歌在「本地」里根本看不到 (只在「下载」入口)。
        // 同一个文件可能既被媒体库收录、又躺在应用专属目录里, 按规范化路径 + 文件名双双去重,
        // 免同一首歌出现两行。
        val seenPaths = HashMap<String, Int>(out.size * 2)
        val seenNames = HashSet<String>(out.size * 2)
        for ((i, s) in out.withIndex()) {
            if (s.localPath.isEmpty()) continue
            seenPaths[canonicalPath(s.localPath)] = i
            seenNames.add(File(s.localPath).name)
        }
        val downloaded = DownloadManager.downloadedFiles(context)
        Diag.i(
            "LocalMusic.query",
            "media=${out.size} downloads=${downloaded.size} dir=${com.portable.qqmusic.core.Storage.downloadDir(context)}"
        )
        for (f in downloaded) {
            val cp = canonicalPath(f.absolutePath)
            val existing = seenPaths[cp]
            if (existing != null) {
                // 媒体库也收录了这个文件: 但媒体库那条只有 title/artist/时长, 没有原曲 mid,
                // 播放页拉不到歌词。有元信息时用下载条目把它换掉。
                if (DownloadManager.metaFor(f.name) != null) {
                    out[existing] = fileToSong(f, existing)
                }
                continue
            }
            if (out.size >= limit) break
            if (!seenNames.add(f.name)) continue
            seenPaths[cp] = out.size
            out.add(fileToSong(f, out.size))
        }
        Diag.i("LocalMusic.query", "result=${out.size}")
        return out
    }

    /** MediaScanner 认得的音频扩展名。 */
    private val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "ape", "wma", "opus")

    /**
     * 让系统媒体库把常见目录重新索引一遍。
     *
     * MediaStore 是系统缓存: 从电脑拷进来的歌、别的应用下载的歌都不会立刻出现在 [query] 结果里,
     * 得等系统自己扫 —— 那个时机完全不可控, 慢的时候要重启才生效。这里主动把下载目录与系统的
     * 音乐/下载目录下的音频文件交给 MediaScanner 扫一遍, 扫完再查才看得到新歌。
     *
     * @param onDone 扫完回调, **保证在主线程**; 没有可扫的文件时也会回调一次
     */
    fun rescan(context: Context, onDone: () -> Unit) {
        val dirs = listOfNotNull(
            com.portable.qqmusic.core.Storage.downloadDir(context),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        val files = ArrayList<String>()
        for (dir in dirs) {
            val list = dir.listFiles() ?: continue
            for (f in list) {
                if (!f.isFile) continue
                if (f.extension.lowercase() in AUDIO_EXT) files.add(f.absolutePath)
            }
        }

        val main = Handler(Looper.getMainLooper())
        if (files.isEmpty()) {
            Diag.i("LocalMusic.rescan", "没有可扫的音频文件")
            main.post(onDone)
            return
        }

        // scanFile 每个文件回调一次, 全部回调完才算扫完。但个别 ROM 上回调可能不触发,
        // 所以再加一个超时兜底 —— 用 AtomicBoolean 保证 onDone 只会被调一次。
        val finished = AtomicBoolean(false)
        val deliver: (Long) -> Unit = { delay ->
            if (finished.compareAndSet(false, true)) main.postDelayed(onDone, delay)
        }
        val left = AtomicInteger(files.size)
        Diag.i("LocalMusic.rescan", "提交 ${files.size} 个文件")
        MediaScannerConnection.scanFile(context, files.toTypedArray(), null) { _, _ ->
            if (left.decrementAndGet() == 0) {
                // 媒体库写完比回调稍慢一点, 留一点余量再让调用方去查
                deliver(400L)
            }
        }
        main.postDelayed({ deliver(0L) }, 3_000L)
    }

    fun fileToSong(f: File, index: Int = 0): Song {
        // 下载时存下了原曲信息: 用它才能显示真实时长/封面, 播放页也才能按原曲 mid 拉歌词。
        val meta = DownloadManager.metaFor(f.name)
        if (meta != null && meta.mid.isNotEmpty()) {
            return Song(
                mid = meta.mid,
                name = meta.name.ifEmpty { DownloadManager.baseNameOfFile(f.name) },
                singer = meta.singer,
                albumMid = meta.albumMid,
                albumName = meta.albumName,
                durationSec = if (meta.durationSec > 0) meta.durationSec else DownloadManager.durationOf(f),
                songId = meta.songId,
                type = Song.TYPE_LOCAL,
                index = index,
                localPath = f.absolutePath
            )
        }
        // 老版本下载的文件没有元信息: 文件名猜歌手/歌名, 时长只能真的读一遍文件头
        // (下载的文件名带音质后缀 _HQ/_SQ/_HiRes, 显示时要去掉)
        val name = DownloadManager.baseNameOfFile(f.name)
        val split = name.split(" - ", limit = 2)
        val artist = if (split.size == 2) split[0] else Song.UNKNOWN_SINGER
        val title = if (split.size == 2) split[1] else name
        return Song(
            mid = "$PREFIX_FILE${f.absolutePath.hashCode()}",
            name = title,
            singer = artist,
            durationSec = DownloadManager.durationOf(f),
            type = Song.TYPE_LOCAL,
            index = index,
            localPath = f.absolutePath
        )
    }

    /**
     * 本地歌曲的可播放路径。
     *
     * 必须是**真实文件路径**: `MediaPlayer.setDataSource(String)` 只认文件路径,
     * 传 MediaStore 的 `content://media/external/audio/media/<id>` 会被当作
     * 一个不存在的路径, 直接 `onError(-38)`。
     *
     * 优先用扫描时读到的 `DATA`, 缺失时 (个别 ROM 不提供该列) 再按 id 查一次库。
     */
    fun pathOf(song: Song): String? {
        if (!song.isLocal) return null
        if (song.localPath.isNotEmpty()) return song.localPath
        if (song.mid.startsWith(PREFIX_FILE)) return null
        return queryDataPath(song.songId)
    }

    // ---------------- 删除 ----------------

    /** [delete] 的结果 */
    sealed class DeleteResult {
        /** 文件已经删掉 (或本来就不存在) */
        object Done : DeleteResult()

        /**
         * 分区存储下必须由用户在系统弹窗里确认。
         * 调用方要把 [sender] 交给 `ActivityResultContracts.StartIntentSenderForResult`。
         */
        class NeedConfirm(val sender: android.content.IntentSender) : DeleteResult()

        object Failed : DeleteResult()
    }

    /**
     * 删除一首本地/已下载歌曲。
     *
     * ⚠️ 之前这里直接用 `File.delete()`, 在 targetSdk 30+ 的分区存储上必然失败:
     * 界面只把这一行从内存列表里抹掉, 文件还在媒体库里, 返回「我的」再进来又出现;
     * 第二次再删就报「删除失败, 请检查存储权限」。已下载的歌删得掉是因为它们落在
     * 应用专属目录, 不需要系统授权。
     *
     * 现在按系统要求走 MediaStore:
     * - API 30+ 用 [MediaStore.createDeleteRequest] 让系统弹一次确认 (可批量)
     * - API 29 直接 `resolver.delete`, 遇到 RecoverableSecurityException 再转系统确认
     * - API 28- 才退回普通文件删除
     */
    fun delete(context: Context, song: Song): DeleteResult {
        val path = song.localPath.ifEmpty { pathOf(song).orEmpty() }
        val uri = findMediaUri(context, song, path)
        Diag.i(
            "LocalMusic.delete",
            "mid=${song.mid} path=$path uri=$uri sdk=${Build.VERSION.SDK_INT}"
        )
        if (uri == null) {
            // 不在媒体库里 (应用专属目录里的下载文件), 直接删文件即可
            return deleteFileOnly(path)
        }
        return try {
            when {
                Build.VERSION.SDK_INT >= 30 -> {
                    val sender = MediaStore.createDeleteRequest(
                        context.contentResolver, listOf(uri)
                    ).intentSender
                    DeleteResult.NeedConfirm(sender)
                }
                Build.VERSION.SDK_INT >= 29 -> {
                    val n = context.contentResolver.delete(uri, null, null)
                    if (n > 0) DeleteResult.Done else DeleteResult.Failed
                }
                else -> deleteWithStore(context, uri, path)
            }
        } catch (t: Throwable) {
            // API 29 上删「别的应用创建的」媒体会抛 RecoverableSecurityException,
            // 里面带着一个系统确认的 IntentSender
            val sender = recoverableSender(t)
            if (sender != null) {
                DeleteResult.NeedConfirm(sender)
            } else {
                Log.w("LocalMusic.delete", t)
                DeleteResult.Failed
            }
        }
    }

    private fun recoverableSender(t: Throwable): android.content.IntentSender? =
        if (Build.VERSION.SDK_INT >= 29 &&
            t is android.app.RecoverableSecurityException
        ) {
            t.userAction.actionIntent.intentSender
        } else {
            null
        }

    private fun deleteFileOnly(path: String): DeleteResult {
        if (path.isEmpty()) return DeleteResult.Failed
        val f = File(path)
        Diag.i("LocalMusic.delete", "fileOnly path=$path exists=${f.exists()}")
        if (!f.exists()) return DeleteResult.Done
        return try {
            val ok = f.delete()
            Diag.i("LocalMusic.delete", "fileOnly delete=$ok path=$path")
            if (ok) DeleteResult.Done else DeleteResult.Failed
        } catch (t: Throwable) {
            Log.w("LocalMusic.delete", t)
            Diag.e("LocalMusic.delete", "fileOnly 抛异常 path=$path", t)
            DeleteResult.Failed
        }
    }

    /**
     * API 28- 的删除路径。
     *
     * 这里**必须连媒体库记录一起删**: 只 `File.delete()` 的话, 文件没了但
     * `content://media/external/audio/media/<id>` 这行还在, 下次扫描又把它列出来,
     * 于是「删了还在」; 再删一次文件已不存在, 就会被当成「删除失败, 请检查存储权限」。
     */
    private fun deleteWithStore(context: Context, uri: Uri, path: String): DeleteResult {
        // MediaProvider 会同时删掉数据库行和磁盘上的文件, 优先走这条
        val viaStore = runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { Diag.e("LocalMusic.delete", "resolver.delete 抛异常 uri=$uri", it) }
            .getOrDefault(-1)
        Diag.i("LocalMusic.delete", "resolver.delete=$viaStore uri=$uri")
        if (viaStore > 0) return DeleteResult.Done
        // provider 拒绝 (返回 0 或抛异常): 退回直接删文件, 再补一刀清残留记录
        val fileResult = deleteFileOnly(path)
        val cleared = runCatching { context.contentResolver.delete(uri, null, null) }
            .getOrDefault(-1)
        Diag.i("LocalMusic.delete", "清理残留记录=$cleared uri=$uri")
        return if (cleared > 0 || fileResult is DeleteResult.Done) {
            DeleteResult.Done
        } else {
            DeleteResult.Failed
        }
    }

    /** 找到这首歌对应的媒体库条目; 不在库里返回 null */
    private fun findMediaUri(context: Context, song: Song, path: String): Uri? {
        val resolver = context.contentResolver
        if (song.mid.startsWith(PREFIX_LOCAL)) {
            val id = song.mid.removePrefix(PREFIX_LOCAL).toLongOrNull()
            if (id != null && id > 0L) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                if (mediaExists(resolver, uri)) return uri
            }
        }
        if (path.isEmpty()) return null
        return try {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(path),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                    )
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            Log.w("LocalMusic.findMediaUri", t)
            null
        }
    }

    private fun mediaExists(resolver: android.content.ContentResolver, uri: Uri): Boolean = try {
        resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (t: Throwable) {
        false
    }

    private fun canonicalPath(path: String): String = try {
        File(path).canonicalPath
    } catch (t: Throwable) {
        path
    }

    /**
     * 清掉 MediaStore 的占位值。
     *
     * 没有 ID3 标签的文件, MediaStore 会把 ARTIST/ALBUM 填成字面量 `<unknown>`,
     * 直接显示就会在界面上出现 `<unknown> - <unknown>`, 所以这里一律当成"没有"。
     * 返回空串而不是"未知歌手" —— 调用方决定怎么兜底, 否则专辑也会被写成"未知歌手",
     * 界面上就成了「未知歌手 - 未知歌手」。
     */
    private fun cleanTag(raw: String?): String {
        val s = raw?.trim().orEmpty()
        return if (s.isEmpty() || s.equals(MediaStore.UNKNOWN_STRING, ignoreCase = true)) "" else s
    }

    /** 系统内置的专辑封面 URI, Glide 可直接加载 */
    private fun albumArtUri(albumId: Long): String =
        "content://media/external/audio/albumart/$albumId"

    /** 按 MediaStore id 查 `DATA` 列, 取不到返回 null */
    private fun queryDataPath(id: Long): String? = try {
        val ctx = com.portable.qqmusic.App.instance
        ctx.contentResolver.query(
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (t: Throwable) {
        Log.w("LocalMusic.queryDataPath", t)
        null
    }

    /** 预估扫描耗时 (调试用) */
    inline fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
    }
}
