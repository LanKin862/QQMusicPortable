package com.portable.qqmusic.core

/**
 * 全局常量与端点配置。
 *
 * 说明: 本项目使用 QQ 音乐官方 WEB / 桌面客户端对外提供的 HTTP 接口 (u.y.qq.com / c.y.qq.com)。
 * 所有接口均为未公开文档的官方接口, 随服务端调整可能失效, 相关适配集中在 [com.portable.qqmusic.data.api] 包内。
 */
object Config {

    const val APP_NAME = "轻音"

    // ---------------- 端点 ----------------
    /** 主接口: 模块化 CGI 网关, 所有业务请求都走这里 */
    const val API_MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    /** 旧版 CGI, 二维码登录等少数接口仍在使用 */
    const val API_CGI = "https://c.y.qq.com"
    /** 二维码图片 */
    const val API_QR = "https://ssl.ptlogin2.qq.com/ptqrshow"
    /** 轮询二维码扫码状态 */
    const val API_QR_POLL = "https://ssl.ptlogin2.qq.com/ptqrlogin"
    /** 登录票据交换 (ptlogin 成功后获取 QQ 音乐自己的 cookie) */
    const val API_LOGIN_QQ = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    // ---------------- 图片 CDN ----------------
    const val IMG_SONG = "https://y.gtimg.cn/music/photo_new/T002R500x500M000%s.jpg"
    const val IMG_SINGER = "https://y.gtimg.cn/music/photo_new/T001R500x500M000%s.jpg"
    const val IMG_PLAYLIST = "https://y.gtimg.cn/music/photo_new/T002R500x500M000%s.jpg"
    /** 歌单封面专用 CDN (需要 cover 字段拼接 url 时使用) */
    const val IMG_PLAYLIST_LOGO = "https://qpic.y.qq.com/music_cover/%s/300?n=1"

    // ---------------- 伪装客户端 ----------------
    /** 桌面客户端 UA, 携带桌面标识才能拿到 Hi-Res / 母带 档位的 vkey */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/98.0.4758.102 Safari/537.36 QQMusic/19.51.0"

    const val REFERER = "https://y.qq.com/"

    // ---------------- 文件扩展名 ----------------
    const val EXT_MP3 = ".mp3"
    const val EXT_FLAC = ".flac"

    // ---------------- 请求文件名后缀 (决定服务端返回的码率/格式) ----------------
    const val PREFIX_M4A = "M500"
    const val PREFIX_MP3 = "M800"
    const val PREFIX_FLAC = "F000"
    const val PREFIX_HIRES = "RS01"
}
