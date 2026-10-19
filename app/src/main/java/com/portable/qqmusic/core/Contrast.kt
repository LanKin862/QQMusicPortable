package com.portable.qqmusic.core

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.pow

/**
 * WCAG 2.1 对比度工具。
 *
 * 播放详情和歌手页的背景色来自封面取色, 碰上浅色封面时白字会糊在背景上。
 * 与其给每段文字单独配色 (同一屏里冒出两三种文字色反而更乱),
 * 不如**固定文字色板, 反过来把背景压暗到足以承载它**。
 *
 * 色板只有三档, 都是白色:
 * - 主文字 `#FFFFFF`   目标 >= 7.0
 * - 次文字 `#CCFFFFFF` 目标 >= 5.0
 * - 三级 `#99FFFFFF`   目标 >= 4.5
 *
 * 把背景压到「纯白 >= 7.0」之后, 60% 白叠上去仍能过 4.5 —— 这是能同时满足三档的下限。
 */
object Contrast {

    /** 正文 / 大字号的最低对比度, 见 WCAG 2.1 AA */
    const val AA_NORMAL = 4.5
    const val AA_LARGE = 3.0

    /** 我们要求的底线: 纯白压到这个比值, 60% 白才有 4.5 */
    const val WHITE_TARGET = 7.0

    fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** 相对亮度, 0(黑) ~ 1(白) */
    fun luminance(color: Int): Double {
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    /** 两色对比度, 1.0 ~ 21.0 */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * 把 [bg] 往黑里压, 直到与 [fg] 的对比度达到 [target]。
     *
     * 每次混黑 12%, 最多 [steps] 次; 到底仍不够就返回最深的那个 —— 宁可背景全黑,
     * 也不要出现「算了够不着」然后把文字压到看不清。
     */
    fun darkenUntil(
        fg: Int,
        bg: Int,
        target: Double = WHITE_TARGET,
        steps: Int = 20,
        step: Float = 0.12f
    ): Int {
        var out = bg
        var i = 0
        while (i++ < steps && ratio(fg, out) < target) {
            out = ColorUtils.blendARGB(out, Color.BLACK, step)
        }
        return out
    }

    /** 把 [bg] 往白里提, 供浅色底 + 深字的方向用 */
    fun lightenUntil(
        fg: Int,
        bg: Int,
        target: Double = AA_NORMAL,
        steps: Int = 20,
        step: Float = 0.12f
    ): Int {
        var out = bg
        var i = 0
        while (i++ < steps && ratio(fg, out) < target) {
            out = ColorUtils.blendARGB(out, Color.WHITE, step)
        }
        return out
    }

    /** [fg] 以 [alpha] (0~1) 叠到 [bg] 上之后的实际颜色, 半透明文字算对比度前要先合成 */
    fun composite(fg: Int, alpha: Float, bg: Int): Int =
        ColorUtils.blendARGB(bg, fg, alpha.coerceIn(0f, 1f))

    /**
     * 这个底色上该用深色文字还是白色文字 —— 谁的对比度高用谁。
     *
     * 白字要求背景很暗 (纯白 7:1 需要 L <= 0.10), 黑字要求背景够亮 (L >= 0.30)。
     * 封面取色落在哪一边就用哪一套色板, 不要为了保白字把浅封面一律压成深色 ——
     * 那样每首歌的背景看起来都是同一个深灰, 反而丢掉了「跟着封面走」的效果。
     */
    fun preferDarkText(bg: Int): Boolean = ratio(Color.BLACK, bg) > ratio(Color.WHITE, bg)

    /**
     * 把 [raw] 调成能承载所选前景色的底色。
     *
     * [darkText] 为 true 时往白里提 (黑字需要亮底), 否则往黑里压。两侧都以
     * [WHITE_TARGET] 为目标, 这样 60% 半透明的前景依然能过 4.5。
     * 已经达标的底色原样返回 —— 大多数封面都落在这个分支里, 背景因此保持住原本的色相。
     */
    fun fitBackground(raw: Int, darkText: Boolean): Int =
        if (darkText) lightenUntil(Color.BLACK, raw, WHITE_TARGET)
        else darkenUntil(Color.WHITE, raw, WHITE_TARGET)

    /**
     * 面 (卡片/迷你条) 与其所在底色之间的最小反差。
     *
     * 1.35 是「看得出是一层独立的表面、又不像贴了块补丁」的位置: 底色 L=0.10 时
     * 面推到 L≈0.15, 亮度差 0.05 —— 远高于人眼约 0.02 的辨别阈值, 又不到 1.5 那种
     * 明显的双色块观感。想更明显就把这个常量往 1.5 调, 其余代码不用动。
     */
    const val SURFACE_RATIO = 1.35

    /**
     * 把 [base] **朝远离自己的方向**推一档, 直到与 [base] 的对比度达到 [target]。
     *
     * 底色偏暗就提亮、偏亮就压深 —— 方向永远是「离背景更远」, 所以观感上是一层浮起
     * 的表面, 而不是第二套主题色。调用前 [base] 应当已经满足文字可读, 这里只会让它更
     * 安全, 不会再压回去。
     */
    fun separate(base: Int, target: Double = SURFACE_RATIO, steps: Int = 30): Int =
        if (luminance(base) < 0.5) lightenUntil(base, base, target, steps, 0.06f)
        else darkenUntil(base, base, target, steps, 0.06f)
}
