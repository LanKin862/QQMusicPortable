package com.portable.qqmusic.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 从封面**底部区域**取一个适合当页面底色的颜色。
 *
 * 为什么要这么麻烦：歌手页的背景要和封面下边缘接得上，中间那道渐变才看不出接缝。
 * 只取「全图主色」经常和底部完全不搭（封面主体在上半部分时尤其明显），
 * 而只取一个像素又会被噪点、文字、人物皮肤带偏。这里按四步走：
 *
 * 1. 采样：只截底部 40%，降到 32 像素宽 —— 留下色块分布，扔掉细节；
 * 2. 聚类：k-means 聚成 [K] 个主色，每个带占比权重；
 * 3. 过滤：占比 < [MIN_WEIGHT] 的、接近纯黑/纯白的（除非整张图本来就黑白）剔掉；
 * 4. 挑选：优先占比高、饱和度克制的候选，再按与底部均色的色差/色相校验 ——
 *    差太多就用底部均色（底部是人物/高频纹理时，取色本来就不该硬取）。
 *
 * 选中色会被降饱和到 [MAX_SAT] 以内（大面积纯色高饱和会刺眼），
 * 明度和对比度交给 [Contrast.fitBackground] 统一收拾。
 */
object CoverColor {

    /** 采样宽度（像素）—— 32 已经足够看出色块分布，再大只是浪费 */
    private const val SAMPLE_W = 32

    /** 聚类个数：太少会把两种颜色并成一个，太多会选出占比很小的噪声色 */
    private const val K = 6

    /** 迭代次数：32×13 的输入，8 次足够收敛 */
    private const val ITER = 8

    /** 低于这个占比的候选直接丢掉 —— 用小占比色当背景会像「凭空出现」 */
    private const val MIN_WEIGHT = 0.05f

    /** 接近纯黑/纯白的阈值：这两个明度做底色要么死黑要么刺眼，除非整图都是 */
    private const val TOO_DARK = 0.03f
    private const val TOO_LIGHT = 0.97f

    /** 选中色的饱和度上限 —— 大面积纯色要克制 */
    private const val MAX_SAT = 0.35f

    /** 候选与底部均色的色差上限（CIE76）。超过就说明「主色和过渡区不是一回事」 */
    private const val MAX_DELTA_E = 20.0

    /** 色相偏差上限（度）：超过 60° 渐变中途会出现脏色 */
    private const val MAX_HUE_GAP = 60f

    /** 底部采样占比 */
    private const val BOTTOM_FRACTION = 0.4f

    fun backgroundFor(bitmap: Bitmap): Int? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        // ---------- 1. 只取底部 ----------
        val bandTop = (h * (1f - BOTTOM_FRACTION)).toInt().coerceIn(0, h - 1)
        val bandH = (h - bandTop).coerceAtLeast(1)
        val band = try {
            Bitmap.createBitmap(bitmap, 0, bandTop, w, bandH)
        } catch (e: Exception) {
            return null
        }
        val sampleH = (SAMPLE_W * bandH / w.toFloat()).toInt().coerceIn(1, 64)
        val small = Bitmap.createScaledBitmap(band, SAMPLE_W, sampleH, true)
        if (small !== band) band.recycle()

        val n = SAMPLE_W * sampleH
        val px = IntArray(n)
        small.getPixels(px, 0, SAMPLE_W, 0, 0, SAMPLE_W, sampleH)
        small.recycle()
        if (n == 0) return null

        // ---------- 底部均色：过渡区的「基准」 ----------
        var mr = 0L
        var mg = 0L
        var mb = 0L
        for (c in px) {
            mr += Color.red(c)
            mg += Color.green(c)
            mb += Color.blue(c)
        }
        val mean = Color.rgb((mr / n).toInt(), (mg / n).toInt(), (mb / n).toInt())

