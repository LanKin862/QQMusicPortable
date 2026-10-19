package com.portable.qqmusic.core

/** QQ 音乐登录态相关的 cookie 工具 */
object CookieUtil {

    /** 把 Set-Cookie 响应头解析成 名->值 的有序表 (只取第一个 name=value 段) */
    fun toMap(setCookies: List<String>): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in setCookies) {
            // 一个 header 里可能塞了多个 cookie, 用 ", name=" 边界切分
            for (piece in raw.split(Regex(",(?=\\s*[A-Za-z0-9_\\-]+=)"))) {
                val first = piece.split(';').firstOrNull()?.trim() ?: continue
                val idx = first.indexOf('=')
                if (idx <= 0) continue
                val name = first.substring(0, idx).trim()
                val value = first.substring(idx + 1).trim()
                if (name.isNotEmpty()) map[name] = value
            }
        }
        return map
    }

    /** 合并 cookie 串: [extra] 覆盖同名键 */
    fun merge(base: String, extra: Map<String, String>): String {
        val map = LinkedHashMap<String, String>()
        if (base.isNotEmpty()) {
            for (part in base.split(';')) {
                val p = part.trim()
                val idx = p.indexOf('=')
                if (idx <= 0) continue
                map[p.substring(0, idx)] = p.substring(idx + 1)
            }
        }
        map.putAll(extra)
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun value(cookie: String, name: String): String {
        if (cookie.isEmpty()) return ""
        for (part in cookie.split(';')) {
            val p = part.trim()
            val idx = p.indexOf('=')
            if (idx <= 0) continue
            if (p.substring(0, idx) == name) return p.substring(idx + 1)
        }
        return ""
    }

    /** 拼接 / 覆盖 URL 上的查询参数 (登录成功后需要往跳转链接追加 qm_keyst 等票据) */
    fun appendQuery(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val hashIndex = url.indexOf('#')
        val base = if (hashIndex >= 0) url.substring(0, hashIndex) else url
        val frag = if (hashIndex >= 0) url.substring(hashIndex) else ""
        val sb = StringBuilder(base)
        var first = !base.contains('?')
        for ((k, v) in params) {
            sb.append(if (first) '?' else '&')
            first = false
            sb.append(k).append('=').append(v)
        }
        return sb.toString() + frag
    }
}
