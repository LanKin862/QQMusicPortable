package com.portable.qqmusic.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Contrast
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.player.PlayerController
import com.portable.qqmusic.ui.util.ImageLoader

/**
 * 迷你播放条控制器。
 *
 * 挂在 Activity 上, 与 PlayerController 的生命周期绑定;
 * 播放条只在有歌曲时显示, 避免初始状态占用布局空间。
 *
 * 底色由**所在页面**决定, 不是歌曲封面 —— 见 [setBackground]。
 * 浅色页面不调它就是默认的 `@color/mini_player_bg`;
 * 深色页面 (歌手详情) 会把主题色灌进来, 让它和列表背景连成一片。
 *
 * @param dark 所在页面是不是深色底。深色底要把文字和图标换成白色色板。
 */
class MiniPlayerController(
    private val root: View,
    private val dark: Boolean = false,
    private val onOpenPlayer: () -> Unit
) : PlayerController.Listener {

    private val cover: ImageView = root.findViewById(R.id.mini_cover)
    private val title: TextView = root.findViewById(R.id.mini_title)
    private val subtitle: TextView = root.findViewById(R.id.mini_subtitle)
    private val playButton: ImageButton = root.findViewById(R.id.mini_play)
    private val nextButton: ImageButton = root.findViewById(R.id.mini_next)
    /** 会跟着左右划一起位移的那一层; 背景留在 root 上, 免得边缘露底 */
    private val content: View = root.findViewById(R.id.mini_content)

    /** 本次手势是不是已经被判定成横向滑动 (用来吞掉随之而来的点击) */
    private var swiped = false

    private val context: Context get() = root.context

    private companion object {
        /** 浅底上的主文字色, 与歌手详情页那一套保持一致 (Material 的 87% 黑) */
        const val DARK_TEXT = 0xDE000000.toInt()
    }

    init {
        // 深色页面: 文字和图标统一成白色色板, 否则主题里的深色文字会糊在深底上
        if (dark) applyTextTint(Color.WHITE)
        root.setOnClickListener { onOpenPlayer() }
        playButton.setOnClickListener { PlayerController.toggle() }
        nextButton.setOnClickListener { PlayerController.next(context) }
        installSwipe()
        PlayerController.addListener(this)
        // 进入页面时可能已经在放歌 (不会再回调 onSongChanged), 这里主动对齐一次状态
        refreshAvailability()
    }

    // ---------------- 左右划切歌 ----------------

    /**
     * 左划下一首、右划上一首。
     *
     * 手势只监听不消费 (返回 false): 迷你条本身是整块可点的, 消费掉 touch 会让点击失效。
     * 判定要求横向位移和速度都够, 且横向分量明显压过纵向 —— 否则在列表上竖着滑一下
     * 也能把它带出来。
     */
    private fun installSwipe() {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                val adx = kotlin.math.abs(dx)
                val ady = kotlin.math.abs(dy)
                if (adx < dp(40f) || kotlin.math.abs(velocityX) < 500f) return false
                if (adx < ady * 1.5f) return false
                swiped = true
                if (dx < 0f) switchTrack(-1) else switchTrack(1)
                return true
            }
        })
        root.setOnTouchListener { _, event ->
            // 一旦确认是横向滑动就吃掉后续事件: 否则松手时 root 的点击照常触发,
            // 表现成「划一下切了歌, 播放页也被顺带弹开」
            if (event.actionMasked == MotionEvent.ACTION_DOWN) swiped = false
            detector.onTouchEvent(event)
            swiped
        }
    }

    /**
     * 滑出 -> 切歌 -> 从另一侧滑入。
     *
     * @param direction -1 向左划 (下一首), 1 向右划 (上一首)
     */
    private fun switchTrack(direction: Int) {
        val width = root.width
        if (width <= 0) {
            if (direction < 0) PlayerController.next(context) else PlayerController.previous()
            return
        }
        // 走的这一侧: 左划往左滑出, 右划往右滑出
        val out = width * 0.35f * direction
        content.animate().cancel()
        content.animate()
            .translationX(out)
            .alpha(0.35f)
            .setDuration(120L)
            .withEndAction {
                if (direction < 0) PlayerController.next(context) else PlayerController.previous()
                // 新歌从划动方向的另一侧回来 (左划时从右边进)
                content.translationX = -out * 1.15f
                content.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(190L)
                    .start()
            }
            .start()
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    fun release() {
        PlayerController.removeListener(this)
        ImageLoader.clear(cover)
    }

    /**
     * 由所在页面指定底色; 传 null 回到默认的 `@color/mini_player_bg`。
     *
     * 文字和图标跟着底色的明暗走: 底色亮就用深色字、暗就用白字。
     * 歌手页传进来的是已经算好的主题色, 这里再判一次也是同一个结论, 所以
     * 迷你条和列表背景能保持**完全一致**, 不会切出一条深色带。
     */
    fun setBackground(color: Int?) {
        if (color == null) {
            val fallback = if (dark) R.color.bg_player else R.color.mini_player_bg
            root.setBackgroundColor(ContextCompat.getColor(context, fallback))
            applyTextTint(if (dark) Color.WHITE else null)
            return
        }
        // 先把底色修到文字读得清, 再整体朝「远离页面背景」的方向推一档:
        // 顺序不能反, 否则 separate 之后 fitBackground 会把它又拉回背景色附近, 白推
        val readable = Contrast.fitBackground(color, Contrast.preferDarkText(color))
        val surface = Contrast.separate(readable)
        val useDark = Contrast.preferDarkText(surface)
        root.setBackgroundColor(Contrast.fitBackground(surface, useDark))
        applyTextTint(if (useDark) DARK_TEXT else Color.WHITE)
    }

    /** [tint] 为 null 时恢复主题默认色 (浅色页面的那套) */
    private fun applyTextTint(tint: Int?) {
        if (tint == null) {
            title.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            subtitle.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            val normal = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
            ImageViewCompat.setImageTintList(playButton, normal)
            ImageViewCompat.setImageTintList(nextButton, normal)
            return
        }
        title.setTextColor(tint)
        subtitle.setTextColor(ColorUtils.setAlphaComponent(tint, 0xCC))
        val list = ColorStateList.valueOf(tint)
        ImageViewCompat.setImageTintList(playButton, list)
        ImageViewCompat.setImageTintList(nextButton, list)
    }

    /** 根据当前队列刷新按钮可用状态 */
    fun refreshAvailability() {
        val hasQueue = PlayerController.queueSnapshot().isNotEmpty()
        root.visibility = if (hasQueue) View.VISIBLE else View.GONE
        nextButton.isEnabled = PlayerController.hasNext()
        nextButton.alpha = if (PlayerController.hasNext()) 1f else 0.4f
        syncPlayIcon()
        Log.d("MiniPlayer", "visibility=" + root.visibility + " queue=" + PlayerController.queueSnapshot().size)
    }

    private fun syncPlayIcon() {
        playButton.setImageResource(
            if (PlayerController.isPlaying) R.drawable.ic_pause_solid else R.drawable.ic_play_solid
        )
        playButton.contentDescription = context.getString(
            if (PlayerController.isPlaying) R.string.action_pause else R.string.action_play
        )
    }

    // ---------------- PlayerController.Listener ----------------

    override fun onSongChanged(song: Song?) {
        if (song == null) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE
        title.text = song.name
        subtitle.text = if (song.albumName.isEmpty()) song.singer else song.singer + " - " + song.albumName
        ImageLoader.loadSong(context, song, cover)
        syncPlayIcon()
    }

    override fun onPlayStateChanged(playing: Boolean) {
        syncPlayIcon()
        if (PlayerController.queueSnapshot().isNotEmpty()) root.visibility = View.VISIBLE
    }

    override fun onProgress(positionMs: Int, durationMs: Int) {
        // 迷你条不展示进度, 避免不必要的刷新
    }

    override fun onQueueChanged() = refreshAvailability()

    override fun onBuffering(buffering: Boolean) {
    }

    override fun onError(message: String) {
    }
}