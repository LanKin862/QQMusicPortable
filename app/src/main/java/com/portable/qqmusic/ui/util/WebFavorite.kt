package com.portable.qqmusic.ui.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Diag
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.api.Login
import com.portable.qqmusic.data.api.Song
import com.portable.qqmusic.data.api.UserApi

/**
 * 「加入我喜欢」的兜底通道: 借一遍网页版。
 *
 * 明文 `musicu.fcg` 打 `PlaylistDetailWrite / AddSonglist { dirId: 201 }` 会被服务端回
 * `code=80105` —— 换任何参数形态 (单值/数组/带 songMid/带 uin/字符串 dirId)、任何
 * `ct`/`cv`、任何网关 (u.y / u6.y)、以及用 `qm_keyst` 或 `p_skey` 算出来的 `g_tk`
 * 都一样。这一条写入被单独挡掉了。
 *
 * 网页版能点, 是因为它在浏览器里走的是签名通道:
 * `u6.y.qq.com/cgi-bin/musics.fcg?encoding=ag-1&sign=...`, body 经 TmeWebSec 加密,
 * 那套签名是 VMP 混淆的, 复刻不了。
 *
 * 好在「让网页自己发那个请求」不需要复刻: 起一个 1×1 的隐形 WebView, 把 App 的登录态
 * 灌进去, 加载这首歌的详情页, 点掉页面上的「收藏」按钮, 签名交给页面自己算。
 */
object WebFavorite {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/98.0.4758.102 Safari/537.36 QQMusic/19.51.0"

    /** 页面 onPageFinished 之后还要等一会儿, 详情页的按钮是异步渲染出来的。 */
    private const val CLICK_DELAY_MS = 2_200L

    /** 点完到复查之间留出的时间, 页面要先跑完自己的签名请求。 */
    private const val VERIFY_DELAY_MS = 5_000L

    /** 兜底超时: 网页打不开时不能让回调永远悬着。 */
    private const val TIMEOUT_MS = 20_000L

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 点掉 `div.data__actions` 里文字是「收藏」的那颗按钮, 顺便把页面状态带回来。
     * `mine` / `loginBtn` / `uin` 只用于排查登录态。
     */
    private const val CLICK_JS = "(function(){" +
        "var r={clicked:false,count:0,label:'',mine:0,loginBtn:0,uin:''};" +
        "var as=document.querySelectorAll('div.data__actions a.mod_btn');" +
        "r.count=as.length;" +
        "r.mine=document.querySelectorAll('.mod_header__mine').length;" +
        "r.loginBtn=document.querySelectorAll('.mod_header__btn_login,a[href*=\"login.y.qq.com\"]').length;" +
        "var m=/uin=([^;]+)/.exec(document.cookie);r.uin=m?m[1]:'';" +
        "for(var i=0;i<as.length;i++){" +
        "var t=as[i].querySelector('.btn__txt');" +
        "var s=t?t.textContent.replace(/\\s/g,''):'';" +
        "if(s==='收藏'){as[i].click();r.clicked=true;r.label=s;break;}" +
        "if(s==='已收藏'){r.label='already';break;}}" +
        "return JSON.stringify(r);})()"

    /** 点完再读一次按钮文字, 看页面自己认不认这次点击。 */
    private const val STATE_JS = "(function(){" +
        "var as=document.querySelectorAll('div.data__actions a.mod_btn');" +
        "for(var i=0;i<as.length;i++){" +
        "var t=as[i].querySelector('.btn__txt');" +
        "var s=t?t.textContent.replace(/\\s/g,''):'';" +
        "if(s!==''){var c=as[i].querySelector('i');" +
        "if(c&&(c.className.indexOf('like')>=0))return s+'|'+c.className;}}" +
        "return 'none';})()"

