package com.portable.qqmusic.data.api

import android.content.Context
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.data.MetaCache

/**
 * 逐字歌词（QRC）的缓存与挂载。
 *
 * QRC 要额外发一次请求（`crypt=1`），所以解出来的行必须落盘 —— 否则每次进歌词页
 * 都要多跑一个来回。缓存条目的 key 前缀是 `lyric_qrc_`，和原文/译文一样落在 lyric 层。
 */
object QrcCache {

    private const val PREFIX = "lyric_qrc_"
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * 应用级 Context。
     *
     * [Api.lyric] 拿不到 Context（它只关心网络），而 QRC 解密完要落盘，
     * 所以这里存一份 applicationContext，由 App.onCreate 灌进来。
     */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 已解析好的逐字行，按 mid 索引。进程内直接复用，避免反复读盘 */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, List<Qrc.Line>>()

    /** 拿某首歌的逐字行；没有就返回空表 */
    fun get(context: Context, mid: String): List<Qrc.Line> {
        memory[mid]?.let { return it }
        val raw = MetaCache.get(context, PREFIX + mid, TTL_MS) ?: return emptyList()
        val lines = Qrc.parse(raw)
        if (lines.isEmpty()) return emptyList()
        memory[mid] = lines
        return lines
    }

    /** 存一份解密后的 QRC 明文；Context 没初始化就只放内存 */
    fun put(mid: String, xml: String) {
        if (mid.isEmpty() || xml.isEmpty()) return
        val lines = Qrc.parse(xml)
        if (lines.isEmpty()) return
        memory[mid] = lines
        val ctx = appContext ?: return
        MetaCache.put(ctx, PREFIX + mid, xml)
        Diag.i("QrcCache", "写入 $mid 行数=${lines.size}")
    }
}
