package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Config
import com.portable.qqmusic.core.CookieUtil
import com.portable.qqmusic.core.Crypto
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Http
import com.portable.qqmusic.core.J
import com.portable.qqmusic.core.Log
import org.json.JSONObject

/**
 * QQ 音乐二维码登录 (持久化登录)。
 *
 * 流程 (与网页版 y.qq.com 完全一致):
 *  1. GET  ptqrshow             -> 取二维码 PNG, 响应头里带 qrsig
 *  2. GET  ptqrlogin (轮询)     -> 65 未失效 / 66 未扫描 / 67 已扫描待确认 / 0 成功
 *  3. 成功后响应 Set-Cookie 带 QQ 互联票据, 把它合并进 cookie 池
 *  4. POST musicu.fcg QQConnectLogin.LoginServer/QQLogin -> 换取 QQ 音乐自己的 qm_keyst
 *  5. GetLoginUserInfo -> 昵称 / 头像 / 会员状态
 *
 * 登录结果整体写入 SharedPreferences, 下次启动自动恢复。
 */
object QrLogin {

    /** 登录流程状态 */
    enum class State { WAITING, SCANNED, CONFIRMED, EXPIRED, FAILED }

    class QrSession(
        val qrImage: ByteArray,
        val qrsig: String
    ) {
        val ptqrtoken: Long = Crypto.hash33(qrsig)
    }

    private const val PTLOGIN_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/98.0.4758.102 Safari/537.36"

    private const val PTLOGIN_REFERER = "https://xui.ptlogin2.qq.com/"

    private const val APPID = "716027609"
    private const val DAID = "383"
    private const val PT_3RD_AID = "100497308"

    // ---------------- 1. 申请二维码 ----------------

    fun requestQr(): QrSession? {
        val url = Config.API_QR +
            "?appid=716027609" +
            "&e=2" +
            "&l=M" +
            "&s=3" +
            "&d=72" +
            "&v=4" +
            "&t=0.${System.currentTimeMillis() % 100000}" +
            "&daid=383" +
            "&pt_3rd_aid=100497308"

        return try {
            val resp = fetchQrRaw(url)
            val cookies = CookieUtil.toMap(resp.cookies)
            val qrsig = cookies["qrsig"] ?: ""
            if (resp.bytes.isEmpty() || qrsig.isEmpty()) {
                Diag.e("QrLogin", "申请二维码失败: bytes=${resp.bytes.size} qrsig长度=${qrsig.length}")
                return null
            }
            Http.setCookies(CookieUtil.merge(Http.cookies(), mapOf("qrsig" to qrsig)))
            val token = Crypto.hash33(qrsig)
            val magic = resp.bytes.take(4).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
            Diag.i(
                "QrLogin",
                "二维码已申请(单次请求) qrsig=${qrsig.take(16)}... ptqrtoken=$token " +
                    "png=${resp.bytes.size}B 头4字节=$magic"
            )
            QrSession(resp.bytes, qrsig)
        } catch (t: Throwable) {
            Diag.e("QrLogin", "requestQr 异常", t)
            null
        }
    }

    /** 一次请求同时取回二维码图片与 Set-Cookie */
    private class QrRaw(val bytes: ByteArray, val cookies: List<String>)

