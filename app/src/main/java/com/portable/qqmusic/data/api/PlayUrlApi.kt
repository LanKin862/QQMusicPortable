package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Config
import com.portable.qqmusic.core.Http
import com.portable.qqmusic.core.J
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Quality
import org.json.JSONObject

/**
 * 播放地址 (vkey) 获取。
 *
 * 关键点:
 * 1. 请求必须带 `filename` = 前缀 + songmid + songmid + 扩展名, 前缀决定音质档位;
 * 2. 服务端按权限返回 purl, 无权限时 purl 为空, 需要逐级降档重试;
 * 3. 返回的 sip 是域名列表 (可能包含备用域名), 需要与 purl 拼接。
 */
object PlayUrlApi {

    private const val MODULE = "vkey.GetVkeyServer"
    private const val METHOD = "CgiGetVkey"
    private const val GUID = "10000"

    /**
     * 获取播放地址。
     *
     * @param quality 期望音质, 无权限时自动降档到 [Quality.STANDARD]
     * @param forceQuality 下载场景下不允许降档 (避免下载到低于用户预期的音质)
     */
    fun getUrl(song: Song, quality: Quality, forceQuality: Boolean = false): SongUrl? {
        val ladder = if (forceQuality) listOf(quality) else Quality.ladderFrom(quality)
        for (q in ladder) {
            val result = requestOne(song, q)
            if (result != null && result.isPlayable) {
                return result
            }
        }
        // 全部档位都失败, 最后尝试一次标准品质的 m4a
        return requestOne(song, Quality.STANDARD)
    }

    /**
     * 取一个**确实能下载**的直链, 顺带把文件长度探出来。
     *
     * 服务端会给出 purl 非空、result=0 但实际 HTTP 404 的地址 —— 实测《如春 (Run)》
     * (mid=001JOLTf4UlH6q) 的 RS01(Hi-Res) 直链在桌面和手机上都 404, 而同一首歌的
     * M500(标准) 直链是 200 / 3569159 字节。只判断 purl 非空发现不了这种坏链接,
     * 于是这里对每一档做一次 Range 探测, 只有真能读到字节才采用。
     *
     * 调用方必须在 IO 线程 (会发网络请求)。
     *
     * @return 直链与远程文件长度; 所有档位都不可用时返回 null
     */
    /**
     * 每首歌上次真正拿到直链的档位。
     *
     * 逐档探测是"从期望档位一路往下降", 一首歌一次要发好几个请求。同一首歌的可用档位
     * 在一次会话里基本不变 —— 上次在 SQ 成功, 这次直接从 SQ 开始试, 省掉上面几次
     * 必然白跑的往返。vkey 过期导致这档这次失败时, 循环照样往下走, 不会卡死。
     */
    private val qualityHint = java.util.concurrent.ConcurrentHashMap<String, Quality>()

    fun resolvePlayable(song: Song, quality: Quality): Pair<SongUrl, Long>? {
        val ladder = Quality.ladderFrom(quality)
        // 用户把音质调低了(hint 不在新梯形里)就从期望档位开始, 不要越过它往上试
        val start = qualityHint[song.mid]?.let { hint -> ladder.indexOf(hint) }.let { idx ->
            if (idx != null && idx > 0) idx else 0
        }
        if (start > 0) {
            com.portable.qqmusic.core.Diag.i(
                "PlayUrlApi",
                "《${song.name}》 直接从 ${ladder[start].label} 开始试(上次在这一档成功)"
            )
        }
        for (i in start until ladder.size) {
            val q = ladder[i]
            val r = requestOne(song, q) ?: continue
            val size = Http.contentLength(r.url)
            if (size > 0L) {
                qualityHint[song.mid] = q
                return r to size
            }
            com.portable.qqmusic.core.Diag.i(
                "PlayUrlApi",
                "《${song.name}》 ${q.label} 直链不可用, 继续降档 ${r.url.take(70)}"
            )
        }
        return null
    }

    private fun requestOne(song: Song, quality: Quality): SongUrl? {
        val fileName = quality.prefix + song.mid + song.mid + quality.ext
        val param = JSONObject()
            .put("guid", GUID)
            .put("songmid", org.json.JSONArray().put(song.mid))
            .put("songtype", org.json.JSONArray().put(0))
            .put("uin", Login.uin().ifEmpty { "0" })
            .put("loginflag", 1)
            .put("platform", "20")
            .put("filename", org.json.JSONArray().put(fileName))

        val resp = MusicU.call(MODULE, METHOD, param, "vkey")
        val data = resp.data("vkey")
        if (data == null) {
            Log.w("PlayUrl", "no vkey data for ${song.mid} @ ${quality.label}")
            return null
        }

        val midurlinfo = data.optJSONArray("midurlinfo")
        val info = midurlinfo?.optJSONObject(0) ?: return null
        val purl = info.optString("purl", "")
        val errType = info.optInt("result", 0)
        if (purl.isEmpty() || errType != 0) {
            Log.d("PlayUrl", "purl empty for ${song.mid} @ ${quality.label}, result=$errType")
            return null
        }

        val sipList = data.optJSONArray("sip")
        val domain = pickDomain(sipList)
        val url = if (purl.startsWith("http")) purl else domain + purl
        val size = info.optLong("filesize", 0L)
        // 试听片段判定: 返回文件名以 RS02 / 试听标识开头, 或时长明显偏短
        val isTrial = info.optInt("pneed", 0) == 1 && info.optInt("pneedbuy", 0) == 1

        return SongUrl(
            url = url,
            quality = quality,
            // 缓存文件名必须与 PlayerService.urlFileName() 一致(去掉 ?query),
            // 否则写入用带 vkey 的名字、查找用不带的名字, 缓存永远不命中。
            fileName = purl.substringAfterLast('/').substringBefore('?').ifEmpty { fileName },
            fileSize = size,
            isTrial = isTrial
        )
    }

