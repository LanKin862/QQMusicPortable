package com.portable.qqmusic.core

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File

/**
 * 当前音频输出通路。
 *
 * 这里只说 Android 上真实发生的事，不做美化：
 *
 * - 内置扬声器 / 有线耳机 / 蓝牙 / USB 都走系统混音器（AudioFlinger）。
 * - 混音器按设备偏好采样率重采样，这就是所谓的 **SRC**（Sample Rate Conversion）。
 * - 只有当「源文件采样率 == 设备输出采样率」时，这一档重采样在数值上是恒等变换，
 *   听感上没有额外损失 —— 我们把它标成「直通」。
 *
 * 真正意义上的 bit-perfect（绕过混音器独占设备）在普通应用层拿不到，
 * 所以这里不谎报「DAC 直出」，只把两个采样率摆出来。
 */
object AudioOutput {

    /** 设备类型，决定用哪个图标 */
    enum class Kind { SPEAKER, HEADPHONE, BLUETOOTH, USB, OTHER }

    /**
     * @param label   设备名（「扬声器」「蓝牙」这种）
     * @param outHz   设备输出采样率（AudioManager 报的，设备原生那一档）
     * @param srcHz   当前曲目的源采样率，0 表示没读出来
     * @param kind    图标类型
     */
    data class Info(
        val label: String,
        val outHz: Int,
        val srcHz: Int,
        val kind: Kind
    ) {
        /** 源采样率和输出采样率一致才算直通 */
        val bitPerfect: Boolean get() = srcHz > 0 && outHz > 0 && srcHz == outHz

        /** 「48 kHz」这种显示串；采样率未知时给空串 */
        val hzText: String
            get() {
                val hz = if (outHz > 0) outHz else return ""
                return if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format("%.1f kHz", hz / 1000f)
            }
    }

    /** 查当前输出设备。拿不到就返回 null，界面上直接不显示这一行 */
    fun query(context: Context, sourceFile: File?): Info? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val device = pickDevice(am)
        val outHz = deviceRate(am, device)
        val srcHz = sourceSampleRate(sourceFile)
        val kind = kindOf(device)
        Diag.i(
            "AudioOutput",
            "type=${device?.type} name=${device?.productName} rates=${device?.sampleRates?.joinToString("/")} " +
                "prop=${runCatching { am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }.getOrNull()} " +
                "out=$outHz src=$srcHz file=${sourceFile?.name}"
        )
        return Info(labelOf(kind), outHz, srcHz, kind)
    }

    /**
     * 挑一个「出声音的那个」设备。
     *
     * `getDevices(GET_DEVICES_OUTPUTS)` 会把扬声器、听筒、蓝牙、USB 全列出来，
     * 但真正在响的只有一个 —— 优先蓝牙/USB（用户插了就是想走它），其次耳机，最后扬声器。
     */
    private fun pickDevice(am: AudioManager): AudioDeviceInfo? {
        val outputs = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (t: Throwable) {
            Log.w("AudioOutput.devices", t)
            emptyList()
        }
        if (outputs.isEmpty()) return null
        fun has(vararg types: Int) = outputs.firstOrNull { it.type in types }
        return has(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE
        ) ?: has(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        ) ?: has(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        ) ?: outputs.firstOrNull()
    }

    private fun kindOf(device: AudioDeviceInfo?): Kind = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> Kind.USB
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> Kind.HEADPHONE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Kind.SPEAKER
        null -> Kind.OTHER
        else -> Kind.OTHER
    }

    private fun labelOf(kind: Kind): String = when (kind) {
        Kind.BLUETOOTH -> "蓝牙"
        Kind.USB -> "USB"
        Kind.HEADPHONE -> "耳机"
        Kind.SPEAKER -> "扬声器"
        Kind.OTHER -> "输出"
    }

    /**
     * 设备输出采样率。
     *
     * 这里必须用 `PROPERTY_OUTPUT_SAMPLE_RATE` **优先**：它是 AudioFlinger 此刻在用的
     * 混音采样率，也就是真正写进声音缓冲的数字。设备自己的 `sampleRates` 只是硬件
     * 支持的档位列表，顺序不保证 —— 之前取了 `first()`，在某些机器上第一个恰好是
     * 96000，于是明明在放 48k 的流，徽章也报 96 kHz；而重进页面时设备列表顺序
     * 可能变了，又"自己恢复正确"，看起来就像只有重进才刷新。
     *
     * 拿不到混音率时才退回硬件档位，并且挑最接近 48 kHz 的那档（绝大多数音源与
     * 混音器默认都是 48k），而不是盲取第一个。
     */
    private fun deviceRate(am: AudioManager, device: AudioDeviceInfo?): Int {
        val mixer = try {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            0
        }
        if (mixer > 0) return mixer
        val rates = device?.sampleRates?.filter { it > 0 }.orEmpty()
        return rates.minByOrNull { kotlin.math.abs(it - 48_000) } ?: 0
    }

    /**
     * 读源文件的采样率。
     *
     * `MediaPlayer` 不暴露这个，只能拿 `MediaExtractor` 单独读一次头部 —— 只读元数据，
     * 不解码，一首歌几毫秒。读不出来返回 0（界面就不显示直通/重采样那一档）。
     *
     * 结果按「路径 + 文件长度」缓存：切换播放模式、切歌、重进页面都会重算徽章，
     * 每次都解一遍文件头没有必要，而这些调用都发生在主线程。
     */
    fun sourceSampleRate(file: File?): Int {
        if (file == null || !file.exists() || file.length() <= 0) return 0
        val key = file.absolutePath
        val len = file.length()
        synchronized(srcCache) {
            val hit = srcCache[key]
            if (hit != null && hit.first == len) return hit.second
        }
        val hz = readSampleRate(file)
        synchronized(srcCache) { srcCache[key] = len to hz }
        return hz
    }

    /** 路径 -> (文件长度, 采样率)。文件被边下边播写长之后长度变了会自然重读 */
    private val srcCache = HashMap<String, Pair<Long, Int>>()

    private fun readSampleRate(file: File): Int {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i) ?: continue
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    return fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            }
            0
        } catch (t: Throwable) {
            Log.w("AudioOutput.sampleRate", t)
            0
        } finally {
            runCatching { ex.release() }
        }
    }

    /** 这个设备类型有没有采样率上限提示（蓝牙要编码，别让用户以为真直通） */
    fun isWireless(kind: Kind): Boolean = kind == Kind.BLUETOOTH

    @Suppress("unused")
    private val needsApi23 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}
