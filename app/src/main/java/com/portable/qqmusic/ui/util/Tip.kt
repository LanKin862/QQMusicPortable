package com.portable.qqmusic.ui.util

import android.content.Context
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar

/** 轻提示 */
object Tip {

    private var lastToast: Toast? = null

    fun toast(context: Context, msg: String) {
        try {
            lastToast?.cancel()
            lastToast = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).also { it.show() }
        } catch (t: Throwable) {
        }
    }

    fun snack(view: View, msg: String) {
        Snackbar.make(view, msg, Snackbar.LENGTH_SHORT).show()
    }
}
