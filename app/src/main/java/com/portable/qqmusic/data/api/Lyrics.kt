package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Diag

/**
 * LRC 歌词解析。
 *
 * 支持 QQ 音乐返回的 LRC 文本格式（含一行多时间标签）：
 * ```
 * [ti:歌名]
 * [00:12.34]第一句
 * [00:20.10][01:05.00]重复的副歌
 * ```
 * 解析结果按时间升序，便于二分查找当前行。
 */
object Lyrics {

    /** 一行歌词 */
    data class Line(
        val timeMs: Int,
        val text: String,
        val transText: String = "",
        /**
         * 逐字时间轴。来自 QRC 而不是 LRC —— 有它就能做到「唱到哪个字亮到哪个字」。
         * 空表表示这首歌没有逐字数据, 扫色退回按整行百分比推进。
         */
        var words: List<Qrc.Word> = emptyList(),
        /**
         * 是不是「作词：xxx / 作曲：xxx / 编曲：xxx」这类制作信息。
         *
         * 这类行照样显示（官方客户端也显示），但它们没有真正被唱出来，
         * 不该当成当前行高亮 —— 否则歌一开头就是这两行在那儿亮。
         */
        val isMeta: Boolean = false,
    ) {
        /** 是否带翻译 */
        val hasTranslation: Boolean get() = transText.isNotEmpty()

        /** 是否有逐字时间轴 */
        val hasWords: Boolean get() = words.isNotEmpty()

        /** 本句总字数（逐字块里所有文字加起来） */
        val wordChars: Int get() = words.sumOf { it.text.length }
    }

    /** 解析结果 */
    class Document(
        val lines: List<Line>,
        /** 翻译歌词: 时间戳 -> 译文 */
        val translated: Map<Int, String> = emptyMap()
    ) {
        val isEmpty: Boolean get() = lines.isEmpty()

        val hasTranslation: Boolean get() = lines.any { it.hasTranslation }

        /**
         * 二分查找当前时间应显示的歌词行下标。
         * 返回 -1 表示还没到第一句。
         */
        fun indexAt(positionMs: Int): Int {
            if (lines.isEmpty()) return -1
            if (positionMs < lines[0].timeMs) return -1
            var low = 0
            var high = lines.size - 1
            var result = 0
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (lines[mid].timeMs <= positionMs) {
                    result = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            // 制作信息行照常参与跟唱 —— 它们本来就是有时间轴的歌词行, 显示和高亮都保留
            return result
        }
    }

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    // 元信息标签, 不参与显示
    private val META_TAG = Regex("^\\[(ti|ar|al|by|offset|kana|length):.*]$", RegexOption.IGNORE_CASE)

    fun parse(lrc: String?, translatedLrc: String? = null): Document {
        if (lrc.isNullOrBlank()) return Document(emptyList())
        var lines = parseLines(lrc)
        Diag.i(
            "Lyrics",
            "parse 入参=" + lrc.length + "字 解析出=" + lines.size + "行 头=" +
                lrc.take(100).replace("\n", "⏎")
        )
        if (lines.isEmpty()) {
            /*
             * 有些歌服务端给的 lyric 不是标准 LRC, 而是 QRC 的紧凑格式
             * `[0,14296]天(0,1787)国 (1787,1787)...`。那种格式按 [TIME_TAG] 一行都匹配不上,
             * 界面就是「暂无歌词」。这里退回 QRC 解析, 顺带把逐字时间轴也拿到了。
             */
            val qrc = Qrc.parseContent(lrc)
            if (qrc.isNotEmpty()) {
                Diag.i("Lyrics", "LRC 解析为空, 改用 QRC 紧凑格式 行数=${qrc.size}")
                lines = qrc.map { Line(it.timeMs, it.text, words = it.words, isMeta = isMetaLine(it.text)) }
            }
        }
        val trans = parseTranslation(translatedLrc)
        return Document(lines, trans)
    }

    /**
     * 直接拿 QRC 的行当歌词。
     *
     * 有些歌 LRC 那边会返回空（版权判断和 QRC 不是一套逻辑），但 QRC 有内容 ——
     * 这时候 QRC 就是唯一的信息源。逐字数据也一起挂上。
     */
    /** 只有斜杠、破折号、竖线、空白这类装饰, 没有任何实际文字 */
    private fun isDecoration(text: String): Boolean =
        text.all { it == '/' || it == '\\' || it == '｜' || it == '·' || it == '-' || it == '—' || it.isWhitespace() }

    /** 「作词：xxx」「Composed by: xxx」这类制作信息行 —— 显示但不参与跟唱 */
    private val META_LINE = Regex(
        "^\\s*(作词|作曲|词曲|词|曲|编曲|编配|制作人?|监制|出品|发行|混音|母带|录音|配唱|" +
            "和声|吉他|贝斯|鼓|键盘|弦乐|管乐|编程|策划|统筹|企划|原唱|翻唱|演唱|OP|SP)" +
            "\\s*[：:]" + "|" +
            "^\\s*(composed|lyrics?|music|written|produced|arranged|mixed|mastered|performed|" +
            "released|credits?|producer|guitars?|bass|drums?|strings?|keyboards?|vocals?|" +
            "programming|op|sp)\\s*(by\\s*)?[：:]",
        RegexOption.IGNORE_CASE
    )

    private fun isMetaLine(text: String): Boolean = META_LINE.containsMatchIn(text)

    fun fromQrc(qrcLines: List<Qrc.Line>, translatedLrc: String? = null): Document {
        if (qrcLines.isEmpty()) return Document(emptyList())
        val lines = qrcLines
            .filter { it.text.isNotBlank() && !isDecoration(it.text) }
            .map { Line(it.timeMs, it.text, words = it.words, isMeta = isMetaLine(it.text)) }
        // QRC 只有正文和逐字时间轴, 官方译文还是得从 LRC 那份 trans 里取
        val trans = parseTranslation(translatedLrc)
        val doc = Document(lines, trans)
        return if (trans.isEmpty()) doc else applyTranslation(doc)
    }

    /**
     * 把翻译拼接到每行歌词上 (翻译时间戳与原词一致时直接采用, 否则按 ±300ms 就近匹配)。
     * 返回新的 Document, 原对象不变。
     */
    fun applyTranslation(doc: Document): Document {
        if (doc.isEmpty || doc.translated.isEmpty()) return doc
        val lines = doc.lines.map { line ->
            /*
             * 制作信息行不贴译文。
             *
             * `nearestTranslation` 是 ±300ms 就近匹配, 而「编曲：xxx」这种行常常和
             * 它后面那句正文只差几十毫秒 —— 不挡掉的话, 正文的译文会同时挂到它下面,
             * 界面上看起来就是「编曲：xxx / 从此以后我们肩并肩」这种串行。
             */
            if (line.isMeta) return@map line
            val exact = doc.translated[line.timeMs]
            val text = exact ?: nearestTranslation(doc.translated, line.timeMs)
            if (text.isNullOrEmpty()) line else line.copy(transText = text)
        }
        return Document(lines, doc.translated)
    }

    private fun nearestTranslation(map: Map<Int, String>, timeMs: Int): String? {
        var best: String? = null
        var bestDelta = NEAREST_TOLERANCE_MS + 1
        for ((t, text) in map) {
            val delta = kotlin.math.abs(t - timeMs)
            if (delta < bestDelta) {
                bestDelta = delta
                best = text
            }
        }
        return if (bestDelta <= NEAREST_TOLERANCE_MS) best else null
    }

    private fun parseLines(raw: String): List<Line> {
        val out = ArrayList<Line>(64)
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || META_TAG.matches(line)) continue

            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) continue