    /**
     * 申请二维码 —— **必须只发一次请求**。
     *
     * 曾经这里先 `Http.get()` 拿 qrsig、再单独发一次请求拿图片:
     * 第二次请求会带上第一次的 qrsig cookie, 服务端于是生成**另一张**二维码返回。
     * 结果用户扫的是图片里的会话 B, 而轮询用的是会话 A 的 ptqrtoken,
     * 服务端永远回 `66`(二维码未被扫描), 表现为「扫码后一直不跳转」。
     */
    private fun fetchQrRaw(url: String): QrRaw {
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                useCaches = false
                // 关键: 申请二维码时不要带旧 cookie, 确保 qrsig 与图片同属一个会话
                setRequestProperty("User-Agent", PTLOGIN_UA)
                setRequestProperty("Referer", PTLOGIN_REFERER)
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Diag.e("QrLogin", "ptqrshow 返回 $code")
                return QrRaw(ByteArray(0), emptyList())
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            val cookies = conn.headerFields?.get("Set-Cookie") ?: emptyList()
            Diag.i("QrLogin", "ptqrshow -> $code, ${bytes.size}B, cookie=${cookies.size}")
            return QrRaw(bytes, cookies)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------- 2. 轮询扫码状态 ----------------

    class PollResult(val state: State, val redirectUrl: String = "")

    fun poll(session: QrSession): PollResult {
        val url = Config.API_QR_POLL +
            "?u1=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump" +
            "&ptqrtoken=${session.ptqrtoken}" +
            "&ptredirect=0" +
            "&h=1" +
            "&t=1" +
            "&g=1" +
            "&from_ui=1" +
            "&ptlang=2052" +
            "&action=0-0-${System.currentTimeMillis()}" +
            "&js_ver=20102616" +
            "&js_type=1" +
            "&login_sig=" +
            "&pt_uistyle=40" +
            "&aid=$APPID" +
            "&daid=$DAID" +
            "&pt_3rd_aid=$PT_3RD_AID" +
            "&has_onekey=1"

        return try {
            val resp = Http.get(
                url,
                extraHeaders = mapOf(
                    "User-Agent" to PTLOGIN_UA,
                    "Referer" to PTLOGIN_REFERER,
                    "Accept" to "*/*"
                ),
                referer = PTLOGIN_REFERER
            )
            // 轮询响应会下发新的 cookie (如 ptcz / uin), 及时合并
            Login.refreshCookies(resp.setCookies)

            // 响应形如: ptuiCB('66','0','','0','二维码未失效。','');
            val body = resp.body
            val code = extractCode(body)
            val target = if (code == "0") extractUrl(body) else ""
            Diag.i(
                "QrLogin",
                "poll# code=[$code] token=${session.ptqrtoken} body=[${body.take(140)}]" +
                    if (target.isNotEmpty()) " target=[${target.take(120)}]" else ""
            )

            when (code) {
                "0" -> {
                    if (target.isEmpty()) PollResult(State.FAILED)
                    else {
                        Login.refreshCookies(resp.setCookies)
                        PollResult(State.CONFIRMED, target)
                    }
                }
                "65" -> PollResult(State.EXPIRED)
                "66" -> PollResult(State.WAITING)
                "67" -> PollResult(State.SCANNED)
                else -> PollResult(State.FAILED)
            }
        } catch (t: Throwable) {
            Log.w("QrLogin.poll", t)
            PollResult(State.WAITING)
        }
    }

    private fun extractCode(body: String): String {
        val start = body.indexOf('\'')
        if (start < 0) return ""
        val end = body.indexOf('\'', start + 1)
        if (end < 0) return ""
        return body.substring(start + 1, end)
    }

    /**
     * 取出 ptuiCB 第三个参数里的跳转地址。
     *
     * 响应形如:
     * `ptuiCB('0','0','https://ssl.ptlogin2.graph.qq.com/check_sig?pttype=1&uin=...&ptsigx=...','0','登录成功！','')`
     *
     * 早期实现按「第 3、4 个单引号之间」取, 但这个 URL 里**不含单引号**,
     * 计数一旦偏一位就会取到 `","` 之类的垃圾值, 于是拿它去发请求 →
     * `MalformedURLException: no protocol: ,` → 登录流程被丢弃 → 重新刷新二维码。
     * 这就是「扫码后一直不跳转」的最后一道坎。改用正则直接匹配 URL。
     */
    private fun extractUrl(body: String): String {
        val m = Regex("(https?://[^'\"\\s]+)").find(body)
        return m?.value ?: ""
    }

    // ---------------- 3~5. 换取 QQ 音乐登录态 ----------------

    /**
     * 用 ptlogin 得到的票据换取 QQ 音乐的登录态。
     *
     * 关键: `graph.qq.com/oauth2.0/login_jump` 是**多级 302 跳转**,
     * 而 p_skey / skey 这些票据是在中间某一跳的 Set-Cookie 里下发的。
     * `HttpURLConnection` 自动跟随重定向时只保留最终响应的 Set-Cookie,
     * 中间票据会全部丢掉 —— 这就是「扫码后一直登录不上」的根因。
     * 因此这里手动逐跳跟随, 每一跳的 Set-Cookie 都立即并入 cookie 池。
     *
     * @return 成功时返回 uin
     */
    fun exchange(redirectUrl: String): String? {
        var url = redirectUrl.ifEmpty { "https://graph.qq.com/oauth2.0/login_jump" }

        // ---- 1. 手动跟随跳转链, 每一跳的 Set-Cookie 都并入 cookie 池 ----
        var hops = 0
        while (hops < MAX_REDIRECT_HOPS) {
            hops++
            val resp = Http.getNoRedirect(
                url,
                extraHeaders = mapOf("User-Agent" to PTLOGIN_UA, "Referer" to PTLOGIN_REFERER),
                referer = PTLOGIN_REFERER
            )
            val keys = CookieUtil.toMap(resp.setCookies).keys.joinToString(",")
            Diag.i("QrLogin", "跳转#$hops ${resp.code} cookie=[$keys]")

            val loc = resp.location
            if (resp.code !in 300..399 || loc.isNullOrEmpty()) break
            url = resolveUrl(url, loc)
        }

        // 立即把当前 cookie 池快照下来。
        // 后续 QQLogin 请求会经过 MusicU.call -> Login.refreshCookies,
        // 那一步可能改写全局池; 早先就是在这里让 p_skey 丢掉的。
        val snap = Http.cookies()
        val pSkeyRaw = CookieUtil.value(snap, "p_skey")
        val skeyRaw = CookieUtil.value(snap, "skey")
        val pSkey = pSkeyRaw.ifEmpty { skeyRaw }
        var uin = CookieUtil.value(snap, "uin")
            .ifEmpty { CookieUtil.value(snap, "p_uin") }
            .ifEmpty { CookieUtil.value(Login.cookie(), "p_uin") }
            .removePrefix("o")

        Diag.i(
            "QrLogin",
            "票据解析: snap长度=${snap.length}, p_skey长度=${pSkeyRaw.length}, skey长度=${skeyRaw.length}, " +
                "p_skey前8=[${pSkeyRaw.take(8)}], uin=[$uin]"
        )

        if (pSkey.isEmpty()) {
            Diag.e("QrLogin", "交换失败: 未拿到 p_skey/skey, 池内=[${cookieKeys(snap)}]")
            return null
        }
        Diag.i("QrLogin", "票据就绪: 使用 p_skey(${pSkey.length} 字), uin=[$uin]")

        // 显式确保全局池里带着票据, 不依赖之前的状态
        Http.setCookies(snap)

        // ---- 2. 用 p_skey 换 OAuth 授权码 ----
        //
        // 关键: QQLogin 要的参数是 OAuth 的 `code`, **不是 p_skey**!
        // 早期实现直接拿 p_skey 当参数传, 服务端只会回 code=1000 / 10006,
        // musicid/openid 全空 —— 这就是「扫码成功却始终登录不上」的原因。
        //
        // 正确流程 (参考 github.com/Hjdd14/Mconnect-Music_connect
        //           lib/platform/qq/qq_api.dart _exchangeAuthCodeForCookies):
        //   1) 顺着 check_sig 的跳转链拿到 p_skey
        //   2) g_tk = hash33(p_skey) % 1000000
        //   3) POST graph.qq.com/oauth2.0/authorize (带 cookie + Referer/Origin)
        //      从 302 的 Location 里取 code=
        //   4) QQLogin({ param: { code } })
        val gTk = Crypto.gtk(pSkey)
        val authCode = fetchAuthCode(pSkey, CookieUtil.value(snap, "skey"))
        if (authCode.isNullOrEmpty()) {
            Diag.e("QrLogin", "换取 OAuth 授权码失败, 无法完成登录")
            return null
        }
        Diag.i("QrLogin", "拿到授权码: 长度=${authCode.length} 前12=[${authCode.take(12)}]")

        // ---- 3. 用授权码换 QQ 音乐登录态 ----
        val loginBody = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("g_tk", gTk)
                    .put("platform", "yqq")
                    .put("ct", 24)
                    .put("cv", 0)
                    .put("uin", uin.ifEmpty { "0" })
                    .put("format", "json")
            )
            .put(
                LOGIN_REQ_KEY,
                JSONObject()
                    .put("module", "QQConnectLogin.LoginServer")
                    .put("method", "QQLogin")
                    .put("param", JSONObject().put("code", authCode))
            )

        val loginHttp = Http.postJson(com.portable.qqmusic.core.Config.API_MUSICU, loginBody.toString())
        Login.refreshCookies(loginHttp.setCookies)
        val loginRoot = J.obj(loginHttp.body)
        val loginReq = loginRoot?.optJSONObject(LOGIN_REQ_KEY) ?: loginRoot?.optJSONObject("req_0")
        val loginCode = J.int(loginReq, "code", -1)
        val loginData = loginReq?.optJSONObject("data")
        Diag.i(
            "QrLogin",
            "QQLogin -> 模块code=$loginCode, 有data=${loginData != null}, " +
                "musickey=${J.str(loginData, "musickey").length}字, " +
                "musicid=${J.str(loginData, "musicid")}"
        )

        if (loginCode != 0 || loginData == null) {
            Diag.e("QrLogin", "QQLogin 失败: code=$loginCode, 响应=[${loginHttp.body.take(300)}]")
            return null
        }

        // ---- 4. 用返回的 data 构造 QQ 音乐域 cookie ----
        val musicKey = J.str(loginData, "musickey")
            .ifEmpty { J.str(loginData, "music_key") }
            .ifEmpty { J.str(loginData, "qqmusic_key") }
        val musicUin = J.str(loginData, "musicid")
            .ifEmpty { J.str(loginData, "music_id") }
            .ifEmpty { uin }

        val built = LinkedHashMap<String, String>()
        cookiePairs(Http.cookies()).forEach { (k, v) -> built[k] = v }
        if (uin.isNotEmpty()) {
            built["uin"] = "o$uin"
            built["loginUin"] = uin
        }
        if (musicUin.isNotEmpty()) built["qqmusic_uin"] = musicUin
        if (musicKey.isNotEmpty()) {
            built["qqmusic_key"] = musicKey
            built["qm_keyst"] = musicKey
            built["musickey"] = musicKey
        }
        J.str(loginData, "openid").takeIf { it.isNotEmpty() }?.let { built["openid"] = it }
        J.str(loginData, "access_token").takeIf { it.isNotEmpty() }?.let { built["access_token"] = it }
        J.str(loginData, "refresh_key")
            .ifEmpty { J.str(loginData, "refresh_token") }
            .takeIf { it.isNotEmpty() }?.let { built["refresh_key"] = it }

        Http.setCookies(built.entries.joinToString("; ") { "${it.key}=${it.value}" })
        Diag.i(
            "QrLogin",
            "构造登录 cookie: qm_keyst=${built["qm_keyst"]?.length ?: 0}字, " +
                "键=[${built.keys.joinToString(",")}]"
        )

        // ---- 5. 补全 uin ----
        if (uin.isEmpty()) uin = musicUin
        if (uin.isEmpty()) {
            Diag.e("QrLogin", "交换失败: 无法确定 uin")
            return null
        }

        Login.save(uin, Http.cookies(), null)

        // ---- 6. 拉资料, 确认登录态真的生效 ----
        val profile = Login.refreshProfile()
        if (profile == null) {
            Diag.e("QrLogin", "cookie 已保存, 但 GetLoginUserInfo 失败, 登录态可能无效")
        } else {
            Diag.i("QrLogin", "登录成功 nick=${profile.nick} uin=${profile.uin} vip=${profile.vipType}")
        }
        return uin
    }

