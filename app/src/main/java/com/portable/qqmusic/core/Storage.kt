package com.portable.qqmusic.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 存储位置定义。
 *
 * Android 10+ 启用分区存储后, 应用无法随意写入任意路径, 因此提供:
 *  - 应用私有目录 (始终可写, 无需权限)
 *  - 公共音乐目录 (Android 9 及以下直接写; 10+ 通过 MediaStore 落盘)
 */
enum class StorageLocation(
    val label: String,
    /** 相对路径, 用于 MediaStore 的 RELATIVE_PATH 或传统模式下的 sd 卡子目录 */
    val relativePath: String
) {
    APP_PRIVATE("应用内部存储 (推荐)", "QQMusicPortable"),
    APP_EXTERNAL("应用外部存储", "QQMusicPortable"),
    MUSIC_PUBLIC("系统音乐目录 /Music/QQMusicPortable", "Music/QQMusicPortable"),
    DOWNLOADS_PUBLIC("系统下载目录 /Download/QQMusicPortable", "Download/QQMusicPortable");

    companion object {
        fun fromPref(raw: String?): StorageLocation = try {
            valueOf(raw ?: APP_EXTERNAL.name)
        } catch (t: Throwable) {
            APP_EXTERNAL
        }
    }
}

/**
 * 下载 / 缓存目录解析。
 */
object Storage {

    private const val CACHE_ROOT = "cache"
    private const val AUDIO_SUB = "audio"
    private const val IMAGE_SUB = "image"
    private const val META_SUB = "meta"
    private const val LYRIC_SUB = "lyric"
    private const val DOWNLOAD_DIR = "download"

    fun location(): StorageLocation =
        StorageLocation.fromPref(Store.str(Store.K.DOWNLOAD_DIR, StorageLocation.APP_EXTERNAL.name))

    /**
     * 缓存根目录。
     *
     * 刻意用 filesDir 而非 cacheDir: cacheDir 会被系统在存储紧张时整个清掉,
     * 而歌单列表/封面这类「相对固定」的数据被清掉会让界面白一下。
     * 目录内部的清理由 CacheManager 按分层优先级负责。
     */
    fun cacheRoot(context: Context): File =
        File(context.filesDir, CACHE_ROOT).apply { if (!exists()) mkdirs() }

    /** 歌曲缓存 (音频文件): 清理优先级最低, 空间不够时最先删这一层 */
    fun audioDir(context: Context): File =
        File(cacheRoot(context), AUDIO_SUB).apply { if (!exists()) mkdirs() }

    /** 封面图片缓存 */
    fun imageDir(context: Context): File =
        File(cacheRoot(context), IMAGE_SUB).apply { if (!exists()) mkdirs() }

    /** 歌单/歌曲列表等元数据: 清理优先级最高, 最后才动 */
    fun metaDir(context: Context): File =
        File(cacheRoot(context), META_SUB).apply { if (!exists()) mkdirs() }

    /**
     * 歌词与译文缓存。
     *
     * 单独占一层 (而不是和歌单数据混在 metaDir) 是为了能各给各的配额:
     * 译文是 30 天有效期、重新生成要花 token, 不该被歌单列表挤掉。
     */
    fun lyricDir(context: Context): File =
        File(cacheRoot(context), LYRIC_SUB).apply { if (!exists()) mkdirs() }

    /** 播放缓存目录 (音频层), 供 CacheManager 使用 */
    fun cacheDir(context: Context): File = audioDir(context)

    /**
     * 下载目录。
     * Android 9 及以下返回可直接写入的文件路径; Android 10+ 若选择公共目录,
     * 返回的是「应用专属外部存储」下的镜像目录, 实际落盘走 MediaStore。
     */
    fun downloadDir(context: Context): File {
        val loc = location()
        val root = when (loc) {
            StorageLocation.APP_PRIVATE -> File(context.filesDir, DOWNLOAD_DIR)
            StorageLocation.APP_EXTERNAL -> context.getExternalFilesDir(null) ?: File(context.filesDir, DOWNLOAD_DIR)
            StorageLocation.MUSIC_PUBLIC ->
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    File(context.getExternalFilesDir(null), "Music")
                } else {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "QQMusicPortable")
                }
            StorageLocation.DOWNLOADS_PUBLIC ->
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    File(context.getExternalFilesDir(null), "Download")
                } else {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "QQMusicPortable")
                }
        }
        val dir = File(root, "").let { if (loc == StorageLocation.APP_PRIVATE || loc == StorageLocation.APP_EXTERNAL) File(root, DOWNLOAD_DIR) else root }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 是否需要在 Android 10+ 通过 MediaStore 写入公共目录 */
    fun needMediaStore(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 29 && (location() == StorageLocation.MUSIC_PUBLIC || location() == StorageLocation.DOWNLOADS_PUBLIC)

    /** 公共目录对应的 RELATIVE_PATH (MediaStore 用) */
    fun mediaStoreRelativePath(): String = when (location()) {
        StorageLocation.MUSIC_PUBLIC -> Environment.DIRECTORY_MUSIC + "/QQMusicPortable"
        StorageLocation.DOWNLOADS_PUBLIC -> Environment.DIRECTORY_DOWNLOADS + "/QQMusicPortable"
        else -> Environment.DIRECTORY_MUSIC + "/QQMusicPortable"
    }

    /** 人类可读的目录展示文本 */
    fun displayPath(context: Context): String = downloadDir(context).absolutePath
}