            // 去掉所有时间标签后剩下的即歌词正文
            val text = line.replace(TIME_TAG, "").trim()
            if (text.isEmpty()) continue
            // LRC 里也会夹着 `//` 这种装饰行, 和 QRC 那个同源, 一并丢掉
            if (isDecoration(text)) continue
            val meta = isMetaLine(text)

            for (m in matches) {
                val minutes = m.groupValues[1].toIntOrNull() ?: continue
                val seconds = m.groupValues[2].toIntOrNull() ?: continue
                val fractionRaw = m.groupValues[3]
                // 支持 [mm:ss.xx] 与 [mm:ss.xxx] 两种精度
                val millis = when {
                    fractionRaw.isEmpty() -> 0
                    fractionRaw.length == 1 -> fractionRaw.toInt() * 100
                    fractionRaw.length == 2 -> fractionRaw.toInt() * 10
                    else -> fractionRaw.substring(0, 3).toInt()
                }
                out.add(Line(minutes * 60_000 + seconds * 1000 + millis, text, isMeta = meta))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** 翻译歌词同样是 LRC, 解析成 时间 -> 文本 */
    private fun parseTranslation(raw: String?): Map<Int, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val lines = parseLines(raw)
        if (lines.isEmpty()) return emptyMap()
        val map = HashMap<Int, String>(lines.size)
        for (l in lines) map[l.timeMs] = l.text
        return map
    }

    /** 时间毫秒 -> mm:ss, 用于纯音乐提示等场景 */
    fun formatTime(ms: Int): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** 翻译与原词允许的最大时间偏差 */
    private const val NEAREST_TOLERANCE_MS = 300
}
