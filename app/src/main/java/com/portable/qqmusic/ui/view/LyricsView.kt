package com.portable.qqmusic.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.core.content.ContextCompat
import com.portable.qqmusic.R
import com.portable.qqmusic.data.api.Lyrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 自绘歌词栏 —— 照 folia 的「莫奈」模式重做。
 *
 * 只画当前行上下各三行、按距离衰减缩放与透明度、当前行整行扫色，换行用临界阻尼弹簧过渡。
 * 每帧只维护三个标量（toneAlpha / toneScale / toneOffset），不在帧循环里分配对象。
 *
 * 扫色按**排版行**分段推进: 一句长歌词折成两三行时, 每行各自从左往右扫,
 * 而不是拿一个矩形斜切过去 —— 后者在折行处会出现「上半行已经亮了、下半行还没动」的错位。
 */
class LyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onSeek: ((Int) -> Unit)? = null
    var textScale: Float = 1.0f
        set(value) {
            if (abs(field - value) < 0.01f) return
            field = value
            rebuild()
        }

    var showTranslation: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    private var doc: Lyrics.Document = Lyrics.Document(emptyList())
    private var rows: List<Row> = emptyList()

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val transPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var paletteNormal: Int? = null
    private var paletteActive: Int? = null

    private var currentIndex = -1
    private var avgStep = 100f

    // 弹簧状态
    private var smooth = 0f
    private var springVelocity = 0f
    private var animating = false
    private var lastFrameUptime = 0L

    // 扫色用的时间锚点: onProgress 是 500ms 一次, 按它画扫色只有 2fps
    private var anchorMs = 0
    private var anchorUptime = 0L
    private var playing = false

    /** 已经把一帧 vsync 回调排进队列了吗 */
    private var frameScheduled = false

    /** 上一次真正重绘的时刻, 用来把重绘压到 30fps */
    private var lastSweepUptime = 0L

    // 逐帧缓存的当前行渐变参数（避免在 onDraw 里 new 对象）
    private var toneAlpha = 1f
    private var toneScale = 1f

    // 手动翻
    private var manualAnchor: Float? = null
    private var autoResumeAt = 0L
    private var flingVelocity = 0f
    private var velocityTracker: VelocityTracker? = null

    init {
        setWillNotDraw(false)
        // 字号和封面上的歌名对齐(19sp + bold), 歌词读起来才和标题是一个层级。
        // 注意 textAlign 必须留默认的 LEFT —— StaticLayout 自己接管对齐,
        // 给它 CENTER 会让每行从中间起笔, 整句飞出画布。
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        transPaint.typeface = Typeface.DEFAULT
        placeholderPaint.textAlign = Paint.Align.CENTER
    }

    fun setLyrics(document: Lyrics.Document) {
        doc = document
        currentIndex = -1
        smooth = 0f
        springVelocity = 0f
        manualAnchor = null
        flingVelocity = 0f
        rebuild()
        scheduleLineWake()
    }

    /** 这首歌没有歌词: 退回「暂无歌词」占位 */
    fun setEmpty() {
        setLyrics(Lyrics.Document(emptyList()))
    }

    fun hasLyrics(): Boolean = rows.isNotEmpty()

    /** 主题色: normal 是未唱/非当前行, active 是当前行已唱部分 */
    fun setPalette(normal: Int?, active: Int?) {
        paletteNormal = normal
        paletteActive = active
        invalidate()
    }

    /** 播放状态: 暂停时扫色停在锚点不动 */
    fun setPlaying(value: Boolean) {
        if (playing == value) return
        // 切换的瞬间把已经走过的时长压进锚点: 不压的话暂停期间那段
        // 会在恢复播放时被算进去, 扫色直接往前蹦一块
        anchorMs = nowMs()
        anchorUptime = SystemClock.uptimeMillis()
        playing = value
        if (value) scheduleLineWake() else removeCallbacks(lineWake)
    }

    fun syncTo(positionMs: Int, smooth: Boolean = true) {
        /*
         * 顺序很重要: 先按外部位置修正锚点, 再拿修正过的时钟判行。
         *
         * 反过来(先判行再对齐)的话, 拖动进度条这种硬跳会先按旧时刻算出一行,
         * 下一帧才纠回来 —— 表现就是歌词先闪一下旧行再跳到新行。
         */
        if (!smooth) {
            anchorMs = positionMs
            anchorUptime = SystemClock.uptimeMillis()
        } else {
            /*
             * 追漂移要平滑, 不能一有差距就硬拽。
             *
             * 硬拽的问题: onProgress 的值本身就滞后几十上百毫秒, 每次 tick 都把锚点拉回去,
             * 扫色就会一顿一顿地往回退(之前那个「一跳一跳」就是这么来的)。
             * 现在的做法是差距小就按 1/3 收敛, 几帧内追上, 肉眼看不出来; 只有差距大到
             * 不像漂移(切歌、拖动进度条)才直接对齐。
             */
            val drift = positionMs - nowMs()
            when {
                abs(drift) > RESYNC_TOLERANCE_MS -> {
                    anchorMs = positionMs
                    anchorUptime = SystemClock.uptimeMillis()
                }
                abs(drift) > DRIFT_SOFT_MS -> {
                    anchorMs = nowMs() + drift / 3
                    anchorUptime = SystemClock.uptimeMillis()
                }
            }
        }
        // 锚点可能刚动过, 换成新时钟再判行
        val index = doc.indexAt(nowMs())
        if (index != currentIndex) currentIndex = index
        if (!smooth) {
            // 硬定位: 画面直接落到当前行, 不留过渡(拖动进度条时过渡反而像卡住)
            this.smooth = currentIndex.toFloat()
            springVelocity = 0f
            manualAnchor = null
        } else if (manualAnchor == null && abs(this.smooth - currentIndex) > 0.001f) {
            ensureAnimating()
        }
        scheduleLineWake()
        if (!animating) ensureAnimating()
    }

    /** 拖动进度条时强制定位 */
    fun syncToImmediately(positionMs: Int) {
        syncTo(positionMs, smooth = false)
    }

    fun currentLine(): Int = currentIndex

    /** 按「锚点 + 系统时间」推算当前播放位置, 让扫色按屏刷新率推进 */
    private fun nowMs(): Int {
        if (!playing) return anchorMs
        val delta = (SystemClock.uptimeMillis() - anchorUptime).toInt()
        return anchorMs + delta.coerceAtLeast(0)
    }

    private fun ensureAnimating() {
        if (!animating) {
            animating = true
            lastFrameUptime = SystemClock.uptimeMillis()
        }
        scheduleSweep()
    }

    /**
     * 让 [currentIndex] 跟上扫色时钟。
     *
     * 以前 currentIndex 只在 syncTo 里更新, 而 syncTo 由播放页 500ms 一次的 tick 驱动 ——
     * 换行于是最多晚半秒: 新的一句已经唱了半秒, 界面还停在上一句; 等它终于聚焦过来,
     * 前半句的进度是"啪"一下补出来的, 没有扫过的过程。
     *
     * 现在行切换和扫色用的是同一个本地时钟, 且由 [scheduleLineWake] 定点唤醒,
     * 不再受外部 tick 频率影响 —— 时间点一到就换行, 进度自然从 0 开始扫。
     */
    private fun advanceLineIfNeeded() {
        if (rows.isEmpty()) return
        val index = doc.indexAt(nowMs())
        if (index == currentIndex) return
        currentIndex = index
        if (manualAnchor == null) ensureAnimating()
        scheduleLineWake()
    }

    /**
     * 在「下一句该开始」的那一刻把自己叫醒。
     *
     * 扫色帧循环在换行结束后会停掉(见 [advanceSpring]), 之后若没人叫, 就要等外部 tick
     * 才发现该换行了 —— 这正是之前那句"过一段时间才聚焦"的另一半原因。这里按本地时钟
     * 精确 post 一次, 到点 invalidate, onDraw 里 [advanceLineIfNeeded] 立刻接管。
     *
     * 单次最长挂 4 秒: 歌词间奏常有十几秒空白, 一次性睡太久的话中途的 seek/暂停
     * 都要额外取消, 反而容易漏; 4 秒醒一次重排成本可以忽略。
     */
    private val lineWake = Runnable {
        if (!playing) return@Runnable
        advanceLineIfNeeded()
        invalidate()
        scheduleLineWake()
    }

    private fun scheduleLineWake() {
        removeCallbacks(lineWake)
        if (!playing || rows.isEmpty()) return
        val next = rows.getOrNull(currentIndex + 1) ?: return
        val delay = (next.timeMs - nowMs()).coerceIn(0, 4_000).toLong() + 1L
        postDelayed(lineWake, delay)
    }

    // 扫色的重绘节奏交给 vsync。用 postInvalidateDelayed(33) 时真实间隔由消息队列决定,
    // 在 33~90ms 之间抖, 折算到进度就是"一下快一下慢", 长音那种跨度大的行最明显。
    // 这里每个 vsync 回调一次, 再按 33ms 门限放过 —— 节奏恒定了, 进度才是匀速推进。


    private val sweepFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            // 扫色帧里也顺手判一次行: 换行瞬间就能把弹簧和扫色都推起来
            advanceLineIfNeeded()
            if (!animating && !lineStillSweeping()) return
            val frameMs = frameTimeNanos / 1_000_000L
            if (frameMs - lastSweepUptime >= SWEEP_FRAME_MS) {
                lastSweepUptime = frameMs
                invalidate()
            }
            scheduleSweep()
        }
    }

    private fun scheduleSweep() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(sweepFrame)
    }

    // ---------------- 排版 ----------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) rebuild()
    }

    private fun rebuild() {
        rows = emptyList()
        val available = width - paddingLeft - paddingRight
        if (available <= 0) {
            invalidate()
            return
        }
        if (doc.isEmpty) {
            invalidate()
            return
        }

        val density = resources.displayMetrics.density
        // 基准字号取封面那个歌名的 19sp: 当前行和歌名一样大, 上下行按 [applyTone] 的比例缩
        val textSize = (19f * textScale).coerceIn(12f, 32f) * density
        val transSize = (13f * textScale).coerceIn(9f, 24f) * density

        textPaint.textSize = textSize
        transPaint.textSize = transSize

        val vPad = (7f * textScale * density).toInt()
        val gap = (10f * textScale * density).toInt()

        val out = ArrayList<Row>(doc.lines.size)
        for ((i, line) in doc.lines.withIndex()) {
            val layout = makeLayout(line.text, textPaint, available)
            val trans = if (showTranslation && line.hasTranslation) {
                makeLayout(line.transText, transPaint, available)
            } else {
                null
            }
            val h = layout.height + (trans?.height ?: 0) + vPad * 2
            val end = doc.lines.getOrNull(i + 1)?.timeMs ?: (line.timeMs + DEFAULT_LINE_MS)
            out.add(Row(layout, trans, h, line.timeMs, end, line.words))
        }
        rows = out
        avgStep = out.map { it.height }.average().toFloat() + gap
        invalidate()
    }

    private fun makeLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.12f)
            .setIncludePad(false)
            .build()

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) {
            drawPlaceholder(canvas)
            return
        }

        resumeIfIdle()
        // 行号先跟上时钟再画: 换行不能等外部 tick, 否则新行前半句会一次性补色
        advanceLineIfNeeded()
        advanceSpring()

        // 手动翻的时候画面跟着手指走, 行号仍然是自动跟唱的那一行
        val display = manualAnchor ?: smooth
        val now = nowMs()
        val centerY = (height / 2).toFloat()
        val colorNormal = paletteNormal
            ?: ContextCompat.getColor(context, R.color.text_on_player_secondary)
        val colorActive = paletteActive
            ?: ContextCompat.getColor(context, R.color.white)

        val base = (display.roundToInt()).coerceIn(0, rows.size - 1)
        val first = (base - VISIBLE_ROWS).coerceAtLeast(0)
        val last = (base + VISIBLE_ROWS).coerceAtMost(rows.size - 1)
        val pivotX = (width / 2).toFloat()

        for (i in first..last) {
            val offset = i - display
            // 再远的两行 alpha 只剩十几, 本来就看不清, 干脆不画 —— 省两成绘制成本
            if (abs(offset) > FADE_OUT_ROWS) continue
            val isActive = i == currentIndex || abs(offset) < ACTIVE_WINDOW
            val row = rows[i]
            applyTone(offset)
            val top = centerY - row.height / 2f + offset * avgStep

            canvas.save()
            // 以行中心为缩放原点: 远景行往中心收, 不会在边缘露出空白
            canvas.scale(toneScale, toneScale, pivotX, centerY + offset * avgStep)
            canvas.translate(paddingLeft.toFloat(), top + row.height / 2f - row.layout.height / 2f)

            val alpha = (toneAlpha * 255f).toInt().coerceIn(0, 255)
            // 当前行的「未唱」部分压得很暗, 已唱部分才是满亮度
            val unsungAlpha = if (isActive) (alpha * DIM_UNSUNG).toInt().coerceIn(0, 255) else alpha

            textPaint.color = withAlpha(colorNormal, unsungAlpha)
            // 折行的歌词按行居中, 但 StaticLayout 的宽度是整块 —— draw 时以块中心对齐
            row.layout.draw(canvas)

            if (isActive) {
                val progress = lineProgress(row, now)
                // 已唱部分整体提亮: 按排版行分段裁出再画一遍。
                // 比每帧 new 一个 LinearGradient 便宜得多 —— 那版跑两秒就被 GC 拖成幻灯片。
                if (progress > 0f) {
                    textPaint.color = withAlpha(colorActive, alpha)
                    drawSwept(canvas, row, progress)
                }
            }

            // 译文排在原文下面, 一律用次要色, 按同样的距离衰减
            row.trans?.let { trans ->
                canvas.translate(0f, row.layout.height.toFloat())
                transPaint.color = withAlpha(colorNormal, (alpha * 0.86f).toInt().coerceIn(0, 255))
                trans.draw(canvas)
            }

            canvas.restore()
        }
    }

    /**
     * 按排版行分段裁出「已唱」区域。
     *
     * 单行歌词就是一条矩形；折成两行时前半行整条、后半行只到进度点 —— 一行歌词扫过去时,
     * 折行处不会出现上下两半进度不一致的斜切。
     */
    /**
     * 把已唱部分按「行内像素宽度」平滑裁切。
     *
     * 这里**不能**把进度量化到字符。早先版本用 done.toInt() 取「已经过了几个字」, 再按那个字的
     * 右边界裁切, 于是每个字母整块整块地变色 —— 一行走三四秒的长音看着就是一张一张放 PPT。
     * 现在: 字数只用来决定这一行占多少进度, 行内一律按 (right - left) * here 线性插值。
     */
    private fun drawSwept(canvas: Canvas, row: Row, progress: Float) {
        val layout = row.layout
        val lines = layout.lineCount
        val chars = layout.text.length.coerceAtLeast(1)
        var consumed = 0
        for (line in 0 until lines) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            val count = (end - start).coerceAtLeast(1)
            val here = ((progress * chars - consumed) / count).coerceIn(0f, 1f)
            consumed += count
            if (here <= 0f) break
            val left = layout.getLineLeft(line)
            val right = layout.getLineRight(line)
            if (right <= left) continue
            val top = layout.getLineTop(line).toFloat()
            val bottom = layout.getLineBottom(line).toFloat()
            canvas.save()
            canvas.clipRect(left, top, left + (right - left) * here, bottom)
            layout.draw(canvas)
            canvas.restore()
            if (here < 1f) break
        }
    }

    /**
     * 扫色进度 0..1。
     *
     * 有 QRC 逐字时间轴时按每个字自己的起止算 —— 这是唯一能「唱到哪亮到哪」的信息来源;
     * 没有的话退回整行按时间比例 (下一句的 timeMs 就是这一句的终点)。
     */
    private fun lineProgress(row: Row, now: Int): Float {
        if (now <= row.timeMs) return 0f
        val words = row.words
        if (words.isNotEmpty()) {
            val total = words.sumOf { it.text.length }.coerceAtLeast(1)
            var acc = 0f
            for (w in words) {
                val t0 = row.timeMs + w.startMs
                val t1 = t0 + w.durationMs
                when {
                    now >= t1 -> acc += w.text.length
                    now <= t0 -> return (acc / total).coerceIn(0f, 1f)
                    else -> {
                        val f = (now - t0).toFloat() / (t1 - t0).coerceAtLeast(1)
                        return ((acc + w.text.length * f) / total).coerceIn(0f, 1f)
                    }
                }
            }
            return 1f
        }
        val span = (row.endMs - row.timeMs).coerceAtLeast(400)
        return ((now - row.timeMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /**
     * 用外部算出来的「真正唱完」时刻替换掉按下一句推的结束点。
     *
     * 只影响扫色的速度, 不影响换行 —— 换行还是看下一句的 timeMs。
     * [ends] 的下标和 [Lyrics.Document.lines] 对齐, 传 0 表示这一句不做修正。
     *
     * 现在唯一的数据源是 QRC 的逐字时间轴（见 [Line.words]）; 之前那套从音频响度
     * 反推的实现已经删掉了 —— 猜不准, 而且纯能量分不开人声和伴奏。
     */
    fun applyLineEnds(ends: IntArray) {
        if (rows.isEmpty() || ends.isEmpty()) return
        var changed = false
        val next = rows.toMutableList()
        for (i in next.indices) {
            val end = ends.getOrNull(i) ?: continue
            if (end > 0 && end != next[i].endMs) {
                next[i] = next[i].copy(endMs = end)
                changed = true
            }
        }
        if (changed) {
            rows = next
            invalidate()
        }
    }

    private fun drawPlaceholder(canvas: Canvas) {
        placeholderPaint.textSize = 14f * resources.displayMetrics.density
        placeholderPaint.color = paletteNormal
            ?: ContextCompat.getColor(context, R.color.text_on_player_secondary)
        val text = context.getString(R.string.player_lyric_none)
        val y = height / 2f - (placeholderPaint.descent() + placeholderPaint.ascent()) / 2f
        canvas.drawText(text, width / 2f, y, placeholderPaint)
    }

    // ---------------- 弹簧 ----------------

    /**
     * 临界阻尼弹簧推进一帧。
     *
     * 放在 onDraw 里就地推进, 帧循环由 [sweepFrame] 驱动。
     * 弹簧没到位时用 postInvalidateOnAnimation 走满帧率 —— 换行那一下要顺;
     * 到位之后的扫色才降到 30fps, 两套节奏互斥, 不会同时排帧。
     */
    private fun advanceSpring() {
        // 手在翻的时候弹簧让位, 松手后从当前位置继续弹回当前行
        if (manualAnchor != null) return
        val target = currentIndex.toFloat()
        if (!animating) {
            smooth = target
            return
        }
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameUptime).coerceIn(0L, 50L)).toFloat() / 1000f
        lastFrameUptime = now

        if (dt > 0f) {
            // a = (-k * x - c * v) / m
            val accel = (-SPRING_STIFFNESS * (smooth - target) - SPRING_DAMPING * springVelocity) / SPRING_MASS
            springVelocity += accel * dt
            smooth += springVelocity * dt
        }

        val settled = abs(smooth - target) < 0.0015f && abs(springVelocity) < 0.0015f
        if (!settled) {
            // 换行途中按屏幕节奏走, 这段必须顺
            postInvalidateOnAnimation()
            return
        }
        smooth = target
        springVelocity = 0f
        // 弹簧到位了, 但扫色可能还在推进, 交给 30fps 的扫色循环
        if (lineStillSweeping()) {
            scheduleSweep()
        } else {
            animating = false
        }
    }


    private fun activeRowTop(): Float? {
        if (rows.isEmpty()) return null
        val index = currentIndex.coerceIn(0, rows.size - 1)
        val display = manualAnchor ?: smooth
        val offset = index - display
        if (abs(offset) > ACTIVE_WINDOW) return null
        val row = rows[index]
        return (height / 2f) - row.height / 2f + offset * avgStep
    }

    /** 当前行还有没唱完的部分吗。唱完了就不必再为扫色刷新 */
    private fun lineStillSweeping(): Boolean {
        if (!playing) return false
        val row = rows.getOrNull(currentIndex) ?: return false
        return nowMs() < row.endMs
    }

    /** 手动翻之后静置到点就交还给自动跟随 */
    private fun resumeIfIdle() {
        val manual = manualAnchor ?: return
        if (SystemClock.uptimeMillis() < autoResumeAt) {
            advanceFling()
            return
        }
        // 从手停下的位置起弹, 而不是直接跳到当前行
        smooth = manual
        springVelocity = 0f
        manualAnchor = null
        flingVelocity = 0f
        animating = true
        lastFrameUptime = SystemClock.uptimeMillis()
    }

    /**
     * 松手后的惯性滑行。
     *
     * 速度按「行/秒」记, 每帧乘一个与 dt 相关的衰减系数 —— 用固定系数的话,
     * 帧率一变滑行距离就跟着变。
     */
    private fun advanceFling() {
        val anchor = manualAnchor ?: return
        if (abs(flingVelocity) < FLING_STOP) {
            flingVelocity = 0f
            return
        }
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameUptime).coerceIn(0L, 40L)).toFloat() / 1000f
        lastFrameUptime = now
        val next = (anchor - flingVelocity * dt).coerceIn(0f, (rows.size - 1).toFloat())
        manualAnchor = next
        // 每秒衰减到原来的 FLING_DECAY 倍
        flingVelocity *= FLING_DECAY.toDouble().pow(dt.toDouble()).toFloat()
        // 撞到边界就停, 免得在顶上一直磨
        if (next <= 0f || next >= (rows.size - 1).toFloat()) flingVelocity = 0f
        if (abs(flingVelocity) < FLING_STOP) {
            flingVelocity = 0f
            autoResumeAt = SystemClock.uptimeMillis() + MANUAL_IDLE_MS
            scheduleResume()
            invalidate()
        } else {
            postInvalidateOnAnimation()
        }
    }

    // ---------------- 距离衰减 ----------------

    /**
     * 离当前行越远, 越淡越小。数值照搬 folia 的 resolveMonetTone:
     * 待唱行起始更亮、衰减更快；已唱行更暗、收得更慢。
     *
     * 结果写进 [toneAlpha] / [toneScale] 两个字段，不在帧循环里 new 对象。
     */
    private fun applyTone(offset: Float) {
        val distance = abs(offset)
        if (distance < ACTIVE_WINDOW) {
            toneAlpha = 1f
            toneScale = 1f
            return
        }
        val d = max(distance, 1f)
        toneScale = max(0.66f, 0.70f * 0.9f.pow(d - 1f))
        toneAlpha = if (offset > 0f) {
            max(0.24f, 0.50f - (d - 1f) * 0.13f)
        } else {
            max(0.16f, 0.34f - (d - 1f) * 0.09f)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    // ---------------- 手势 ----------------

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragged = false
    private var dragStart = 0f

    /**
     * 到点了就重绘一帧, 让 onDraw 里的 [resumeIfIdle] 有机会把控制权交回去。
     *
     * 手动翻期间 `advanceSpring` 是直接 return 的, 也就没有随之而来的
     * `postInvalidateOnAnimation`, 帧循环断在这里 —— 不补这一下, 松手之后就再也不会重绘,
     * 画面会一直停在手动翻到的那一屏。
     */
    private val resumeTick = Runnable { invalidate() }

    private fun scheduleResume() {
        removeCallbacks(resumeTick)
        postDelayed(resumeTick, MANUAL_IDLE_MS + 32L)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rows.isEmpty()) return super.onTouchEvent(event)
        val slop = resources.displayMetrics.density * 6f
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = SystemClock.uptimeMillis()
                dragged = false
                flingVelocity = 0f
                dragStart = manualAnchor ?: smooth
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                // 一按就先续命, 免得按住的瞬间被复位抢回去
                autoResumeAt = SystemClock.uptimeMillis() + MANUAL_IDLE_MS
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                if (!dragged && abs(dy) > slop) dragged = true
                if (dragged && avgStep > 1f) {
                    manualAnchor = (dragStart - dy / avgStep)
                        .coerceIn(0f, (rows.size - 1).toFloat())
                    autoResumeAt = SystemClock.uptimeMillis() + MANUAL_IDLE_MS
                    scheduleResume()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val moved = abs(event.x - downX) + abs(event.y - downY)
                val quick = SystemClock.uptimeMillis() - downTime < 400L
                if (!dragged && moved < slop * 3f && quick) {
                    seekFromTouch(event.x, event.y)
                } else {
                    // 松手后重新计时: 静置 3 秒才交还自动跟随
                    autoResumeAt = SystemClock.uptimeMillis() + MANUAL_IDLE_MS
                    scheduleResume()
                    startFling()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragged = false
                flingVelocity = 0f
                autoResumeAt = SystemClock.uptimeMillis() + MANUAL_IDLE_MS
                scheduleResume()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 松手时接住手指的速度, 换成「行/秒」继续滑一段。
     *
     * flutter 里那一版是给 ScrollController 挂曲线动画, 我们这边没有滚动容器,
     * 就自己按指数衰减推 manualAnchor。
     */
    private fun startFling() {
        if (manualAnchor == null || avgStep <= 1f) {
            flingVelocity = 0f
            return
        }
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000)
        val vy = tracker.yVelocity          // 向下为正, 手指往上推是负
        tracker.recycle()
        velocityTracker = null
        if (abs(vy) < FLING_MIN_PX) {
            flingVelocity = 0f
            return
        }
        // 手指往上推 vy<0, 内容也跟着往上走 = 行号变大。
        // advanceFling 里是 anchor - v*dt, 所以这里要保持 v 的符号, 别再取反。
        flingVelocity = (vy / avgStep).coerceIn(-FLING_MAX_ROWS, FLING_MAX_ROWS)
        lastFrameUptime = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /** 点某一行跳到那一行的时间点 */
    private fun seekFromTouch(x: Float, y: Float) {
        if (rows.isEmpty()) return
        if (x < paddingLeft || x > width - paddingRight) return
        val display = manualAnchor ?: smooth
        val centerY = (height / 2).toFloat()
        val index = display.roundToInt() + ((y - centerY) / avgStep).roundToInt()
        val row = rows.getOrNull(index) ?: return
        manualAnchor = null
        flingVelocity = 0f
        onSeek?.invoke(row.timeMs)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(resumeTick)
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    private data class Row(
        val layout: StaticLayout,
        val trans: StaticLayout?,
        val height: Int,
        val timeMs: Int,
        val endMs: Int,
        /** 逐字时间轴, 空表表示这首歌没有 QRC, 扫色按整行比例走 */
        val words: List<com.portable.qqmusic.data.api.Qrc.Word> = emptyList()
    )

    private companion object {
        /**
         * 当前行上下各画这么多行。
         *
         * 取 2 而不是 3: 七行时每帧要光栅化七块文字, gfxinfo 的 50th 卡在 19ms 下不来;
         * 五行能压到 16ms 以内, 30fps 的扫色才真的不掉帧。
         */
        const val VISIBLE_ROWS = 2

        /** 超过这个距离的行不画: alpha 已经低到看不出来, 白费绘制 */
        const val FADE_OUT_ROWS = 2.2f

        /** |offset| 小于它就算「当前行」, 享受扫色与满不透明 */
        const val ACTIVE_WINDOW = 0.5f

        /**
         * 当前行未唱部分的亮度系数。
         *
         * 已唱是满亮度 `colorActive`，未唱压到 30% —— 差三倍多，扫到一半一眼能看出分界。
         * 之前取 0.5 时对比还是偏软，而且那版扫色层本身又盖了一遍同色，等于白压。
         */
        const val DIM_UNSUNG = 0.30f

        const val DEFAULT_LINE_MS = 4000

        /**
         * 扫色推进时的重绘门限。
         *
         * 先要顺, 再谈省: 30fps(33ms) 在大字号下扫色边缘仍能看出台阶, 所以放到每个 vsync
         * 都画(16ms)。真正的成本不在 invalidate 频率, 而在 onDraw 里画几行 ——
         * 想再快只能减行数或上局部重绘, 那两条都试过, 见 README 6.55 / 6.56。
         */
        const val SWEEP_FRAME_MS = 16L

        /** 手动翻之后静置多久交还自动跟随 */
        const val MANUAL_IDLE_MS = 3000L

        /**
         * onProgress 报来的位置和本地推算差多少才**硬对齐**。
         *
         * 定得小跟得紧、但容易把扫色往回拽(看着一顿一顿); 定得大跟得松、滞后会拖长。
         * 250ms 是这次试出来的: onProgress 每 500ms 一次, 超过半格偏差基本就是真滞后了,
         * 该直接拉回; 半格以内的交给 [DRIFT_SOFT_MS] 那条路慢慢追。
         */
        const val RESYNC_TOLERANCE_MS = 250

        /**
         * 偏差超过这个值就开始往正确时刻收敛(每次追 1/3), 低于它就当噪声不管。
         *
         * 25ms 的依据: 单次采样抖动通常也就几十毫秒, 门槛压在 25 能追得比较跟手;
         * 再往下调锚点会一直动, 扫色反而发飘。
         */
        private const val DRIFT_SOFT_MS = 25

        // folia 的 MONET_SCROLL_SPRING
        const val SPRING_STIFFNESS = 142f
        const val SPRING_DAMPING = 28f
        const val SPRING_MASS = 0.82f

        /** 惯性滑行的起步速度门槛(px/s), 低于它当作没甩 */
        const val FLING_MIN_PX = 220f

        /** 甩得再狠也不超过这个速度(行/秒) */
        const val FLING_MAX_ROWS = 26f

        /** 惯性每秒衰减到原来的这么多倍 */
        const val FLING_DECAY = 0.02f

        /** 速度低于这个(行/秒)就停 */
        const val FLING_STOP = 0.06f
    }
}
