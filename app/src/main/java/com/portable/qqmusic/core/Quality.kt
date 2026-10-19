package com.portable.qqmusic.core

/**
 * 音质档位。
 *
 * [prefix] 是歌曲文件名前缀, 服务端据此返回不同码率的 vkey;
 * [order] 越大音质越高, vkey 缺失时按此降级。
 */
enum class Quality(
    val label: String,
    val prefix: String,
    val ext: String,
    val order: Int,
    /** 是否需要绿钻/SVIP 才有播放权限 */
    val needVip: Boolean
) {
    STANDARD("标准品质", Config.PREFIX_M4A, Config.EXT_MP3, 0, false),
    HQ("HQ 高品质", Config.PREFIX_MP3, Config.EXT_MP3, 1, false),
    SQ("SQ 无损品质", Config.PREFIX_FLAC, Config.EXT_FLAC, 2, true),
    HIRES("Hi-Res 无损品质", Config.PREFIX_HIRES, Config.EXT_FLAC, 3, true);

    companion object {
        private val byOrder = entries.sortedBy { it.order }

        fun fromPref(raw: String?, fallback: Quality = STANDARD): Quality {
            if (raw.isNullOrEmpty()) return fallback
            return try {
                valueOf(raw)
            } catch (t: Throwable) {
                fallback
            }
        }

        /** 从 [quality] 开始逐级降档, 用于服务端没有目标档位 vkey 时兜底 */
        fun ladderFrom(quality: Quality): List<Quality> =
            byOrder.filter { it.order <= quality.order }.sortedByDescending { it.order }

        /** 全部档位由高到低 */
        fun allDesc(): List<Quality> = byOrder.sortedByDescending { it.order }
    }
}

/** 播放过渡效果 */
enum class PlayFade(val label: String) {
    SEAMLESS("无缝播放"),
    CROSSFADE("淡入淡出");

    companion object {
        fun fromPref(raw: String?): PlayFade =
            if (raw == CROSSFADE.name) CROSSFADE else SEAMLESS
    }
}

/** 播放模式 */
enum class PlayMode(val label: String) {
    LIST_LOOP("列表循环"),
    SINGLE_LOOP("单曲循环"),
    SHUFFLE("随机播放");

    companion object {
        fun fromPref(raw: String?): PlayMode = try {
            valueOf(raw ?: LIST_LOOP.name)
        } catch (t: Throwable) {
            LIST_LOOP
        }

        fun next(cur: PlayMode): PlayMode = when (cur) {
            LIST_LOOP -> SINGLE_LOOP
            SINGLE_LOOP -> SHUFFLE
            SHUFFLE -> LIST_LOOP
        }
    }
}

/**
 * 播放队列的来源。
 *
 * 「猜你喜欢」不是一个有限的集合: 服务端每次调用本来就换一批, 所以它比普通歌单多两条规矩 ——
 * 不提供随机播放 (内容已经是按口味现推的, 再随机只会让"播到最后一首"无从判断),
 * 以及播到最后一首时自动续一批, 让队列能一直往下走。
 */
enum class QueueKind {
    NORMAL,
    GUESS;

    companion object {
        fun fromPref(raw: String?): QueueKind = try {
            valueOf(raw ?: NORMAL.name)
        } catch (t: Throwable) {
            NORMAL
        }
    }
}
