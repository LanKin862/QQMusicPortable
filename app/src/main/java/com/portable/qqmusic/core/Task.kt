package com.portable.qqmusic.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 轻量异步工具。
 *
 * 刻意不引入 Kotlin 协程 (省掉 ~1.5MB 依赖 + 类加载开销),
 * 用固定线程池 + 主线程 Handler 完成全部异步工作。
 */
object Task {

    private val main = Handler(Looper.getMainLooper())

    private val io: java.util.concurrent.ExecutorService =
        Executors.newFixedThreadPool(4, object : ThreadFactory {
            private val n = AtomicInteger(1)
            override fun newThread(r: Runnable): Thread =
                Thread(r, "qqm-io-${n.getAndIncrement()}").apply { isDaemon = true }
        })

    /** 单线程串行池, 用于需要顺序执行的任务 (如下载、歌词抓取) */
    private val serial: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "qqm-serial").apply { isDaemon = true }
        }

    fun io(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.w("Task.io", t)
            }
        }
    }

    fun serial(block: () -> Unit) {
        serial.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.w("Task.serial", t)
            }
        }
    }

    fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** 后台执行 [work], 结果回到主线程交给 [done] (主线程回调) */
    fun <T> async(work: () -> T, done: (T) -> Unit) {
        io {
            val r = work()
            main { done(r) }
        }
    }

    /** 后台执行可能抛异常的 [work], 成功/失败分别在主线程回调 */
    fun <T> async(work: () -> T, done: (T) -> Unit, fail: (Throwable) -> Unit) {
        io {
            try {
                val r = work()
                main { done(r) }
            } catch (t: Throwable) {
                main { fail(t) }
            }
        }
    }

    fun cancelAll() {
        try {
            io.shutdownNow()
        } catch (_: Throwable) {
        }
    }
}
