package com.portable.qqmusic.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.portable.qqmusic.R
import com.portable.qqmusic.data.api.PlayUrlApi
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.ui.util.ImageLoader

/**
 * 推荐页的歌曲行。
 *
 * 用自定义 View 而不是 RecyclerView 嵌套在 ScrollView 里:
 * 推荐页每块内容最多 6 行, 直接 addView 省掉嵌套滚动的测量与回收开销,
 * 也避免了 nestedScroll 的滑动冲突。
 */
class SongRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val cover: ImageView
    private val title: TextView
    private val subtitle: TextView
    private val duration: TextView
    private val tag: TextView
    private val playButton: ImageButton

    var song: Song? = null
        private set

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.item_recommend_song, this, true)
        cover = findViewById(R.id.card_cover)
        title = findViewById(R.id.card_title)
        subtitle = findViewById(R.id.card_subtitle)
        duration = findViewById(R.id.card_duration)
        tag = findViewById(R.id.card_tag)
        playButton = findViewById(R.id.card_play)
    }

    fun bind(
        item: Song,
        position: Int,
        onClick: (Song, Int) -> Unit,
        onPlay: (Song, Int) -> Unit
    ) {
        song = item
        title.text = item.name
        subtitle.text = if (item.albumName.isEmpty()) item.singer else "${item.singer} - ${item.albumName}"
        duration.text = PlayUrlApi.durationText(item.durationSec)
        ImageLoader.loadSong(context, item, cover)

        if (item.type == Song.TYPE_LOCAL) {
            tag.visibility = VISIBLE
            tag.text = "本地"
        } else if (item.vipOnly) {
            tag.visibility = VISIBLE
            tag.text = "VIP"
        } else {
            tag.visibility = GONE
        }

        setOnClickListener { onClick(item, position) }
        playButton.setOnClickListener { onPlay(item, position) }
    }

    fun setPlaying(playing: Boolean) {
        title.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                context,
                if (playing) R.color.brand else R.color.text_primary
            )
        )
    }

    override fun onDetachedFromWindow() {
        ImageLoader.clear(cover)
        super.onDetachedFromWindow()
    }
}
