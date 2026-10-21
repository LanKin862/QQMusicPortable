package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Config
import com.portable.qqmusic.core.CookieUtil
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Http
import com.portable.qqmusic.core.J
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Store
import org.json.JSONObject

/**
 * 登录态管理。
 *
 * 持久化策略: 登录成功后把完整 cookie 串写入 SharedPreferences,
 * 之后每次请求自动带上, 并通过响应里的 Set-Cookie 增量刷新
 * (QQ 音乐的 qm_keyst / skey 会定期轮换)。
 */
object Login {

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        val cookie = Store.str(Store.K.COOKIE)
        if (cookie.isNotEmpty()) {
            val normalized = normalizeCookie(cookie)
            Http.setCookies(normalized)
            if (normalized != cookie) Store.putStr(Store.K.COOKIE, normalized)
        }
    }

    /**
     * 补全 cookie 里 QQ 音乐接口需要的派生字段。
     *
     * 从网页版 DevTools 复制出来的 cookie 往往只有 `qm_keyst` 和 `uin`,
     * 但收藏 / 歌单这类接口还认 `loginUin`、`qqmusic_uin`、`qqmusic_key`,
     * 缺了它们会返回 40000 之类的业务错误。
     * 这里按 Mconnect `_buildMusicLoginCookie` 的做法把等价字段补齐, 幂等。
     */
    fun normalizeCookie(raw: String): String {
        if (raw.isEmpty()) return raw
        val map = LinkedHashMap<String, String>()
        for (part in raw.split(';')) {
            val p = part.trim()
            val idx = p.indexOf('=')
            if (idx <= 0) continue
            map[p.substring(0, idx)] = p.substring(idx + 1)
        }

        val digits = (map["uin"] ?: map["p_uin"] ?: map["qqmusic_uin"] ?: map["loginUin"] ?: "")
            .removePrefix("o")
            .filter { it.isDigit() }
        if (digits.isNotEmpty()) {
            map["uin"] = "o$digits"
            map["p_uin"] = "o$digits"
            if (map["loginUin"].isNullOrEmpty()) map["loginUin"] = digits
            if (map["qqmusic_uin"].isNullOrEmpty()) map["qqmusic_uin"] = digits
        }

        val key = map["qm_keyst"].orEmpty()
            .ifEmpty { map["qqmusic_key"].orEmpty() }
            .ifEmpty { map["musickey"].orEmpty() }
        if (key.isNotEmpty()) {
            map["qm_keyst"] = key
            if (map["qqmusic_key"].isNullOrEmpty()) map["qqmusic_key"] = key
            if (map["musickey"].isNullOrEmpty()) map["musickey"] = key
        }

        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun isLoggedIn(): Boolean = uin().isNotEmpty() && Store.str(Store.K.COOKIE).isNotEmpty()

    fun uin(): String = Store.str(Store.K.UIN)

    fun nick(): String = Store.str(Store.K.NICK)

    fun avatar(): String = Store.str(Store.K.AVATAR)

    fun vipType(): Int = Store.int(Store.K.VIP)

    fun cookie(): String = Store.str(Store.K.COOKIE)

    val isVip: Boolean get() = vipType() > 0

    /** 从 cookie 串里取某个键 */
    fun cookieValue(name: String): String {
        val c = cookie()
        if (c.isEmpty()) return ""
        for (part in c.split(';')) {
            val p = part.trim()
            val idx = p.indexOf('=')
            if (idx <= 0) continue
            if (p.substring(0, idx) == name) return p.substring(idx + 1)
        }
        return ""
    }

    /**
     * 算 g_tk 用的票据。
     *
     * 必须优先 `qm_keyst` / `qqmusic_key`: QQ 音乐服务端校验的是这套票据算出来的 g_tk。
     * 早先优先取 `p_skey`(QQ 空间的), 于是给所有带 g_tk 的写接口送了一个对不上的 CSRF
     * token —— 写「我喜欢」恒回 `80105` 就是这个原因。`p_skey` 只作为最后兜底。
     */
    fun skey(): String =
        cookieValue("qm_keyst")
            .ifEmpty { cookieValue("qqmusic_key") }
            .ifEmpty { cookieValue("p_skey") }
            .ifEmpty { cookieValue("skey") }

    /** 保存登录结果 */
    fun save(uin: String, cookie: String, profile: UserProfile?) {
        // 换账号: 上一个账号的歌单/收藏缓存不能留, 否则会先闪一下别人的数据
        val prevUin = Store.str(Store.K.UIN, "")
        if (prevUin.isNotEmpty() && prevUin != uin) {
            com.portable.qqmusic.App.instance?.let { com.portable.qqmusic.data.MetaCache.clearAll(it) }
        }
        Store.putStr(Store.K.UIN, uin)
        Store.putStr(Store.K.COOKIE, cookie)
        Store.putLong(Store.K.LOGIN_TIME, System.currentTimeMillis())
        Http.setCookies(cookie)
        if (profile != null) {
            Store.putStr(Store.K.NICK, profile.nick)
            Store.putStr(Store.K.AVATAR, profile.avatar)
            Store.putInt(Store.K.VIP, profile.vipType)
        }
    }

    /**
     * 每次网络返回后调用: 把响应中的 Set-Cookie 合并进全局池,
     * 若与已持久化的不同则写回磁盘。
     */
    @Synchronized
    fun refreshCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val before = Http.cookies()
        Http.mergeCookies(setCookies)
        val after = Http.cookies()
        if (after != before) {
            Store.putStr(Store.K.COOKIE, after)
            Log.d("Login", "cookies refreshed")
        }
    }

    fun logout() {
        Store.remove(Store.K.COOKIE)
        Store.remove(Store.K.UIN)
        Store.remove(Store.K.NICK)
        Store.remove(Store.K.AVATAR)
        Store.remove(Store.K.VIP)
        Http.setCookies("")
        // 退出后不能再把上一个账号的歌单摆在界面上
        com.portable.qqmusic.App.instance?.let { com.portable.qqmusic.data.MetaCache.clearAll(it) }
    }


    /** 拉取并刷新用户资料 (昵称/头像/会员状态) */
    fun refreshProfile(): UserProfile? {
        if (!isLoggedIn()) {
            Diag.i("Login", "refreshProfile 跳过: 未登录 (uin空=${uin().isEmpty()}, cookie空=${cookie().isEmpty()})")
            return null
        }
        val param = JSONObject()
            .put("uin", uin().toLongOrNull() ?: 0L)
            .put("euin", "")
            .put("screen", 1080)
        val resp = MusicU.call("music.UserInfo.userInfoServer", "GetLoginUserInfo", param, "profile")
        val raw = resp.root?.toString() ?: "null"
        Diag.i(
            "Login",
            "GetLoginUserInfo 顶层code=${resp.code} 模块code=${resp.mod("profile").code} " +
                "data=${resp.data("profile") != null}"
        )
        Diag.i("Login", "GetLoginUserInfo 原始响应(前 400)=[${raw.take(400)}]")

        val profile = UserProfile.fromJson(resp.data("profile"))
        if (profile != null) {
            Store.putStr(Store.K.NICK, profile.nick)
            Store.putStr(Store.K.AVATAR, profile.avatar)
            Store.putInt(Store.K.VIP, profile.vipType)
        }
        return profile
    }

    /** 歌单封面等 CDN 有时需要带上 uin 参数 */
    fun appendUin(url: String): String =
        if (url.isEmpty() || !isLoggedIn()) url
        else if (url.contains("?")) "$url&uin=${uin()}"
        else "$url?uin=${uin()}"

    val referer: String get() = Config.REFERER
}
