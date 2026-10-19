package com.portable.qqmusic.data.api

import com.portable.qqmusic.core.Diag
import java.util.zip.Inflater

/**
 * QQ 音乐 QRC 歌词解密。
 *
 * 流程（和 luren-dc/QQMusicApi 的 `algorithms/__init__.py::qrc_decrypt` 一致）：
 *
 * 1. 密文按 8 字节分块，逐块过 [QrcDes] 的**定制版** 3DES（ECB 模式，无 IV）
 * 2. 解出来的字节流是 zlib 压缩的
 * 3. 解压出来就是带逐字时间轴的 QRC 明文
 *
 * 密钥是固定常量，不是从接口拿的。
 */
object QrcDecrypt {

    /** QRC 固定的 24 字节 3DES 密钥 */
    private val KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    /**
     * 解密 [cipher]。能吃两种常见表示：
     * - hex 字符串（长度偶数、全是 0-9a-f）
     * - base64 字符串
     *
     * 失败返回 null，调用方按「没有逐字歌词」处理。
     */
    fun decrypt(cipher: String): String? {
        if (cipher.isEmpty()) return null
        return try {
            val raw = decodeBytes(cipher) ?: return null
            if (raw.isEmpty() || raw.size % 8 != 0) {
                Diag.e("QrcDecrypt", "长度不是 8 的倍数: ${raw.size}")
                return null
            }
            val schedule = QrcDes.keySetup(KEY, QrcDes.DECRYPT)
            val plain = ByteArray(raw.size)
            val block = ByteArray(8)
            var offset = 0
            while (offset < raw.size) {
                System.arraycopy(raw, offset, block, 0, 8)
                val out = QrcDes.tripleCrypt(block, schedule)
                System.arraycopy(out, 0, plain, offset, 8)
                offset += 8
            }
            inflate(plain)
        } catch (e: Throwable) {
            Diag.e("QrcDecrypt", "解密失败: ${e.message}")
            null
        }
    }

    private fun decodeBytes(cipher: String): ByteArray? {
        // 优先按 hex：QRC 接口返回的就是纯十六进制
        if (cipher.length % 2 == 0 && cipher.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return ByteArray(cipher.length / 2) {
                ((hexValue(cipher[it * 2]) shl 4) or hexValue(cipher[it * 2 + 1])).toByte()
            }
        }
        return try {
            android.util.Base64.decode(cipher, android.util.Base64.DEFAULT)
        } catch (e: Throwable) {
            null
        }
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> c - 'A' + 10
    }

    /** zlib 解压。密文里可能带尾部填充，所以按需一块块喂 */
    private fun inflate(data: ByteArray): String? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    // 输入吃完了还没结束，或者需要一个字典 —— 都算失败
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                out.write(buf, 0, n)
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8)
        } finally {
            inflater.end()
        }
    }
}
