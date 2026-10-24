package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Config
import com.portable.qqmusic.core.Crypto
import com.portable.qqmusic.core.Http
import com.portable.qqmusic.core.J
import com.portable.qqmusic.core.Log
import org.json.JSONArray
import org.json.JSONObject

/** 单个业务模块的返回 */
class Mod(val name: String, val code: Int, val data: JSONObject?) {
    val isOk: Boolean get() = code == 0 && data != null
}

/** 一次 musicu.fcg 调用的返回 */
class MusicUResponse(val root: JSONObject?, private val mods: Map<String, Mod>) {
    val code: Int = J.int(root, "code", -1)
    val msg: String = J.str(root, "msg", "")

    /** 取模块; 指定的 key 不存在时回退到默认请求键 req_0 */
    fun mod(name: String): Mod {
        mods[name]?.let { return it }
        mods[MusicU.DEFAULT_KEY]?.let { return it }
        return mods.values.firstOrNull() ?: Mod(name, -1, null)
    }

    fun data(name: String): JSONObject? = mod(name).data
}
/**
 * QQ 音乐模块化 CGI 网关 (musicu.fcg) 的客户端。
 *
 * 请求体格式 (与 QQ 音乐桌面端 / Mconnect 实现一致):
 * ```json
 * { "comm": { "ct": 19, "cv": 1845, "uin": "...", "format": "json" },
 *   "req_0": { "module": "music.search.SearchCgiService",
 *              "method": "DoSearchForQQMusicDesktop",
 *              "param": { ... } } }
 * ```
 *
 * 响应里模块数据挂在**与请求相同的键**下 (`req_0`), 形如:
 * `{"code":0,"req_0":{"code":0,"data":{...}}}`
 *
 * ⚠️ 早期实现用的是 `"request": {module,method,param}` 的数组格式,
 * 服务端虽然返回 200, 但模块数据取不到 —— 这是「所有业务接口都没数据」的根因。
 * 参考实现: github.com/Hjdd14/Mconnect-Music_connect (lib/platform/qq/qq_api.dart)
 */
object MusicU {

    /**
     * 当前登录态下的 comm 段。
     *
     * `ct`/`cv` 不是全局常量 —— 服务端会按它切换**同一个 module 的返回内容**:
     * 拿歌词举例, `ct=19`(桌面网页版) 只给逐行 LRC, 而逐字歌词 (QRC) 是 App 端
     * (`ct=24`) 才有的。默认值保持不变, 个别接口需要时再显式传。
     */
    fun comm(minimal: Boolean = false, ct: Int = 19, cv: Int = 1845): JSONObject {
        val c = JSONObject()
        c.put("ct", ct)
        c.put("cv", cv)
        val uin = Login.uin()
        c.put("uin", if (uin.isEmpty()) "0" else uin)
        c.put("format", "json")
        if (!minimal) {
            c.put("inCharset", "utf-8")
            c.put("outCharset", "utf-8")
            c.put("notice", 0)
            if (Login.isLoggedIn()) {
                c.put("g_tk", Crypto.gtk(Login.skey()))
                c.put("g_tk_new_20200303", Crypto.gtk(Login.skey()))
            }
        }
        return c
    }

    /**
     * 发起一次模块调用。
     *
     * @param module  模块名, 例如 music.search.SearchCgiService
     * @param method  方法名, 例如 DoSearchForQQMusicDesktop
     * @param param   业务参数
     * @param key     请求键名, 默认 `req_0`; 响应里同名键即模块数据
     * @param ct      客户端类型, 见 [comm] 的说明; 只有需要换返回内容的接口才传
     * @param cv      对应客户端版本号
     */
    fun call(
        module: String,
        method: String,
        param: JSONObject,
        key: String = DEFAULT_KEY,
        referer: String? = null,
        ct: Int = 19,
        cv: Int = 1845
    ): MusicUResponse {
        val request = JSONObject()
            .put("module", module)
            .put("method", method)
            .put("param", param)

        val body = JSONObject()
            .put("comm", comm(ct = ct, cv = cv))
            .put(key, request)

        val resp = Http.postJson(Config.API_MUSICU, body.toString(), referer = referer)
        Login.refreshCookies(resp.setCookies)
        if (!resp.isOk) {
            Log.w("MusicU", "http ${resp.code} for $module.$method")
            return MusicUResponse(null, emptyMap())
        }

        val root = J.obj(resp.body)
        if (root == null) {
            Log.w("MusicU", "non-json body for $module.$method")
            return MusicUResponse(null, emptyMap())
        }

        val mods = HashMap<String, Mod>(4)
        collect(root, key, module, mods)
        // 登录态失效时服务端会回 -10000(未登录) 或 1000(需要登录): 清凭证并提示重新登录
        if (Login.isLoggedIn() && isSessionExpired(root, mods)) Login.onSessionExpired()
        return MusicUResponse(root, mods)
    }

    /**
     * 一次请求里并发多个模块, 减少往返次数。
     * [items] 的 key 由调用方指定 (req_0 / req_1 / ...)。
     */
    fun callMulti(items: List<RequestItem>, referer: String? = null): MusicUResponse {
        if (items.isEmpty()) return MusicUResponse(null, emptyMap())
        if (items.size == 1) {
            val it = items[0]
            return call(it.module, it.method, it.param, it.key, referer)
        }
        val body = JSONObject().put("comm", comm())
        for (it in items) {
            body.put(
                it.key,
                JSONObject()
                    .put("module", it.module)
                    .put("method", it.method)
                    .put("param", it.param)
            )
        }
        val resp = Http.postJson(Config.API_MUSICU, body.toString(), referer = referer)
        Login.refreshCookies(resp.setCookies)
        val root = J.obj(resp.body) ?: return MusicUResponse(null, emptyMap())

        val mods = HashMap<String, Mod>(items.size * 2)
        for (it in items) collect(root, it.key, it.module, mods)
        if (Login.isLoggedIn() && isSessionExpired(root, mods)) Login.onSessionExpired()
        return MusicUResponse(root, mods)
    }

    const val DEFAULT_KEY = "req_0"

    data class RequestItem(val module: String, val method: String, val param: JSONObject, val key: String)

    /**
     * 这批响应是不是在说「登录失效」。
     *
     * 只用顶层 code / retcode 以及模块 code 里的 -10000 / 1000 判断 ——
     * 业务接口自己的错误码(比如歌单的 10004 参数错误)不参与, 免得误清登录态。
     */
    private fun isSessionExpired(root: JSONObject, mods: Map<String, Mod>): Boolean {
        val top = J.int(root, "code", 0)
        val ret = J.int(root, "retcode", 0)
        if (top == -10000 || top == 1000 || ret == -10000 || ret == 1000) return true
        return mods.values.any { it.code == -10000 }
    }

    /** 取出响应中与请求键同名的模块对象 */
    private fun collect(container: JSONObject?, key: String, module: String, out: MutableMap<String, Mod>) {
        if (container == null) return
        if (out.containsKey(key)) return
        val o = container.optJSONObject(key) ?: return
        if (o === container) return
        // 模块对象形如 { "code": 0, "data": {...} }; 个别接口 data 缺失时退回整个对象
        val data = o.optJSONObject("data") ?: o
        out[key] = Mod(module, J.int(o, "code", -1), data)
    }
}