    /**
     * 用已登录的 QQ 互联会话换 OAuth 授权码。
     *
     * 步骤 (与 Mconnect `lib/platform/qq/qq_api.dart` 对齐):
     *  1. GET `oauth2.0/show` —— 建立会话; 若它直接 302 出 `code` 就省下后面所有步骤
     *  2. POST `oauth2.0/authorize` —— 从 302 的 Location 里取 `code=`
     *
     * ⚠️ 这里对 `g_tk` 做多候选尝试: QQ 对 g_tk 有两套算法
     * (`hash33(x) % 1000000` 与 `hash33(x) & 0x7FFFFFFF`), 且可能要用
     * p_skey 或 skey 去算, 文档缺失。一次扫码把所有组合试完, 命中即止。
     */
    private fun fetchAuthCode(pSkey: String, skey: String): String? {
        val state = System.currentTimeMillis() / 1000
        val redirectEncoded = java.net.URLEncoder.encode(OAUTH_REDIRECT_URI, "UTF-8")
        val authHeaders = mapOf(
            "Referer" to GRAPH_SHOW,
            "Origin" to "https://graph.qq.com"
        )
        val queryBase = "response_type=code&client_id=$PT_3RD_AID" +
            "&redirect_uri=$redirectEncoded" +
            "&scope=get_user_info&state=$state" +
            "&from_ptlogin=1&src=1&update_auth=1&openapi=1010"

        // ---- 步骤 1: GET show ----
        try {
            val showResp = Http.getNoRedirect(
                "$GRAPH_SHOW?which=Login&display=pc&$queryBase",
                extraHeaders = authHeaders,
                referer = GRAPH_SHOW
            )
            val hasForm = showResp.body.contains("<form")
            val hasLoginUi = showResp.body.contains("pt_login") || showResp.body.contains("loginbtn")
            Diag.i(
                "QrLogin",
                "show -> ${showResp.code}, 长度=${showResp.body.length}, " +
                    "含表单=$hasForm 含登录UI=$hasLoginUi, loc=${showResp.location?.take(120) ?: "-"}"
            )
            extractCodeParam(showResp.location)?.let { return it }
            extractCodeParam(showResp.body)?.let { return it }
        } catch (t: Throwable) {
            Diag.e("QrLogin", "show 失败", t)
        }

        // ---- 步骤 2: POST authorize, 遍历 g_tk 候选 ----
        val candidates = LinkedHashMap<String, Long>()
        candidates["hash33%1e6(p_skey)"] = Crypto.gtk(pSkey)
        candidates["hash33&0x7FFFFFFF(p_skey)"] = Crypto.hash33(pSkey)
        if (skey.isNotEmpty()) {
            candidates["hash33%1e6(skey)"] = Crypto.gtk(skey)
            candidates["hash33&0x7FFFFFFF(skey)"] = Crypto.hash33(skey)
        }
        candidates["5381"] = 5381L

        val formBase = "$queryBase&q_login_code=&q_state=&from=login"
        for ((label, gtk) in candidates) {
            if (gtk < 0) continue
            try {
                val resp = Http.postFormNoRedirect(
                    GRAPH_AUTHORIZE,
                    "$formBase&g_tk=$gtk",
                    extraHeaders = authHeaders,
                    referer = GRAPH_SHOW
                )
                val loc = resp.location ?: ""
                val err = Regex("error=(\\d+)").find(loc)?.groupValues?.get(1) ?: "-"
                Diag.i(
                    "QrLogin",
                    "authorize[$label g_tk=$gtk] -> ${resp.code} err=$err loc=${loc.take(130)}"
                )
                extractCodeParam(loc)?.let { return it }
                extractCodeParam(resp.body)?.let { return it }
            } catch (t: Throwable) {
                Diag.e("QrLogin", "authorize[$label] 失败", t)
            }
        }
        return null
    }

