package com.portable.qqmusic.core

import android.util.Log as ALog

/** 统一日志开关: release 版本自动静默, 减少字符串拼接开销 */
object Log {
    var enabled: Boolean = false

    fun d(tag: String, msg: String) {
        if (enabled) ALog.d(tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (enabled) ALog.w(tag, msg)
    }

    fun w(tag: String, t: Throwable) {
        if (enabled) ALog.w(tag, t.message, t)
    }

    fun e(tag: String, msg: String) {
        if (enabled) ALog.e(tag, msg)
    }
}