    /**
     * 借网页版把 [song] 加进「我喜欢」。
     *
     * @param onDone 主线程回调, true 表示复查确认已经进去了。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun addFavorite(activity: Activity, song: Song, onDone: (Boolean) -> Unit) {
        if (song.mid.isEmpty() || activity.isFinishing) {
            onDone(false)
            return
        }
        val mid = song.mid
        val url = "https://y.qq.com/n/ryqq/songDetail/$mid"

        val web = WebView(activity)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_UA
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // 把 App 的登录 cookie 灌进 WebView, 网页才认得这个账号。
        // 先清一遍: WebView 的 cookie 是持久化的, 上一次注入的 `uin=o313...` 会留在
        // 里面, 和这次写的 `uin=313...` 并存, document.cookie 读到哪个就说不准了。
        // `uin` 还要转成纯数字 —— 网页版解析的是裸 uin, 带 o 前缀会被当成未登录。
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.removeAllCookies(null)
        cookies.flush()
        for (raw in Login.cookie().split(";")) {
            val kv = raw.trim()
            if (kv.isEmpty() || !kv.contains("=")) continue
            val cut = kv.indexOf('=')
            val name = kv.substring(0, cut)
            var value = kv.substring(cut + 1)
            if (name == "uin" || name == "p_uin" || name == "loginUin" || name == "qqmusic_uin") {
                value = value.removePrefix("o")
            }
            cookies.setCookie("https://y.qq.com", "$name=$value; path=/; domain=.qq.com")
        }
        cookies.flush()

        // 1×1 的隐形容器: 不显示, 但 WebView 需要有窗口才能跑 JS。
        val holder = FrameLayout(activity)
        holder.addView(web, FrameLayout.LayoutParams(1, 1))
        val dialog = Dialog(activity)
        dialog.setContentView(holder)
        dialog.setCancelable(false)
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(1, 1)
            w.setGravity(Gravity.TOP or Gravity.START)
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            // 不要 dim: 这个窗口只是为了给 WebView 一个容器, 用户不该看到屏幕暗一下。
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0f)
        }

        var settled = false

        fun finish(ok: Boolean) {
            if (settled) return
            settled = true
            handler.postDelayed({
                runCatching { holder.removeAllViews() }
                runCatching { web.stopLoading(); web.destroy() }
                runCatching { if (dialog.isShowing) dialog.dismiss() }
            }, 30L)
            onDone(ok)
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                handler.postDelayed({
                    view?.evaluateJavascript(CLICK_JS) { result ->
                        Diag.i("WebFavorite", "点击结果=$result url=$pageUrl")
                    }
                    handler.postDelayed({
                        view?.evaluateJavascript(STATE_JS) { state ->
                            Diag.i("WebFavorite", "点击后按钮=$state")
                        }
                    }, 3_500L)
                    handler.postDelayed({
                        // 页面自己发完请求后, 用单曲查询确认到底进去没有
                        // (翻 favoriteMids 要好几页, 太慢, 会误判成失败)。
                        Task.io {
                            val ok = runCatching {
                                UserApi.isSongFan(mid)
                            }.getOrElse {
                                Diag.e("WebFavorite", "复查收藏失败 mid=$mid", it)
                                false
                            }
                            Task.main {
                                Diag.i("WebFavorite", "mid=$mid 结果=$ok")
                                if (ok) {
                                    // 成功不打扰用户: 爱心在点下去时就已经翻过去了 (动画先行)。
                                    UserApi.markFavorite(mid, true)
                                } else {
                                    Tip.toast(
                                        activity,
                                        activity.getString(R.string.favorite_add_unsupported)
                                    )
                                }
                                finish(ok)
                            }
                        }
                    }, VERIFY_DELAY_MS)
                }, CLICK_DELAY_MS)
            }
        }
        web.loadUrl(url)
        dialog.show()

        // 兜底: 网页卡住 / 结构变了, 不要让回调永远悬着。
        handler.postDelayed({ finish(false) }, TIMEOUT_MS)
    }
}