    /** 从 URL 或响应体里抠出 `code=` 参数 */
    private fun extractCodeParam(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val m = Regex("code=([^&\\s\"'<>]+)").find(text) ?: return null
        return m.groupValues[1].takeIf { it.isNotEmpty() }
    }

    private const val LOGIN_REQ_KEY = "req"
    private const val GRAPH_SHOW = "https://graph.qq.com/oauth2.0/show"
    private const val GRAPH_AUTHORIZE = "https://graph.qq.com/oauth2.0/authorize"
    private const val OAUTH_REDIRECT_URI = "https://y.qq.com/portal/wx_redirect.html?login_type=1"

    /** 把 cookie 串解析成 名->值, 供合并使用 */
    private fun cookiePairs(cookie: String): Map<String, String> {
        if (cookie.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (part in cookie.split(';')) {
            val p = part.trim()
            val idx = p.indexOf('=')
            if (idx <= 0) continue
            map[p.substring(0, idx)] = p.substring(idx + 1)
        }
        return map
    }

    /**
     * QQLogin 的参数候选组合。
     *
     * 该接口没有公开文档, 不同来源的实现传入的字段不完全一致。
     * 这里按「字段由少到多」列出几种, 调用方依次尝试, 命中 qm_keyst 即止。
     */
    private fun buildLoginAttempts(uin: String, pSkey: String, skey: String): List<JSONObject> {
        val gtk = Crypto.gtk(pSkey)
        val base = {
            JSONObject()
                .put("uin", uin.ifEmpty { "0" })
                .put("p_skey", pSkey)
                .put("skey", skey)
                .put("g_tk", gtk)
        }
        return listOf(
            // #1 最简: 只有票据
            base(),
            // #2 加 loginType
            base().put("loginType", 1),
            // #3 加 appid / daid / pt_3rd_aid
            base()
                .put("appid", APPID)
                .put("daid", DAID)
                .put("pt_3rd_aid", PT_3RD_AID),
            // #4 加 loginType 与 appid 全套
            base()
                .put("loginType", 1)
                .put("appid", APPID)
                .put("daid", DAID)
                .put("pt_3rd_aid", PT_3RD_AID)
                .put("login_mode", 1),
            // #5 附带 openid/access_token 占位, 部分实现会带上
            base()
                .put("loginType", 1)
                .put("appid", APPID)
                .put("openid", "")
                .put("access_token", "")
        )
    }

    private fun resolveUrl(base: String, location: String): String {
        if (location.startsWith("http")) return location
        return try {
            java.net.URL(java.net.URL(base), location).toString()
        } catch (t: Throwable) {
            location
        }
    }

    private fun cookieKeys(cookie: String): String =
        cookie.split(';')
            .mapNotNull { part -> part.trim().substringBefore('=').takeIf { it.isNotEmpty() } }
            .joinToString(",")
            .take(200)

    private const val MAX_REDIRECT_HOPS = 6

    private fun parseUinFromBody(body: String): String {
        // 形如 callback( {"uin":"123456"} );
        val idx = body.indexOf("\"uin\"")
        if (idx < 0) return ""
        val colon = body.indexOf(':', idx)
        if (colon < 0) return ""
        val quoteStart = body.indexOf('"', colon + 1)
        if (quoteStart < 0) return ""
        val quoteEnd = body.indexOf('"', quoteStart + 1)
        if (quoteEnd < 0) return ""
        return body.substring(quoteStart + 1, quoteEnd)
    }

    /** 登录后调用的完整收尾: 保存 cookie + 拉资料 */
    fun completeLogin(uin: String) {
        Login.save(uin, Http.cookies(), null)
        Login.refreshProfile()
    }

    /** 从 Set-Cookie 里挑出 QQ 音乐域的票据 (qm_keyst 等) */
    fun musicCookies(setCookies: List<String>): Map<String, String> {
        val all = CookieUtil.toMap(setCookies)
        val keep = listOf("qm_keyst", "skey", "p_skey", "uin", "qqmusic_key", "psrf_qqunionid")
        return all.filterKeys { k -> keep.any { it == k } }
    }

    /** 调试用: 读取一个 JSON 字段 */
    fun json(text: String): JSONObject? = J.obj(text)
}
