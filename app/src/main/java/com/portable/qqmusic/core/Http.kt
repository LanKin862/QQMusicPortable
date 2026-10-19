package com.portable.qqmusic.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 一次 HTTP 调用的结果 */
class NetResponse(
    val code: Int,
    val body: String,
    val setCookies: List<String>,
    val finalUrl: String,
    /** 3xx 响应的 Location 头, 手动跟随重定向时使用 */
    val location: String? = null
) {
    val isOk: Boolean get() = code in 200..299
}

/**
 * 基于 HttpURLConnection 的网络层。
 *
 * 选型理由: 相比 OkHttp 可省掉约 800KB 依赖与额外的线程/连接池对象,
 * 且本项目请求形态简单 (GET/POST + cookie), 平台实现完全够用。
 * 连接池与 gzip 复用由 JVM 的 KeepAliveCache 承担。
 */
object Http {

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000

    /** 全局 Cookie 池 (QQ 音乐登录态全靠 cookie) */
    @Volatile
    private var cookieHeader: String = ""

    fun setCookies(cookie: String) {
        cookieHeader = cookie
    }

    fun cookies(): String = cookieHeader

    /**
     * 探测远程音频文件的总字节数。
     *
     * MediaPlayer.setDataSource(MediaDataSource) 会在 prepareAsync 之前同步调 getSize(),
     * 那一刻若拿不到真实长度, 部分 ROM 的 NuPlayer 会判定媒体不可用并回调 onError(-38)。
     * 而 AudioEngine.play() 是在主线程被调用的, MediaDataSource 的构造函数里不能发网络
     * (会抛 NetworkOnMainThreadException), 所以长度必须提前在这里 —— 一个 IO 线程上 —— 问好。
     *
     * @return 文件总长; 探测失败返回 -1
     */
    fun contentLength(url: String): Long {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Range", "bytes=0-0")
                conn.connect()
                var len = -1L
                val cr = conn.getHeaderField("Content-Range")
                if (cr != null && cr.contains('/')) {
                    len = cr.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                }
                if (len <= 0L && conn.responseCode in 200..299) {
                    len = conn.contentLengthLong
                }
                Diag.i("Http", "contentLength ${hostOf(url)} -> $len")
                len
            } finally {
                try {
                    conn.disconnect()
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Diag.i("Http", "contentLength 失败: ${t.javaClass.simpleName} ${t.message}")
            -1L
        }
    }

    fun get(url: String, extraHeaders: Map<String, String>? = null, referer: String? = null): NetResponse =
        request("GET", url, null, extraHeaders, referer, null)

    /**
     * 不自动跟随重定向的 GET。
     *
     * 登录流程必须用它: `HttpURLConnection` 跟随 302 时只保留**最终**响应的
     * Set-Cookie, 中间跳转下发的 p_skey / uin 等票据会全部丢失,
     * 导致后续 QQLogin 换不到登录态 (本次登录卡住的根因之一)。
     */
    fun getNoRedirect(url: String, extraHeaders: Map<String, String>? = null, referer: String? = null): NetResponse =
        request("GET", url, null, extraHeaders, referer, null, followRedirects = false)

    /**
     * POST JSON。
     *
     * [readTimeoutMs] 可单独放大: 歌词翻译要等模型把整篇生成完, 默认的 15 秒
     * 在长歌词上会稳定超时 —— 超时后重试等于白烧 token, 所以宁可等久一点。
     */
    fun postJson(
        url: String,
        json: String,
        extraHeaders: Map<String, String>? = null,
        referer: String? = null,
        readTimeoutMs: Int = READ_TIMEOUT,
        withCookies: Boolean = true
    ): NetResponse =
        request(
            "POST", url, json, extraHeaders, referer, "application/json",
            readTimeoutMs = readTimeoutMs, withCookies = withCookies
        )

    fun postForm(url: String, form: String, extraHeaders: Map<String, String>? = null, referer: String? = null): NetResponse =
        request("POST", url, form, extraHeaders, referer, "application/x-www-form-urlencoded")

    /** 表单 POST 且不跟随重定向: 登录换 code 时需要从 302 的 Location 里取授权码 */
    fun postFormNoRedirect(url: String, form: String, extraHeaders: Map<String, String>? = null, referer: String? = null): NetResponse =
        request("POST", url, form, extraHeaders, referer, "application/x-www-form-urlencoded", followRedirects = false)

    private fun request(
        method: String,
        url: String,
        body: String?,
        extraHeaders: Map<String, String>?,
        referer: String?,
        contentType: String?,
        followRedirects: Boolean = true,
        readTimeoutMs: Int = READ_TIMEOUT,
        withCookies: Boolean = true
    ): NetResponse {
        var conn: HttpURLConnection? = null
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = readTimeoutMs
                instanceFollowRedirects = followRedirects
                useCaches = false
                setRequestProperty("User-Agent", Config.USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Referer", referer ?: Config.REFERER)
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                // 第三方请求必须跳过: 不能把 QQ 音乐的登录态发给别人的服务器
                if (withCookies && cookieHeader.isNotEmpty()) setRequestProperty("Cookie", cookieHeader)
                extraHeaders?.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) doOutput = true
            }

            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream: InputStream? = try {
                if (code in 200..299) conn.inputStream else conn.errorStream
            } catch (t: Throwable) {
                null
            }
            val text = stream?.let { readAll(it, conn.contentEncoding) } ?: ""

