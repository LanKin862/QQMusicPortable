package com.portable.qqmusic.player

import android.media.MediaDataSource
import com.portable.qqmusic.core.Diag
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用自建 HTTP 连接给 MediaPlayer 喂数据。
 *
 * 背景: 本机 ROM (Flyme / Android 7.0) 的 MediaPlayer 直接 setDataSource(http url) 会在
 * 约 140ms 内立刻回调 onError(what=-38) —— 无论 http 还是 https 都一样, 说明走不通平台的
 * MediaHTTPConnection。而同一 URL 用 HttpURLConnection 下载完全正常。
 *
 * 因此这里实现 MediaDataSource: 由 MediaPlayer 按需调 readAt(position, ...) 取数据,
 * 我们同步去 HTTP 连接上读。数据没到时 read() 自身阻塞, 播放器自然处于等待状态;
 * 数据到了继续返回, 播放器接着解码 —— 即「缓冲不足就等, 数据来了再走」。
 *
 * 顺序播放时只有一条连接、顺序读; seek 到别处时用 Range 头重开连接。
 */
internal class HttpMediaDataSource(
    private val url: String,
    /** 调用方在 IO 线程预先探好的文件总长; -1 表示未知 (此时 getSize() 会返回 -1) */
    knownSize: Long = -1L,
    private val headers: Map<String, String> = DEFAULT_HEADERS
) : MediaDataSource() {

    companion object {
        private const val TAG = "HttpMediaDataSource"

        private val DEFAULT_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 7.0; QQMusicPortable) Chrome/98.0.4758.102",
            "Referer" to "https://y.qq.com/"
        )

        /** 读到这个字节数才认为网络稳定, 用于上报缓冲进度 */
        const val PREBUFFER_BYTES = 256 * 1024
    }

    private var conn: HttpURLConnection? = null
    private var input: InputStream? = null
    private var pos = 0L
    private var total = if (knownSize > 0L) knownSize else -1L

    // 注意: 构造函数里绝对不能发网络请求。
    // AudioEngine.play() 是被 PlayerService.load() 在主线程同步调用的, 构造里做 HTTP 会直接抛
    // NetworkOnMainThreadException, total 永远停在 -1。
    // 因此长度由调用方在 IO 线程用 Http.contentLength() 探好传进来;
    // 万一没探到, 也会在第一次 readAt -> open(0) 的 200 响应里补上。

    /** 已从网络取到的字节数 (含预缓冲), 供 UI 显示缓冲进度 */
    @Volatile
    var bufferedBytes: Long = 0L
        private set

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        return try {
            if (input == null || position != pos) {
                open(position)
            }
            val n = input?.read(buffer, offset, size) ?: -1
            if (n > 0) {
                pos += n
                bufferedBytes += n
                if (probeCount < 6) {
                    probeCount++
                    Diag.i(
                        TAG,
                        "readAt pos=$position size=$size -> n=$n 累计=${bufferedBytes}B" +
                            (if (position == 0L) " head=${hexHead(buffer, offset, n)}" else "")
                    )
                }
            } else {
                Diag.i(TAG, "readAt pos=$position size=$size -> EOF(n=$n)")
            }
            n
        } catch (t: Throwable) {
            Diag.i(TAG, "readAt pos=$position 失败: ${t.javaClass.simpleName} ${t.message}")
            -1
        }
    }

    private var probeCount = 0

    private fun hexHead(buf: ByteArray, offset: Int, n: Int): String {
        val sb = StringBuilder()
        val lim = minOf(n, 16)
        for (i in 0 until lim) {
            sb.append(String.format("%02X ", buf[offset + i]))
        }
        return sb.toString().trim()
    }

    @Synchronized
    override fun getSize(): Long = total

    @Synchronized
    override fun close() {
        try {
            input?.close()
        } catch (_: Throwable) {
        }
        try {
            conn?.disconnect()
        } catch (_: Throwable) {
        }
        input = null
        conn = null
        pos = 0L
    }

    private fun open(position: Long) {
        close()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (position > 0L) {
            c.setRequestProperty("Range", "bytes=$position-")
        }
        c.connect()
        val code = c.responseCode
        if (code !in 200..299) {
            throw java.io.IOException("HTTP $code")
        }
        if (position == 0L) {
            // 只有首次 200 响应拿到的 Content-Length 才是文件真实总长。
            // 206 的 Content-Length 是剩余长度, 拿它反推总长会被 gzip/重定向干扰而前后矛盾,
            // 因此 seek 重连时保持已有的 total 不变。
            val len = c.contentLengthLong
            total = if (len > 0L) len else -1L
        }
        // 64KB 缓冲: 一次系统调用多拿一些, 减少 readAt 往返次数
        input = BufferedInputStream(c.inputStream, 64 * 1024)
        conn = c
        pos = position
        Diag.i(TAG, "open pos=$position code=$code total=$total")
    }
}
