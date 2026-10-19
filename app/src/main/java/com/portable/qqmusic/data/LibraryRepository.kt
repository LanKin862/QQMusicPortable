package com.portable.qqmusic.data

import com.portable.qqmusic.core.Log
import com.portable.qqmusic.data.api.Login
import com.portable.qqmusic.data.api.Playlist
import com.portable.qqmusic.data.api.UserApi

/**
 * 「我的」页数据仓库: 收藏 / 本地 / 自建歌单 / 收藏歌单。
 */
object LibraryRepository {

    class Result(
        val created: List<Playlist> = emptyList(),
        val collected: List<Playlist> = emptyList(),
        val favoriteSongCount: Int = 0,
        val localCount: Int = 0,
        val error: String = ""
    )

    /**
     * [useCache] 为 true 时优先用本地持久化的歌单列表 (冷启动/断网秒开);
     * 下拉刷新传 false。
     */
    fun load(localCount: Int, useCache: Boolean = true): Result {
        if (!Login.isLoggedIn()) {
            return Result(localCount = localCount, error = "未登录")
        }
        return try {
            val lib = UserApi.library(useCache = useCache)
            if (lib == null) {
                Result(localCount = localCount, error = "歌单加载失败")
            } else {
                Result(
                    created = lib.created,
                    collected = lib.collected,
                    favoriteSongCount = lib.favoriteSongCount,
                    localCount = localCount
                )
            }
        } catch (t: Throwable) {
            Log.w("Library", t)
            Result(localCount = localCount, error = "歌单加载失败")
        }
    }
}
