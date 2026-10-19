package com.portable.qqmusic.player

import android.media.MediaDataSource
import com.portable.qqmusic.core.Diag
import java.io.File
import java.io.RandomAccessFile

/**
 * 边下边播的数据源: 包装一个"正在增长"的缓存文件。
 *
 * 为什么不把 URL 直接交给播放器:
 * 本机 ROM (Flyme / Android 7.0) 的 MediaPlayer 走平台 MediaHTTPConnection 会在 140ms 内
 * 回调 onError(what=-38); 换成自建 [HttpMediaDataSource] (readAt 里同步做网络 IO) 也一样 ——
 * 设备到 CDN 的 RTT 有 600ms 上下, native 解码线程等不到数据就报错。
 *
 * 这里把网络 IO 挪到下载线程: 下载线程不停地往 .part 文件里追加,
 * [readAt] 只读本地文件 —— 数据还没下到那个位置就短暂等待, 下到了立刻返回,
 * 播放器接着解码。这样既不用等整首歌下完, 也不会让解码线程碰网络。
 */
internal class GrowingFileDataSource(
    private val file: File,
    /** 真实总长 (调用方在 IO 线程用 Range 探测得到), 播放器据此计算时长与可 seek 范围 */
    private val totalSize: Long,
    /** 下载是否已作废 (切歌/失败), 用来从等待里跳出来 */
    private val isAborted: () -> Boolean
) : MediaDataSource() {

    private var raf: RandomAccessFile? = null
    private var loggedWaits = 0

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L) return -1
        if (position >= totalSize) return -1

        val want = minOf(size.toLong(), totalSize - position).toInt()
        if (want <= 0) return -1

        var waitedMs = 0L
        while (true) {
            val available = availableAt(position)
            if (available > 0) {
                val n = readFromFile(position, buffer, offset, minOf(want.toLong(), available).toInt())
                if (n > 0) return n
            }
            if (isAborted()) {
                Diag.i(TAG, "readAt pos=$position 放弃(下载已作废)")
                return -1
            }
            if (waitedMs >= MAX_WAIT_MS) {
                Diag.i(TAG, "readAt pos=$position 等待超时(${waitedMs}ms), 已下 ${fileLength()}B")
                return -1
            }
            if (loggedWaits < MAX_LOGS) {
                loggedWaits++
                Diag.i(
                    TAG,
                    "readAt pos=$position 缓冲不足, 等待中(已下 ${fileLength()}B / $totalSize)"
                )
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            waitedMs += POLL_MS
        }
    }

    /** 当前位置往后已经落盘了多少字节; 文件不存在返回 0 */
    private fun availableAt(position: Long): Long = fileLength() - position

    private fun fileLength(): Long = try {
        if (file.exists()) file.length() else 0L
    } catch (t: Throwable) {
        0L
    }

    private fun readFromFile(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        val r = raf ?: openFile() ?: return -1
        return try {
            r.seek(position)
            r.read(buffer, offset, len)
        } catch (t: Throwable) {
            Diag.i(TAG, "readAt pos=$position 读文件失败: ${t.javaClass.simpleName} ${t.message}")
            -1
        }
    }

    private fun openFile(): RandomAccessFile? = try {
        // "rw" 而不是 "r": 下载线程可能还没来得及创建文件, 这里顺手建出来,
        // 否则第一帧就 FileNotFoundException, 播放器立刻报错。
        RandomAccessFile(file, "rw").also { raf = it }
    } catch (t: Throwable) {
        Diag.i(TAG, "打开 ${file.name} 失败: ${t.javaClass.simpleName} ${t.message}")
        null
    }

    @Synchronized
    override fun getSize(): Long = totalSize

    @Synchronized
    override fun close() {
        try {
            raf?.close()
        } catch (_: Throwable) {
        }
        raf = null
        Diag.i(TAG, "close ${file.name}")
    }

    companion object {
        private const val TAG = "GrowingFileDataSource"

        /** 轮询间隔: 太小会空转, 太大会让起播变钝 */
        private const val POLL_MS = 40L

        /**
         * 单次 readAt 最多等多久。
         * 下载线程一直在写, 正常情况下几百毫秒就有数据; 超过这个时间说明链路出了问题,
         * 让播放器报错走 onError 分支比一直挂着更好。
         */
        private const val MAX_WAIT_MS = 45_000L

        private const val MAX_LOGS = 8
    }
}
