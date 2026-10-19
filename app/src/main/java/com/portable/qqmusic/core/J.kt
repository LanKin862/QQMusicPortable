package com.portable.qqmusic.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简 JSON 读取工具。
 *
 * 直接使用 Android 平台内置的 org.json (零额外依赖、零反射),
 * 这里只补齐空安全与默认值访问, 避免业务层到处写 try/catch。
 */
object J {

    fun obj(text: String?): JSONObject? = try {
        if (text.isNullOrBlank()) null else JSONObject(text)
    } catch (t: Throwable) {
        null
    }

    fun obj(o: JSONObject?, key: String): JSONObject? = o?.optJSONObject(key)

    fun arr(o: JSONObject?, key: String): JSONArray? = o?.optJSONArray(key)

    fun objAt(a: JSONArray?, index: Int): JSONObject? = a?.optJSONObject(index)

    fun str(o: JSONObject?, key: String, def: String = ""): String =
        o?.optString(key, def) ?: def

    fun strOrNull(o: JSONObject?, key: String): String? {
        val v = o?.optString(key, "") ?: return null
        return if (v.isEmpty() || v == "null") null else v
    }

    fun long(o: JSONObject?, key: String, def: Long = 0L): Long =
        o?.optLong(key, def) ?: def

    fun int(o: JSONObject?, key: String, def: Int = 0): Int =
        o?.optInt(key, def) ?: def

    fun bool(o: JSONObject?, key: String, def: Boolean = false): Boolean =
        o?.optBoolean(key, def) ?: def

    /** 兼容 "1" / 1 / true 三种布尔表达 */
    fun boolLoose(o: JSONObject?, key: String, def: Boolean = false): Boolean {
        val v = o?.opt(key) ?: return def
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "1" || v.equals("true", true)
            else -> def
        }
    }

    fun stringList(o: JSONObject?, key: String): List<String> {
        val a = o?.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val s = a.optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** 深度取字段: path("a.b.c") */
    fun path(root: JSONObject?, path: String): JSONObject? {
        if (root == null) return null
        var cur: JSONObject = root
        val parts = path.split('.')
        for ((i, p) in parts.withIndex()) {
            if (i == parts.size - 1) return cur.optJSONObject(p)
            cur = cur.optJSONObject(p) ?: return null
        }
        return null
    }
}
