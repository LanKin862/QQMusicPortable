package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Diag

/**
 * QRC 逐字歌词的解析。
 *
 * 解密出来的是一段 XML：
 *
 * ```xml
 * <?xml version="1.0" encoding="utf-8"?>
 * <QrcInfos>
 *   <QrcHeadInfo SaveTime="..." Version="100"/>
 *   <LyricInfo LyricCount="1">
 *     <Lyric_1 LyricType="1" LyricContent="[ti:歌名]\n[0:12.345]第(0,300)一(300,200)句(500,400)"/>
 *   </LyricInfo>
 * </QrcInfos>
 * ```
 *
 * `LyricContent` 里每行是 `[mm:ss.mmm]正文`，正文每个字后面跟一对 `(起点偏移, 时长)`，
 * 单位毫秒、相对本行开头 —— 这就是「跟词」要的逐字时间轴。
 */
object Qrc {

    /** 一行正文里的一小块：文字 + 它相对**行首**的起止（毫秒） */
    class Word(val text: String, var startMs: Int, val durationMs: Int)

    /** 一行歌词 */
    class Line(
        val timeMs: Int,
        val text: String,
        val words: List<Word>
    )

    /**
     * 解析解密后的 QRC 明文。拿不到 `LyricContent` 就返回空表。
     */
    fun parse(xml: String): List<Line> {
        val content = extractContent(xml) ?: run {
            Diag.e("Qrc", "没找到 LyricContent, 前 120 字: " + xml.take(120))
            return emptyList()
        }
        Diag.i("Qrc", "content 长度=" + content.length + " 头=" + content.take(160).replace("\n", "⏎"))
        return parseContent(content)
    }

    /**
     * 直接解析 `LyricContent` 那段文本（不带 XML 外壳）。
     *
     * 有些歌的 `lyric` 字段返回的就是这种紧凑格式 `[0,14296]天(0,1787)国 ...`，
     * 而不是标准 LRC —— 那种情况下 [Lyrics.parse] 一行都解析不出来，得从这里兜。
     */
    fun parseContent(content: String): List<Line> {
        val out = ArrayList<Line>()
        for (rawLine in content.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parsed = parseLine(line) ?: continue
            out.add(parsed)
        }
        Diag.i("Qrc", "解析完成 行数=${out.size} 首个逐字行字数=" + (out.firstOrNull { it.words.isNotEmpty() }?.words?.size ?: 0))
        // 排查偏移语义: 打印前三行的行首时间和前两个词的偏移, 看词偏移是相对行首还是相对整首
        for (k in 0 until minOf(3, out.size)) {
            val l = out[k]
            val w = l.words.take(3).joinToString(" ") { "${it.text.trim()}@${it.startMs}+${it.durationMs}" }
            Diag.i("Qrc", "行$k time=${l.timeMs} text=${l.text.take(16)} words=[$w]")
        }
        return out
    }

    /** 从 XML 里抠出 `LyricContent` 属性并反转义 */
    private fun extractContent(xml: String): String? {
        val key = "LyricContent=\""
        val start = xml.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = xml.indexOf('"', from)
        if (end < 0) return null
        return xml.substring(from, end)
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("\\n", "\n")
    }

    private val WORD = Regex("""([^(]*?)\((\d+),(\d+)\)""")

