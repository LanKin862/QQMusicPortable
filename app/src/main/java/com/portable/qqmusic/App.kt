package com.portable.qqmusic

import android.app.Application
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Store
import com.portable.qqmusic.data.api.Login
import com.portable.qqmusic.player.PlayerController

/**
 * 应用入口。
 *
 * 启动路径上只做三件事: 打开配置、恢复登录态、恢复播放器队列,
 * 不启动任何后台服务, 保证冷启动速度。
 *
 * 每一步都写入 [Diag], 因为在关闭 logcat 的 ROM (如 Flyme) 上
 * 这是唯一能看到启动过程与异常的手段。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // 诊断必须最先初始化, 否则前面抛的异常记不下来
        Diag.init(this)

        // QRC 解密结果要落盘, 但 Api.lyric 拿不到 Context, 这里存一份应用级的
        com.portable.qqmusic.data.api.QrcCache.init(this)
        Diag.i("App", "=== Application.onCreate 开始 ===")

        try {
            instance = this
            Log.enabled = BuildConfig.DEBUG

            Diag.i("App", "Store.init ...")
            Store.init(this)
            Diag.i("App", "Store.init ok")

            Diag.i("App", "Login.init ...")
            Login.init()
            Diag.i("App", "Login.init ok, loggedIn=${Login.isLoggedIn()}")

            Diag.i("App", "PlayerController.restoreState ...")
            PlayerController.restoreState(this)
            Diag.i("App", "restoreState ok, queue=${PlayerController.queueSnapshot().size}")

            // 下载元信息 (真实时长/封面/原曲 mid) 的懒加载需要 application context
            com.portable.qqmusic.data.DownloadManager.init(this)

            // 旧版本的音频缓存放在 cacheDir/cache 下, 现在统一挪到 filesDir/cache/{audio,image,meta}。
            // 老目录再也不会被读到, 留着只是白占空间, 这里一次性删掉。
            cleanupLegacyCache()

            // 已登录但没有昵称时, 后台补一次资料 (登录态可能是导入 Cookie 恢复的)
            if (Login.isLoggedIn() && Login.nick().isEmpty()) {
                com.portable.qqmusic.core.Task.io {
                    try {
                        Login.refreshProfile()
                        Diag.i("App", "后台补资料完成 nick=${Login.nick()}")
                    } catch (t: Throwable) {
                        Diag.e("App", "后台补资料失败", t)
                    }
                }
            }

            Diag.i("App", "=== Application.onCreate 完成 ===")
        } catch (t: Throwable) {
            Diag.e("App", "Application.onCreate 抛异常", t)
            throw t
        }
    }

    /**
     * 清理旧版音频缓存目录 (cacheDir/cache)。
     *
     * 新版缓存全部在 filesDir/cache/{audio,image,meta} 下, 老目录既读不到也不会被
     * CacheManager 统计, 属于纯粹的空间浪费。
     */
    private fun cleanupLegacyCache() {
        com.portable.qqmusic.core.Task.io {
            try {
                val legacy = java.io.File(cacheDir, "cache")
                if (!legacy.exists()) return@io
                var freed = 0L
                legacy.walkBottomUp().forEach { f ->
                    val len = if (f.isFile) f.length() else 0L
                    if (f.delete()) freed += len
                }
                Diag.i("App", "清理旧缓存目录 释放=${freed / 1024 / 1024}MB")
            } catch (t: Throwable) {
                Log.w("App.cleanupLegacyCache", t)
            }
        }
    }

    companion object {
        @Volatile
        lateinit var instance: App
            private set
    }
}
