package com.portable.qqmusic.data

import android.content.Context
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Storage
import com.portable.qqmusic.core.Task
import java.io.File

/**
 * 歌单列表 / 歌曲列表这类「相对固定」数据的本地持久化。
 *
 * 为什么不放内存: 「我的」页与推荐页每次冷启动都要重新拉一遍用户歌单、
 * 收藏歌单和推荐货架, 断网时直接空白。落在磁盘上以后冷启动能立刻出内容,
 * 网络回来再刷新。
 *
 * 目录位于 [Storage.metaDir] (listDir 下), 属于缓存分层里的最高优先级:
 * CacheManager 清空间时会先删歌曲缓存、再删封面, 最后才动这一层。
 *
 * 过期判定直接用文件 mtime, 不额外写元数据文件。
 */
object MetaCache {

    /**
     * 歌词层的前缀。`lyric_trans_`(译文) 与 `lyric_src_`(原文) 都落这一层:
     * 它俩共用一套 100MB 配额, 也不该被歌单列表的清理挤掉。
     */
    private const val LYRIC_PREFIX = "lyric_"

    /** 默认有效期: 12 小时。歌单这类数据一天内基本不变 */
    const val TTL_MS = 12 * 60 * 60 * 1000L

    /** 单条上限: 500 首的歌曲列表 JSON 约 300KB, 2MB 留足余量 */
    private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024

    private fun fileOf(context: Context, key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val hash = Integer.toHexString(key.hashCode())
        // 译文单独一层: 它有独立配额, 也不该被歌单列表的清理挤掉
        val dir = if (key.startsWith(LYRIC_PREFIX)) Storage.lyricDir(context)
        else Storage.metaDir(context)
        return File(dir, "$safe-$hash.json")
    }

    /**
     * 读取缓存; 不存在或已过期返回 null。
     *
     * 会在调用线程做文件 IO, 调用方需确保在 IO 线程 (Task.io 内)。
     */
    fun get(context: Context, key: String, ttlMs: Long = TTL_MS): String? {
        return try {
            val f = fileOf(context, key)
            if (!f.exists() || f.length() == 0L) {
                Diag.i("MetaCache", "未命中 $key (文件不存在: ${f.name})")
                return null
            }
            val age = System.currentTimeMillis() - f.lastModified()
            if (ttlMs > 0 && age > ttlMs) {
                Diag.i("MetaCache", "未命中 $key (已过期 ${age / 1000}s > ${ttlMs / 1000}s)")
                return null
            }
            f.readText()
        } catch (t: Throwable) {
            Log.w("MetaCache.get", t)
            null
        }
    }

    /** 写入缓存 (异步, 不阻塞调用方) */
    fun put(context: Context, key: String, payload: String) {
        if (payload.isEmpty() || payload.length > MAX_ENTRY_BYTES) return
        val app = context.applicationContext
        Task.io {
            try {
                val f = fileOf(app, key)
                // 先写临时文件再改名: 中途被杀不会留下半截 JSON 让解析器崩
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(payload)
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    // rename 失败时退回直接覆盖, 至少不丢数据
                    f.writeText(payload)
                    tmp.delete()
                }
                // 元数据虽然小, 但写入后同样要检查上限, 保证"任何一次落盘后总量都不超限"
                CacheManager.enforceLimit(app)
                CacheManager.refreshUsage(app)
            } catch (t: Throwable) {
                Log.w("MetaCache.put", t)
            }
        }
    }

    fun remove(context: Context, key: String) {
        try {
            fileOf(context, key).delete()
        } catch (t: Throwable) {
            Log.w("MetaCache.remove", t)
        }
    }

    /** 当前元数据层占用 (字节) */
    fun usedBytes(context: Context): Long = try {
        Storage.metaDir(context).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    } catch (t: Throwable) {
        0L
    }

    /**
     * 按 key 前缀删缓存。
     *
     * 文件名是「key 去掉非法字符」+「hash」, 前缀不变形, 所以能直接比对。
     * 目前只有本地曲目匹配 (`local_meta_`) 用它 —— 规则变了或者用户点了
     * 「重新匹配」, 旧结论必须先作废。
     */
    fun clearPrefix(context: Context, prefix: String) {
        if (prefix.isEmpty()) return
        try {
            Storage.metaDir(context).listFiles()?.forEach {
                if (it.isFile && it.name.startsWith(prefix) && it.delete()) {
                    // 删掉就删掉了, 失败也不影响本次匹配结果
                }
            }
        } catch (t: Throwable) {
            Log.w("MetaCache.clearPrefix", t)
        }
    }

    /** 登录状态变化时清空: 换了账号, 上一个账号的歌单不能留在界面上 */
    fun clearAll(context: Context) {
        try {
            Storage.metaDir(context).listFiles()?.forEach { it.delete() }
        } catch (t: Throwable) {
            Log.w("MetaCache.clearAll", t)
        }
    }
}