    /**
     * 解析一行。
     *
     * QQ 这边实际下发的 QRC 行首是 **`[起始毫秒, 总时长]`** 这种紧凑写法，不是 LRC 的
     * `[mm:ss.xxx]` —— 文件头里 `by:krc转qrc工具` 就是痕迹。同一份文件里还混着
     * `[ti:]` `[ar:]` `[kana:]` 这类元信息行，所以不能用一个死正则去套。
     */
    private fun parseLine(line: String): Line? {
        if (!line.startsWith("[")) return null
        val close = line.indexOf(']')
        if (close <= 1) return null
        val inner = line.substring(1, close)

        // 形态一: [起始毫秒, 总时长]
        val timeMs: Int
        val comma = inner.indexOf(',')
        if (comma > 0) {
            val start = inner.substring(0, comma).toIntOrNull() ?: return null
            val dur = inner.substring(comma + 1).toIntOrNull() ?: return null
            if (dur <= 0) return null
            timeMs = start
        } else {
            // 形态二: [mm:ss.xxx] —— 老一点的数据还会这么给
            val colon = inner.indexOf(':')
            if (colon <= 0) return null
            val minutes = inner.substring(0, colon).toIntOrNull() ?: return null
            val rest = inner.substring(colon + 1)
            val dot = rest.indexOf('.')
            if (dot <= 0) return null
            val seconds = rest.substring(0, dot).toIntOrNull() ?: return null
            val fracRaw = rest.substring(dot + 1)
            if (fracRaw.isEmpty() || fracRaw.any { it !in '0'..'9' }) return null
            val frac = when (fracRaw.length) {
                3 -> fracRaw.toInt()
                2 -> fracRaw.toInt() * 10
                1 -> fracRaw.toInt() * 100
                else -> return null
            }
            timeMs = minutes * 60_000 + seconds * 1_000 + frac
        }
        val body = line.substring(close + 1)
        if (body.isEmpty()) return Line(timeMs, "", emptyList())

        val words = ArrayList<Word>()
        val plain = StringBuilder()
        for (m in WORD.findAll(body)) {
            val text = m.groupValues[1]
            val offset = m.groupValues[2].toIntOrNull() ?: 0
            val duration = m.groupValues[3].toIntOrNull() ?: 0
            if (text.isNotEmpty()) {
                words.add(Word(text, offset, duration))
                plain.append(text)
            }
        }
        // 有些不带时间戳的尾巴（比如纯 "(0,0)" 之外的文字）漏在正则外面，补回文本
        if (plain.isEmpty()) plain.append(body)
        val text = plain.toString()
        /*
         * 丢掉纯装饰行。
         *
         * 服务端这批数据是 krc 转 qrc 转出来的（文件头就写着 `by:krc转qrc工具`），
         * 转换时会在每段注释后面留一行 `//`，它带自己的时间戳，看起来就是一条歌词 ——
         * 表现成「词：xxx」下面多出一个「//」。这类行没有意义，直接不出现在列表里。
         */
        if (text.isBlank()) return null
        if (text.all { it == '/' || it == '\\' || it == '｜' || it == '·' || it == '-' || it == '—' || it.isWhitespace() }) {
            return null
        }
        normalizeOffsets(timeMs, words)
        return Line(timeMs, text, words)
    }

    /**
     * 把词偏移统一成「相对行首」。
     *
     * 这里踩过一次大坑: 服务端下发的 `(offset,duration)` 其实是**相对整首歌**的绝对毫秒,
     * 不是相对本行 —— `[2913,2912]Lyrics (2913,416)by (3329,416)` 里行首是 2913、首词偏移也是 2913。
     * 当时按「行首 + 偏移」算, 第二行的起点就变成了 5826, 永远是「还没唱到」,
     * 表现就是**只有第一句有扫色**, 后面每一句都不亮。
     *
     * 两种形态都要兼容, 靠「首词偏移与行首时间的距离」判断:
     * - 差值很小(<= [ABSOLUTE_TOLERANCE_MS]) → 是绝对时间, 减掉行首
     * - 差得多 → 本来就是相对偏移, 不动
     */
    private fun normalizeOffsets(timeMs: Int, words: MutableList<Word>) {
        val first = words.firstOrNull()?.startMs ?: return
        if (kotlin.math.abs(first - timeMs) > ABSOLUTE_TOLERANCE_MS) return
        for (w in words) {
            w.startMs = (w.startMs - timeMs).coerceAtLeast(0)
        }
    }

    /** 判断「词偏移是绝对时间还是相对行首」的容差 */
    private const val ABSOLUTE_TOLERANCE_MS = 600
}
