package com.portable.qqmusic.core

import android.content.Context
import android.os.Build
import com.portable.qqmusic.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动诊断记录器。
 *
 * 背景: 部分国产 ROM (如 Flyme) 会通过 `persist.sys.log-main.enable=0` 关闭
 * logcat 缓冲区, 导致 `adb logcat` 一行都读不到, 无法定位启动异常。
 * 这里把关键步骤与未捕获异常直接写入应用私有目录下的文件,
 * 无需 root、无需重启 logd, 用 `adb pull` 即可取出。
 *
 * 文件位置:
 *   /sdcard/Android/data/<包名>/files/diag/boot.log        (外部, 便于取)
 *   /data/data/<包名>/files/diag/boot.log                  (内部兜底)
 */
object Diag {

    private const val DIR = "diag"
    private const val FILE = "boot.log"
    private const val MAX_BYTES = 2 * 1024 * 1024L

    private val started = AtomicBoolean(false)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    /** 日志写入目标: 内部私有目录 + (若可用) 外部私有目录 */
    private val sinks = ArrayList<File>(2)

    val path: String get() = logFile?.absolutePath ?: "(未初始化)"

    /** 在 Application.onCreate 最开始调用 */
    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        // 优先内部目录: 一定可写; 外部目录仅作为「便于用户查看」的副本
        val candidates = ArrayList<File>(2)
        try {
            candidates.add(File(File(app.filesDir, DIR), FILE))
        } catch (_: Throwable) {
        }
        try {
            app.getExternalFilesDir(null)?.let { candidates.add(File(File(it, DIR), FILE)) }
        } catch (_: Throwable) {
        }

        val header = "=== 轻音启动诊断 ===\n" +
            "time    : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n" +
            "device  : ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "app     : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "=========================\n"

        // 同时写内部与外部: 内部保证一定能读到, 外部便于用户用文件管理器查看
        for (f in candidates) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(header)
                sinks.add(f)
            } catch (_: Throwable) {
            }
        }
        logFile = sinks.firstOrNull()

        installCrashHandler()
        i("Diag", "init done, sinks=${sinks.size}, log=${path}")
    }

    fun i(tag: String, msg: String) = append("I", tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        append("E", tag, msg)
        if (t != null) append("E", tag, throwableToString(t))
    }

    @Synchronized
    private fun append(level: String, tag: String, msg: String) {
        if (sinks.isEmpty()) return
        val line = "[${stamp.format(Date())}] $level/$tag: $msg\n"
        for (f in sinks) {
            try {
                if (f.length() > MAX_BYTES) f.writeText("(日志超限, 已截断)\n")
                f.appendText(line)
            } catch (_: Throwable) {
            }
        }
    }

    fun throwableToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            append("F", "Uncaught", "线程 ${thread.name} 未捕获异常")
            append("F", "Uncaught", throwableToString(throwable))
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 主线程卡顿检测: 延迟 [delayMs] 后若未回调, 说明主线程被阻塞。
     * 对定位「启动窗口一直显示、界面画不出来」特别有用。
     */
    fun watchMainThread(delayMs: Long = 3000) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val marker = Any()
        var fired = false
        handler.postAtTime({
            fired = true
            i("Watchdog", "主线程正常, 延迟 ${delayMs}ms 已执行")
        }, marker, android.os.SystemClock.uptimeMillis() + delayMs)

        // 比看门狗更晚一点检查
        handler.postDelayed({
            if (!fired) {
                e("Watchdog", "主线程被阻塞超过 ${delayMs}ms (任务未执行)")
            }
        }, delayMs + 500)
    }
}