            val setCookies = conn.headerFields?.get("Set-Cookie") ?: emptyList()
            val location = conn.getHeaderField("Location")
            val elapsed = android.os.SystemClock.elapsedRealtime() - started

            // 诊断降噪: 正常请求不再逐条记录, 只留失败与超慢请求,
            // 避免长时间使用后诊断文件无限膨胀 (Flyme 等 ROM 关了 logcat,
            // 出问题时仍能从文件里看到异常请求)。
            if (code !in 200..299 || elapsed > 8000) {
                Diag.i(
                    "Http",
                    "$method ${hostOf(url)} -> $code, ${text.length}B, " +
                        "发送cookie=${cookieHeader.length}字, loc=${location?.take(50) ?: "-"}, ${elapsed}ms"
                )
            }

            // 手动跟随重定向的场景: 立即合并本次响应的 cookie, 供下一跳使用
            if (!followRedirects && setCookies.isNotEmpty()) mergeCookies(setCookies)

            return NetResponse(code, text, setCookies, conn.url?.toString() ?: url, location)
        } catch (t: Throwable) {
            Diag.e("Http", "$method ${hostOf(url)} 请求失败: ${t.javaClass.simpleName}: ${t.message}")
            if (t is android.os.NetworkOnMainThreadException) {
                // 主线程发网络请求: 把调用栈写出来, 才能定位是哪条路径漏了 Task.io
                Diag.e("Http", "主线程网络调用栈: " + t.stackTrace.take(12).joinToString(" <- ") {
                    "${it.fileName}:${it.lineNumber}"
                })
            }
            throw t
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 只保留主机名与路径, 避免把 query 里的票据写进日志 */
    private fun hostOf(url: String): String = try {
        val u = URL(url)
        u.host + u.path
    } catch (t: Throwable) {
        url.take(60)
    }

    private fun readAll(input: InputStream, encoding: String?): String {
        val source: InputStream =
            if (!encoding.isNullOrEmpty() && encoding.contains("gzip", true)) {
                try {
                    GZIPInputStream(input)
                } catch (t: Throwable) {
                    input
                }
            } else input

        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream(16 * 1024)
        source.use { s ->
            while (true) {
                val n = s.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    /**
     * 合并响应中的 Set-Cookie 到全局 cookie 池。
     * 只覆盖同名键, 保留其余键 —— QQ 音乐的登录态分散在多个 cookie 中。
     */
    @Synchronized
    fun mergeCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val map = LinkedHashMap<String, String>()
        parseCookieString(cookieHeader) { k, v -> map[k] = v }
        var rawLogged = 0
        var keyLogged = 0

        for (raw in setCookies) {
            if (rawLogged < 3) {
                rawLogged++
                Diag.i("Http.cookie", "Set-Cookie 长度=${raw.length} 前140=[${raw.take(140)}]")
            }
            for (piece in raw.split(Regex(",(?=\\s*[A-Za-z0-9_\\-]+=)"))) {
                val first = piece.split(';').firstOrNull()?.trim() ?: continue
                val idx = first.indexOf('=')
                if (idx <= 0) continue
                val k = first.substring(0, idx).trim()
                val v = first.substring(idx + 1).trim()

                /*
                 * 关键: 登录会下发**同名但不同域**的 cookie。
                 *
                 * 实测服务端在 check_sig 这一跳同时返回:
                 *   p_skey=<44字有效票据>; Domain=graph.qq.com
                 *   p_skey=; Expires=Thu, 01 Jan 1970 ...; Domain=qq.com   ← 删除标记
                 *
                 * 本 cookie 池不区分域, 后到的空值会把有效票据覆盖掉, 导致
                 * 「扫码成功却一直登录不上」。因此对「空值 + 1970 过期」的删除标记:
                 *   - 已有同名非空值 -> 保留非空值
                 *   - 否则按空值记录 (与原有行为一致, 避免误留脏值)
                 * 另外: 值为空时不覆盖已有的非空值。
                 */
                val isDeleteMarker = v.isEmpty() && piece.contains("1970")
                val existing = map[k]
                if (isDeleteMarker && !existing.isNullOrEmpty()) {
                    if (k == "p_skey" || k == "skey") {
                        Diag.i("Http.cookie", "$k 删除标记被忽略, 保留长度=${existing.length} 的有效值")
                    }
                    continue
                }
                if (v.isEmpty() && !existing.isNullOrEmpty()) {
                    if (k == "p_skey" || k == "skey") {
                        Diag.i("Http.cookie", "$k 空值被忽略, 保留长度=${existing.length} 的有效值")
                    }
                    continue
                }
                if ((k == "p_skey" || k == "skey" || k == "qm_keyst") && keyLogged < 4) {
                    keyLogged++
                    Diag.i("Http.cookie", "$k 记录长度=${v.length} 值前12=[${v.take(12)}]")
                }
                map[k] = v
            }
        }
        cookieHeader = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private inline fun parseCookieString(cookie: String, action: (String, String) -> Unit) {
        if (cookie.isEmpty()) return
        for (part in cookie.split(';')) {
            val p = part.trim()
            val idx = p.indexOf('=')
            if (idx <= 0) continue
            action(p.substring(0, idx), p.substring(idx + 1))
        }
    }
}
