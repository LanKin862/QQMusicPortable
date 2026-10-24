package com.portable.qqmusic.ui

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.api.DiscoverApi
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.ui.adapter.PlaylistAdapter
import com.portable.qqmusic.ui.util.Extras

/**
 * 「更多为你推荐」。
 *
 * 推荐页那一行只放 12 个, 这里的列表把个性化货架 + 广场推荐流合并后的
 * 全部结果 (最多 [LIMIT] 个) 一次列出来, 供用户慢慢挑。
 */
class RecommendMoreActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RecommendMore"

        /** `DiscoverApi.recommendPlaylists` 的上限就是 60 */
        private const val LIMIT = 60
    }

    private lateinit var listView: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var adapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommend_more)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        listView = findViewById(R.id.list)
        loading = findViewById(R.id.loading)
        emptyView = findViewById(R.id.empty_view)

        adapter = PlaylistAdapter(PlaylistAdapter.MODE_ROW, onClick = { openPlaylist(it) })
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        load()
    }

    private fun load() {
        loading.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        Task.io {
            val list = runCatching { DiscoverApi.recommendPlaylists(LIMIT) }
                .onFailure { Log.w(TAG, "加载更多推荐失败: ${it.message}") }
                .getOrDefault(emptyList())
            Task.main {
                if (isFinishing) return@main
                loading.visibility = View.GONE
                adapter.submit(list)
                emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openPlaylist(playlist: Playlist) {
        when (playlist.type) {
            Playlist.TYPE_ALBUM -> startActivity(
                Extras.albumIntent(this, playlist.disstid, playlist.title)
            )
            Playlist.TYPE_TOP -> startActivity(
                Extras.playlistIntent(
                    this, playlist.id, playlist.title, Extras.SOURCE_TOP,
                    cover = playlist.cover
                )
            )
            else -> startActivity(
                Extras.playlistIntent(
                    this, playlist.id, playlist.title, Extras.SOURCE_PLAYLIST,
                    cover = playlist.cover
                )
            )
        }
    }
}
