package com.portable.qqmusic.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.portable.qqmusic.R
import com.portable.qqmusic.data.api.Singer
import com.portable.qqmusic.ui.util.ImageLoader

/**
 * 搜索结果里的歌手行。
 *
 * 直接复用 [R.layout.item_playlist_row] 的骨架 (头像 + 标题 + 副标题),
 * 只把右侧「更多」按钮藏起来 —— 歌手没有可做的菜单操作, 点整行进详情即可。
 */
class SingerAdapter(private val onClick: (Singer) -> Unit) :
    RecyclerView.Adapter<SingerAdapter.Holder>() {

    private var items: List<Singer> = emptyList()

    fun submit(list: List<Singer>) {
        items = list
        notifyDataSetChanged()
    }

    fun append(list: List<Singer>) {
        items = items + list
        notifyDataSetChanged()
    }

    fun currentItems(): List<Singer> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_row, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.item_cover)
        private val title: TextView = itemView.findViewById(R.id.item_title)
        private val subtitle: TextView = itemView.findViewById(R.id.item_subtitle)
        private val more: ImageButton? = itemView.findViewById(R.id.item_more)

        fun bind(singer: Singer) {
            title.text = singer.name
            more?.visibility = View.GONE

            val ctx = itemView.context
            subtitle.text = when {
                singer.songCount > 0 -> ctx.getString(R.string.song_count_format, singer.songCount)
                singer.albumCount > 0 -> ctx.getString(R.string.singer_album_count_format, singer.albumCount)
                else -> ""
            }
            subtitle.visibility = if (subtitle.text.isNullOrEmpty()) View.GONE else View.VISIBLE

            if (singer.avatar.isNotEmpty()) {
                ImageLoader.load(ctx, singer.avatar, cover)
            } else {
                cover.setImageResource(R.drawable.ic_music_placeholder)
            }

            itemView.setOnClickListener { onClick(singer) }
        }
    }
}
