package com.portable.qqmusic.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.portable.qqmusic.R
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.ui.util.ImageLoader

/**
 * 歌单适配器, 支持两种布局:
 *  - [MODE_CARD]: 横向滑动卡片 (推荐页)
 *  - [MODE_ROW] : 竖向列表行 (我的页)
 */
class PlaylistAdapter(
    private val mode: Int,
    private val onClick: (Playlist) -> Unit,
    private val onMore: ((View, Playlist) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    companion object {
        const val MODE_CARD = 0
        const val MODE_ROW = 1
    }

    private var items: List<Playlist> = emptyList()

    fun submit(list: List<Playlist>) {
        items = list
        notifyDataSetChanged()
    }

    fun append(list: List<Playlist>) {
        items = items + list
        notifyDataSetChanged()
    }

    fun currentItems(): List<Playlist> = items

    fun isEmpty(): Boolean = items.isEmpty()

    override fun getItemViewType(position: Int): Int = mode

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == MODE_CARD) R.layout.item_playlist_card else R.layout.item_playlist_row
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false), viewType)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(itemView: View, private val viewType: Int) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.item_cover)
        private val title: TextView = itemView.findViewById(R.id.item_title)
        private val subtitle: TextView = itemView.findViewById(R.id.item_subtitle)
        private val badge: TextView? = itemView.findViewById(R.id.item_badge)
        private val more: ImageButton? = itemView.findViewById(R.id.item_more)

        fun bind(playlist: Playlist) {
            title.text = playlist.title
            ImageLoader.loadPlaylist(itemView.context, playlist, cover)

            val ctx = itemView.context
            val countText = if (playlist.songCount > 0) ctx.getString(R.string.song_count_format, playlist.songCount) else ""
            val playText = if (playlist.playCount > 0) formatPlayCount(playlist.playCount) else ""
            subtitle.text = when {
                countText.isNotEmpty() && playText.isNotEmpty() -> "$countText · $playText"
                countText.isNotEmpty() -> countText
                playlist.creator.isNotEmpty() -> playlist.creator
                else -> playText
            }

            if (viewType == MODE_CARD) {
                // 横向卡片: 收藏的歌单用「收藏」角标, 自建用「自建」
                if (badge != null) {
                    if (playlist.isSelfCreated) {
                        badge.visibility = View.VISIBLE
                        badge.text = ctx.getString(R.string.section_created_playlist)
                    } else {
                        badge.visibility = View.GONE
                    }
                }
            } else if (more != null) {
                more.visibility = if (onMore != null) View.VISIBLE else View.GONE
                more.setOnClickListener { v -> onMore?.invoke(v, playlist) }
            }

            itemView.setOnClickListener { onClick(playlist) }
        }

        private fun formatPlayCount(count: Long): String = when {
            count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000.0)
            count >= 10_000 -> "%.1f万".format(count / 10_000.0)
            else -> count.toString()
        }
    }
}
