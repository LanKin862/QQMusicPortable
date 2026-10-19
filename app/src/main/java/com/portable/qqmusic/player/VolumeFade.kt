package com.portable.qqmusic.player

import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.portable.qqmusic.core.Log

/**
 * 淡入淡出过渡。
 *
 * API 26+ 优先使用 VolumeShaper (系统级音量曲线, 音频线程内完成, 几乎零 CPU 占用);
 * 低版本回退到主线程 Handler 上的音量爬坡 (400ms / 8 步, 开销可忽略)。
 */
class VolumeFade(
    private val player: MediaPlayer?,
    private val targetGain: Float,
    private val durationMs: Long = 400L,
    private val steps: Int = 8
) {

    @Volatile
    private var cancelled = false

    /** MediaPlayer 没有音量 getter, 这里自行跟踪当前音量 */
    @Volatile
    private var currentVolume: Float = 1.0f

    private val handler = Handler(Looper.getMainLooper())

    fun fadeIn() {
        if (player == null) return
        if (Build.VERSION.SDK_INT >= 26 && applyVolumeShaper(targetGain)) {
            currentVolume = targetGain
            return
        }
        rampTo(targetGain, null)
    }

    fun fadeOut(onEnd: () -> Unit = {}) {
        if (player == null) return
        if (Build.VERSION.SDK_INT >= 26 && applyVolumeShaper(0f)) {
            currentVolume = 0f
            handler.postDelayed({ if (!cancelled) onEnd() }, durationMs)
            return
        }
        rampTo(0f, onEnd)
    }

    fun cancel() {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
    }

    /** 使用系统音量曲线; 失败返回 false 以便回退 */
    private fun applyVolumeShaper(endVolume: Float): Boolean {
        val p = player ?: return false
        return try {
            val startVolume = currentVolume
            val builder = android.media.VolumeShaper.Configuration.Builder()
                .setDuration(durationMs)
                .setCurve(floatArrayOf(0f, 1f), floatArrayOf(startVolume, endVolume))
                .setInterpolatorType(android.media.VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                .build()
            p.createVolumeShaper(builder).apply(android.media.VolumeShaper.Operation.PLAY)
            true
        } catch (t: Throwable) {
            Log.d("VolumeFade", "VolumeShaper unavailable: ${t.message}")
            false
        }
    }

    /** 低版本回退: 主线程音量爬坡 */
    private fun rampTo(target: Float, onEnd: (() -> Unit)?) {
        val p = player ?: return
        val start = currentVolume
        val stepTime = durationMs / steps
        for (i in 1..steps) {
            handler.postDelayed({
                if (cancelled) return@postDelayed
                val fraction = i.toFloat() / steps
                val v = start + (target - start) * fraction
                try {
                    p.setVolume(v, v)
                    currentVolume = v
                } catch (t: Throwable) {
                }
                if (i == steps) onEnd?.invoke()
            }, stepTime * i)
        }
    }
}
