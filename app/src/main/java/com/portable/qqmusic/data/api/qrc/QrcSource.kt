package com.portable.qqmusic.data.api.qrc

import android.content.Context
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.data.api.MusicU
import com.portable.qqmusic.data.api.Qrc
import com.portable.qqmusic.data.api.QrcDecrypt
import com.portable.qqmusic.data.api.QrcCache
import org.json.JSONObject

/**
 * 逐字歌词（QRC）的唯一入口 —— 一层薄薄的防腐层。
 *
 * 上层（`LyricPrefetcher` / `NowPlayingActivity`）只认 [Qrc.Line] 这一种数据形状，
 * 不关心它背后是哪个 module、用的什么加密、响应长什么样。只要服务端换了实现，
 * 改动只会落在这个文件 [request] 里，别处一行都不用动。
 *
 * 为什么要单独一层而不是塞在 `Api.lyric` 里：
 * 1. QRC 要带 `crypt=1` 再发一次请求，而那条路上 `lyric` / `trans` 都会变成密文，
 *    和普通歌词混在同一个函数里非常容易互相污染（已经踩过一次：加了 `qrc=1` 之后
 *    整个 LRC 变成二进制乱码，界面全是「暂无歌词」）。
 * 2. 解密方式是「定制版 3DES」，服务端一旦调整，只要改 [request] 的解码调用。
 */
object QrcSource {

    private const val MODULE = "music.musichallSong.PlayLyricInfo"
    private const val METHOD = "GetPlayLyricInfo"

    /** 只查本地（内存 + 磁盘），不发请求 */
    fun cached(context: Context, mid: String): List<Qrc.Line> =
        if (mid.isEmpty()) emptyList() else QrcCache.get(context, mid)

    /**
     * 拿逐字行：先看缓存，没有再发一次 `crypt=1` 的请求。
     *
     * 会抛异常吗？不会 —— 网络/解密/解析的失败都吞在这里，返回空表表示「这首歌没有逐字」，
     * 调用方据此退回 LRC 就够了。
     */
    fun fetch(context: Context, mid: String): List<Qrc.Line> {
        if (mid.isEmpty()) return emptyList()
        cached(context, mid).takeIf { it.isNotEmpty() }?.let { return it }
        val plain = request(mid)
        if (plain.isEmpty()) return emptyList()
        QrcCache.put(mid, plain)
        return cached(context, mid)
    }

    /**
     * 发请求并解出明文。
     *
     * 这里就是所有「和 QRC 有关的知识」的唯一归属：
     * - 参数要 `crypt=1, qrc=1, qrc_t=0`
     * - 密文可能在 `qrc` 字段，也可能在 `lyric` 字段（有的歌是前者、有的歌是后者）
     * - 解密走 [QrcDecrypt]（定制版 3DES + zlib）
     */
    private fun request(mid: String): String {
        return try {
            val param = JSONObject()
                .put("songmid", mid)
                .put("songtype", 0)
                .put("format", "json")
                .put("nobase64", 1)
                .put("crypt", 1)
                .put("qrc", 1)
                .put("qrc_t", 0)
                .put("trans", 1)
                .put("trans_t", 0)
            val resp = MusicU.call(MODULE, METHOD, param)
            val data = resp.mod(MusicU.DEFAULT_KEY).data ?: return ""
            val fromQrc = data.optString("qrc", "")
            val fromLyric = data.optString("lyric", "")
            Diag.i(
                "QrcSource",
                "请求 mid=$mid qrc位数=${fromQrc.length} lyric位数=${fromLyric.length} " +
                    "qrc头=${fromQrc.take(32)} lyric头=${fromLyric.take(32)}"
            )
            var plain = if (fromQrc.length > 8) QrcDecrypt.decrypt(fromQrc).orEmpty() else ""
            if (plain.isEmpty() && fromLyric.length > 8) {
                plain = QrcDecrypt.decrypt(fromLyric).orEmpty()
            }
            plain
        } catch (t: Throwable) {
            Diag.e("QrcSource", "请求失败 mid=$mid ${t.message}")
            ""
        }
    }
}
