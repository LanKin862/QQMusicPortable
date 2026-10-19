package com.portable.qqmusic.core

import java.security.MessageDigest
import java.util.Random

/**
 * QQ 音乐接口签名 / 票据相关算法。
 *
 * 这些算法是社区长期逆向得到的公开实现 (hash33 / zzcSign / g_tk),
 * 服务端一旦调整即会失效, 全部集中在此文件便于维护。
 */
object Crypto {

    private const val QQ_SALT = "zzb@qq.com"

    /**
     * hash33: 二维码轮询 (ptqrtoken) 与 skey -> g_tk 都基于它。
     * 等价 JavaScript 实现:
     *   for (i = 0, e = 0; i < t.length; ++i) e += (e << 5) + t.charCodeAt(i);
     *   return 2147483647 & e;
     *
     * **每一步都要 `& 0x7FFFFFFF`**: JS 的 `e << 5` 是 32 位运算, 中间值始终被截在
     * 32 位内; 若用 Long 一路不截断, `e` 会按 33 的幂次指数膨胀, 101 个字符就把 Long
     * 撑溢出, 结果完全没有意义。截断在模 2^31 下与「最后才取低位」等价, 但必须每步做。
     */
    fun hash33(text: String): Long {
        var e = 0L
        for (ch in text) {
            e = (e + (e shl 5) + ch.code) and 0x7FFFFFFFL
        }
        return e
    }

    /**
     * skey -> g_tk (QQ 空间/音乐通用)。
     *
     * **不能取模**: 网页版请求里 `g_tk` 与 `g_tk_new_20200303` 都是 hash33 的完整值
     * (实测 `qm_keyst` → `759472775`)。早先这里多算了一个 `% 1000000`, 导致写「我喜欢」
     * 这类会校验 g_tk 的接口一律回 `80105`。
     */
    fun gtk(skey: String): Long = hash33(skey)

    /**
     * 每日推荐 (雷达) 签名。
     * zzcSign = md5( data + md5(data + salt) ) 的小写十六进制。
     */
    fun zzcSign(data: String): String {
        val inner = md5(data + QQ_SALT)
        return md5(data + inner)
    }

    /** 旧版 fcgi 接口使用的 sign: md5("zza" + 排序后的 query) 的小写十六进制 */
    fun zzaSign(sortedQuery: String): String = md5("zza" + sortedQuery)

    private val HEX = "0123456789abcdef".toCharArray()

    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun md5Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("MD5").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** data = 16 进制随机串, 与 QQ 客户端本地生成方式一致 */
    fun randomHex(len: Int): String {
        val r = Random()
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append(HEX[r.nextInt(16)])
        return sb.toString()
    }

    /** QQ 号 -> 带前导零的 uin (接口要求 10 位) */
    fun formatUin(uin: String): String {
        val digits = uin.filter { it.isDigit() }
        if (digits.isEmpty()) return uin
        return if (digits.length >= 10) digits else "0".repeat(10 - digits.length) + digits
    }
}