    private fun pickDomain(sip: org.json.JSONArray?): String {
        if (sip != null) {
            for (i in 0 until sip.length()) {
                val s = sip.optString(i, "")
                if (s.isNotEmpty()) return if (s.endsWith("/")) s else "$s/"
            }
        }
        return "https://dl.stream.qqmusic.qq.com/"
    }

    /**
     * 直链下载 (供离线下载使用)。
     * 下载走独立连接, 不回写 cookie, 避免并发写冲突。
     */
    /**
     * 把 [url] 下到 [target]。
     *
     * [shouldStop] 每读一块检查一次, 返回 true 就放弃并删掉 .part ——
     * 播放路径用它取消「已经切走的那首歌」的下载, 否则 4 个 IO 线程会被
     * 上百 MB 的 FLAC 占死, 后续切歌连直链都解析不了。
     */
    fun downloadToFile(
        url: String,
        target: java.io.File,
        onProgress: (Long, Long) -> Unit,
        /** >0 时先把 .part 预分配到该长度再顺序写, 播放器据此能得到正确时长 (边下边播用) */
        preallocate: Long = -1L,
        /**
         * 临时文件后缀。传空串表示直接写 target 本身 —— 调用方自己保证 target
         * 是个临时路径。边下边播必须走这条: 文件名要保留 .mp3/.flac,
         * 加上 .part 后这台机器的 MediaPlayer 会直接拒播 (onError -38)。
         */
        tmpSuffix: String = ".part",
        shouldStop: () -> Boolean = { false }
    ): Boolean {
        var conn: java.net.HttpURLConnection? = null
        // .part 临时文件声明在 try 外面: 下载中途失败 (HTTP 404 / 断流) 时也要能删掉。
        // 否则切歌留下的残骸会一直堆在缓存目录里, 实测堆积过几十 MB 的 .part。
        target.parentFile?.mkdirs()
        val tmp =
            if (tmpSuffix.isEmpty()) target
            else java.io.File(target.parentFile, target.name + tmpSuffix)
        try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", Config.USER_AGENT)
                setRequestProperty("Referer", Config.REFERER)
                setRequestProperty("Cookie", Http.cookies())
            }
            if (conn.responseCode !in 200..299) {
                com.portable.qqmusic.core.Diag.i(
                    "PlayUrlApi",
                    "下载失败 HTTP ${conn.responseCode} ${url.take(80)}"
                )
                tmp.delete()
                return false
            }
            val total = conn.contentLengthLong
            val capacity = if (preallocate > 0L) preallocate else total
            conn.inputStream.use { input ->
                // 用 RandomAccessFile 而不是 FileOutputStream: 后者会把文件截断成 0,
                // 而边下边播时我们要先把 .part 撑到完整长度, 这样播放器打开这个
                // 还没下完的文件也能算出正确时长 (不会把 30 秒当成整首歌)。
                java.io.RandomAccessFile(tmp, "rw").use { out ->
                    if (capacity > 0L && out.length() != capacity) out.setLength(capacity)
                    out.seek(0)
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastNotify = 0L
                    while (true) {
                        if (shouldStop()) {
                            com.portable.qqmusic.core.Diag.i(
                                "PlayUrlApi",
                                "下载被取消(已切歌) 已写 ${done / 1024}KB ${target.name}"
                            )
                            tmp.delete()
                            return false
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastNotify > 128 * 1024) {
                            lastNotify = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                    try {
                        out.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
            }
            if (target.exists()) target.delete()
            val renamed = tmp.renameTo(target)
            com.portable.qqmusic.core.Diag.i(
                "PlayUrlApi",
                "下载完成 renamed=$renamed ${target.name} total=$total " +
                    "落盘=${if (target.exists()) target.length() else -1}B ${url.take(60)}"
            )
            return renamed
        } catch (t: Throwable) {
            com.portable.qqmusic.core.Diag.i(
                "PlayUrlApi",
                "下载异常 ${t.javaClass.simpleName}: ${t.message} ${url.take(80)}"
            )
            Log.w("PlayUrl.download", t)
            tmp.delete()
            return false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 供下载模块判断歌曲是否可下载到指定音质 */
    fun resolveDownloadUrl(song: Song, quality: Quality): SongUrl? =
        getUrl(song, quality, forceQuality = false)

    /** 解析歌曲时长 (秒), 用于计算时长字符串 */
    fun durationText(seconds: Int): String {
        if (seconds <= 0) return "--:--"
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    /** 从 vkey 返回体里取文件大小 (UI 显示用) */
    fun fileSizeText(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.0fKB".format(bytes / 1024.0)
        else -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    }

    /** 解析 JSON 字符串中的字段, 供外部调试 */
    fun peek(text: String, key: String): String = J.str(J.obj(text), key, "")
}