        // ---------- 2. k-means ----------
        val centers = kmeans(px, K, ITER)
        val counts = IntArray(centers.size)
        for (c in px) {
            var best = 0
            var bestD = Long.MAX_VALUE
            for (i in centers.indices) {
                val d = dist2(c, centers[i])
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            counts[best]++
        }

        // ---------- 3. 过滤 + 4. 打分 ----------
        var bestColor = 0
        var bestScore = -1f
        var anyCandidate = false
        val meanLab = labOf(mean)
        val meanHue = hueOf(mean)
        val meanSat = satOf(mean)

        for (i in centers.indices) {
            val weight = counts[i] / n.toFloat()
            if (weight < MIN_WEIGHT) continue
            val c = centers[i]
            val lum = ColorUtilsLum(c)
            val sat = satOf(c)
            // 纯黑/纯白只有在整张图都这样时才允许（黑白封面）
            val extreme = lum < TOO_DARK || lum > TOO_LIGHT
            if (extreme && !isMonochrome(px)) continue
            anyCandidate = true

            // 占比越高越好；饱和度越偏离「适中」越扣分；色相与底部差太远直接淘汰
            val hueGap = hueGap(meanHue, hueOf(c))
            if (!extreme && meanSat > 0.08f && hueGap > MAX_HUE_GAP) continue
            val de = deltaE(labOf(c), meanLab)
            if (!extreme && de > MAX_DELTA_E) continue

            val satPenalty = abs(sat - 0.2f).coerceAtMost(0.5f)
            val score = weight * (1f - satPenalty)
            if (score > bestScore) {
                bestScore = score
                bestColor = c
            }
        }

        // 底部太花 / 候选全被过滤 → 退回底部均色（用户经验：这时不该硬取，用均值 + 渐变遮罩）
        val chosen = if (anyCandidate && bestScore > 0f) bestColor else mean

        // ---------- 降饱和 + 与均色轻微混合，保证过渡 --------------------------------------------------
        var out = desaturate(chosen, MAX_SAT)
        out = blend(out, mean, 0.18f)
        return out
    }

    // ---------------- 工具 ----------------

    private fun kmeans(px: IntArray, k: Int, iter: Int): IntArray {
        val kk = k.coerceAtMost(px.size)
        val centers = IntArray(kk)
        // 均匀间隔取样做初始化，比随机稳（同样的封面每次得到的底色一致）
        for (i in 0 until kk) {
            centers[i] = px[(i * px.size / kk).coerceIn(0, px.size - 1)]
        }
        val sumR = LongArray(kk)
        val sumG = LongArray(kk)
        val sumB = LongArray(kk)
        val cnt = IntArray(kk)
        repeat(iter) {
            java.util.Arrays.fill(sumR, 0L)
            java.util.Arrays.fill(sumG, 0L)
            java.util.Arrays.fill(sumB, 0L)
            java.util.Arrays.fill(cnt, 0)
            for (c in px) {
                var best = 0
                var bestD = Long.MAX_VALUE
                for (i in 0 until kk) {
                    val d = dist2(c, centers[i])
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                sumR[best] = sumR[best] + Color.red(c).toLong()
                sumG[best] = sumG[best] + Color.green(c).toLong()
                sumB[best] = sumB[best] + Color.blue(c).toLong()
                cnt[best] = cnt[best] + 1
            }
            for (i in 0 until kk) {
                if (cnt[i] == 0) continue
                centers[i] = Color.rgb(
                    (sumR[i] / cnt[i]).toInt(),
                    (sumG[i] / cnt[i]).toInt(),
                    (sumB[i] / cnt[i]).toInt()
                )
            }
        }
        return centers
    }

    private fun dist2(a: Int, b: Int): Long {
        val dr = (Color.red(a) - Color.red(b)).toLong()
        val dg = (Color.green(a) - Color.green(b)).toLong()
        val db = (Color.blue(a) - Color.blue(b)).toLong()
        return dr * dr + dg * dg + db * db
    }

    private fun isMonochrome(px: IntArray): Boolean {
        var maxSat = 0f
        for (c in px) {
            val s = satOf(c)
            if (s > maxSat) maxSat = s
            if (maxSat > 0.10f) return false
        }
        return true
    }

    private fun satOf(c: Int): Float {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
        return hsv[1]
    }

    private fun hueOf(c: Int): Float {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
        return if (hsv[1] < 0.05f) -1f else hsv[0]
    }

    private fun hueGap(a: Float, b: Float): Float {
        if (a < 0f || b < 0f) return 0f
        val d = abs(a - b)
        return if (d > 180f) 360f - d else d
    }

    private fun desaturate(c: Int, maxSat: Float): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
        if (hsv[1] <= maxSat) return c
        hsv[1] = maxSat
        return Color.HSVToColor(hsv)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val g = f.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) * (1 - g) + Color.red(b) * g).toInt(),
            (Color.green(a) * (1 - g) + Color.green(b) * g).toInt(),
            (Color.blue(a) * (1 - g) + Color.blue(b) * g).toInt()
        )
    }

    private fun ColorUtilsLum(c: Int): Float = Contrast.luminance(c).toFloat()

    // ---------------- Lab / ΔE ----------------

    private fun labOf(c: Int): DoubleArray {
        val r = srgb(Color.red(c) / 255.0)
        val g = srgb(Color.green(c) / 255.0)
        val b = srgb(Color.blue(c) / 255.0)
        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = (r * 0.2126 + g * 0.7152 + b * 0.0722)
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun srgb(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun labF(t: Double): Double =
        if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0

    private fun deltaE(a: DoubleArray, b: DoubleArray): Double {
        val dl = a[0] - b[0]
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt(dl * dl + da * da + db * db)
    }
}
