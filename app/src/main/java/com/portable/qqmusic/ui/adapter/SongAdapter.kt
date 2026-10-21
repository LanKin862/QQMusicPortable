package com.portable.qqmusic.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.portable.qqmusic.R
import com.portable.qqmusic.data.api.PlayUrlApi
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.ui.util.ImageLoader

/**
 * 通用歌曲列表适配器。
 *
 * 单实例复用 (没有多 ViewType), 且不持有 Context 引用之外的资源,
 * onBindViewHolder 里只做字符串设置, 避免列表滑动时分配对象。
 */
class SongAdapter(
    private var items: List<Song> = emptyList(),
    private val onClick: (position: Int, song: Song) -> Unit,
    private val onMore: ((View, position: Int, song: Song) -> Unit)? = null,
    private val onPlay: ((position: Int) -> Unit)? = null,
    /** 行内附加状态 (下载进度/结果); 返回 null 时按普通歌曲行渲染 */
    private val stateOf: ((Song) -> RowState?)? = null,
    /** 哪些行显示右侧「更多」按钮 (默认只要有 [onMore] 就显示) */
    private val showMoreOf: ((Song) -> Boolean)? = null,
    /** 深色或着色背景上要换一套文字颜色 (歌手页), 不传就沿用日间那套 */
    titleColor: Int? = null,
    subtitleColor: Int? = null,
    /** 批量模式下点行/点勾选框的回调 */
    private val onToggleSelect: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.Holder>() {

    /** 批量多选模式: 行尾的「更多」换成勾选框 */
    private var selecting = false
    private var checkedKeys: Set<String> = emptySet()

    /** 进入/退出批量模式 (选中集合一起换掉, 免得残留上一轮的勾) */
    fun setSelection(selecting: Boolean, checked: Set<String>) {
        this.selecting = selecting
        // 一定要拷一份: 调用方那边是可变集合, 直接持有引用的话
        // 下次「取消勾选」时它先被改掉再传进来, 内容比对恒等 → 界面不刷新
        this.checkedKeys = HashSet(checked)
        notifyDataSetChanged()
    }

    /** 只换勾选集合, 不重建列表 */
    fun setChecked(keys: Set<String>) {
        val next = HashSet(keys)
        if (next == checkedKeys) return
        checkedKeys = next
        notifyDataSetChanged()
    }

    private var titleTint: Int? = titleColor
    private var subTint: Int? = subtitleColor

    /**
     * 换文字颜色 (歌手页按封面取色切深浅两套色板时用)。
     *
     * 只刷可见区间的文字, 不重建列表, 所以滚动位置和高亮都不受影响。
     */
    fun setColors(title: Int?, sub: Int?) {
        if (titleTint == title && subTint == sub) return
        titleTint = title
        subTint = sub
        notifyItemRangeChanged(0, itemCount)
    }

    /**
     * 行内状态。
     *
     * [progress] 大于等于 0 时显示进度条; 小于 0 表示只显示 [text] (比如「已完成」)。
     */
    data class RowState(val text: String, val progress: Int = -1)

    /** 当前正在播放的歌曲 mid, 用于高亮 */
    private var playingKey: String? = null

    fun submit(list: List<Song>) {
        items = list
        notifyDataSetChanged()
    }

    fun append(list: List<Song>) {
        if (list.isEmpty()) return
        val start = items.size
        items = items + list
        notifyItemRangeInserted(start, list.size)
    }

    fun setPlaying(key: String?) {
        if (key == playingKey) return
        val old = playingKey
        playingKey = key
        items.indexOfFirst { it.key() == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        items.indexOfFirst { it.key() == key }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    fun currentItems(): List<Song> = items

    /** 只重绑现有行, 不换数据源 (下载进度刷新走这里) */
    fun refresh() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val index: TextView = itemView.findViewById(R.id.item_index)
        private val title: TextView = itemView.findViewById(R.id.item_title)
        private val subtitle: TextView = itemView.findViewById(R.id.item_subtitle)
        private val duration: TextView = itemView.findViewById(R.id.item_duration)
        private val tag: TextView = itemView.findViewById(R.id.item_tag)
        private val more: ImageButton = itemView.findViewById(R.id.item_more)
        private val progress: ProgressBar = itemView.findViewById(R.id.item_progress)

        fun bind(song: Song, position: Int) {
            val playing = song.key() == playingKey
            index.text = if (playing) "♪" else (position + 1).toString()
            title.text = song.name
            title.setTextColor(
                if (playing) {
                    androidx.core.content.ContextCompat.getColor(itemView.context, R.color.brand)
                } else {
                    titleTint ?: androidx.core.content.ContextCompat.getColor(
                        itemView.context, R.color.text_primary
                    )
                }
            )
            // 副标题/时长/序号原本的颜色写在 item_song.xml 里, 深色底上要单独覆盖
            subTint?.let {
                subtitle.setTextColor(it)
                duration.setTextColor(it)
                index.setTextColor(it)
            }

            val state = stateOf?.invoke(song)
            if (state != null) {
                // 下载任务行: 副标题让位给状态文案, 右下角显示百分比与进度条
                subtitle.text = state.text
                if (state.progress in 0..100) {
                    progress.visibility = View.VISIBLE
                    progress.progress = state.progress
                    duration.text = "${state.progress}%"
                } else {
                    progress.visibility = View.GONE
                    duration.text = ""
                }
            } else {
                subtitle.text =
                    if (song.albumName.isEmpty()) song.singer else "${song.singer} - ${song.albumName}"
                duration.text = PlayUrlApi.durationText(song.durationSec)
                progress.visibility = View.GONE
            }

            if (song.type == Song.TYPE_LOCAL) {
                tag.visibility = View.VISIBLE
                tag.text = "本地"
            } else if (song.vipOnly) {
                tag.visibility = View.VISIBLE
                tag.text = "VIP"
            } else {
                tag.visibility = View.GONE
            }

            itemView.setOnClickListener {
                if (selecting) onToggleSelect?.invoke(song) else onClick(position, song)
            }
            if (selecting) {
                val on = checkedKeys.contains(song.key())
                more.visibility = View.VISIBLE
                more.setImageResource(
                    if (on) R.drawable.ic_check_circle_on else R.drawable.ic_check_circle_off
                )
                more.setOnClickListener { onToggleSelect?.invoke(song) }
            } else {
                val showMore = onMore != null && (showMoreOf?.invoke(song) ?: true)
                more.visibility = if (showMore) View.VISIBLE else View.GONE
                more.setImageResource(R.drawable.ic_more)
                more.setOnClickListener { v -> if (showMore) onMore?.invoke(v, position, song) }
            }
        }
    }
}
