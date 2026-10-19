package com.portable.qqmusic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.portable.qqmusic.R
import com.portable.qqmusic.data.api.Song

/**
 * 播放通知 (MediaStyle)。
 *
 * 使用固定通知 id + 复用 Builder, 避免每次刷新都重建 RemoteViews。
 */
object PlayerNotification {

    const val CHANNEL_ID = "qqm_playback"
    const val NOTIFICATION_ID = 1001

    private var channelReady = false

    fun ensureChannel(context: Context) {
        if (channelReady || Build.VERSION.SDK_INT < 26) {
            channelReady = true
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_playback_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
        channelReady = true
    }

    fun build(
        context: Context,
        song: Song?,
        isPlaying: Boolean,
        hasNext: Boolean,
        hasPrev: Boolean,
        cover: Bitmap?,
        token: android.support.v4.media.session.MediaSessionCompat.Token?
    ): Notification {
        ensureChannel(context)

        val openIntent = Intent(context, com.portable.qqmusic.ui.NowPlayingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            pendingFlags()
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(song?.name ?: context.getString(R.string.app_name))
            .setContentText(song?.singer ?: "")
            .setSubText(song?.albumName?.takeIf { it.isNotEmpty() })
            .setContentIntent(contentIntent)
            .setDeleteIntent(action(context, PlayerService.ACTION_STOP, 9))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_skip_previous,
                context.getString(R.string.action_prev),
                action(context, PlayerService.ACTION_PREV, 1)
            )
            .addAction(
                if (isPlaying) R.drawable.ic_pause_solid else R.drawable.ic_play_solid,
                context.getString(if (isPlaying) R.string.action_pause else R.string.action_play),
                action(context, PlayerService.ACTION_TOGGLE, 2)
            )
            .addAction(
                R.drawable.ic_skip_next,
                context.getString(R.string.action_next),
                action(context, PlayerService.ACTION_NEXT, 3)
            )

        if (cover != null) {
            builder.setLargeIcon(cover)
        }

        val style = MediaStyle().setShowActionsInCompactView(
            *(if (hasPrev) intArrayOf(0, 1, 2) else intArrayOf(1, 2))
        )
        if (token != null) style.setMediaSession(token)
        builder.setStyle(style)

        return builder.build()
    }

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlayerService::class.java).setAction(action)
        return PendingIntent.getService(context, requestCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
}
