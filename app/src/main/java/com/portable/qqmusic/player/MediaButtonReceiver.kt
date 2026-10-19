package com.portable.qqmusic.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * 耳机线控 / 蓝牙按键。
 *
 * 仅做转发, 不持有任何状态, 由 [PlayerService] 处理具体动作。
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return

        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerService.ACTION_PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerService.ACTION_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> PlayerService.ACTION_TOGGLE
            KeyEvent.KEYCODE_MEDIA_NEXT -> PlayerService.ACTION_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlayerService.ACTION_PREV
            KeyEvent.KEYCODE_MEDIA_STOP -> PlayerService.ACTION_STOP
            else -> return
        }
        PlayerService.sendCommand(context, action)
    }
}
