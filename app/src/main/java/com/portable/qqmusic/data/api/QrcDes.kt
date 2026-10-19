package com.portable.qqmusic.data.api

/**
 * QRC 解密用的 Triple-DES —— 这**不是**标准 3DES。
 *
 * QQ 音乐的 QRC 歌词用的是一套逆向出来的变体：S 盒下标要先过 [sboxBit] 做一次位重排，
 * 密钥扩展的 PC-2 偏移量、初始/逆置换的位序也都和标准实现不同。
 * 拿 javax.crypto 的 DESede 去解永远是乱码，所以这一份是从 Python 实现逐位移植的。
 *
 * 参考: WXRIW/QQMusicDecoder 的 DESHelper.cs + Decrypter.cs（C#），
 *       luren-dc/QQMusicApi 的 algorithms/tripledes.py（Python）。
 *
 * Python 那边整数是无限精度的，这里统一用 Int 承载 32 位位模式，
 * 所以所有右移都必须是 `ushr`（逻辑右移），`shr` 会把符号位带进来。
 */
internal object QrcDes {

    const val ENCRYPT = 1
    const val DECRYPT = 0

    private val SBOX = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    private val KEY_RND_SHIFT = intArrayOf(
        1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1,
    )

    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
        9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )

    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
        13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )

    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9,
        22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
        43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)

    private fun initialPermutation(d: ByteArray): IntArray {
        val v0 = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8) or
            ((d[2].toInt() and 0xFF) shl 16) or ((d[3].toInt() and 0xFF) shl 24)
        val v1 = (d[4].toInt() and 0xFF) or ((d[5].toInt() and 0xFF) shl 8) or
            ((d[6].toInt() and 0xFF) shl 16) or ((d[7].toInt() and 0xFF) shl 24)

        var s0 = 0
        var s1 = 0
        s0 = (((v1 ushr 6) and 1) shl 31) or
            (((v1 ushr 14) and 1) shl 30) or
            (((v1 ushr 22) and 1) shl 29) or
            (((v1 ushr 30) and 1) shl 28) or
            (((v0 ushr 6) and 1) shl 27) or
            (((v0 ushr 14) and 1) shl 26) or
            (((v0 ushr 22) and 1) shl 25) or
            (((v0 ushr 30) and 1) shl 24) or
            (((v1 ushr 4) and 1) shl 23) or
            (((v1 ushr 12) and 1) shl 22) or
            (((v1 ushr 20) and 1) shl 21) or
            (((v1 ushr 28) and 1) shl 20) or
            (((v0 ushr 4) and 1) shl 19) or
            (((v0 ushr 12) and 1) shl 18) or
            (((v0 ushr 20) and 1) shl 17) or
            (((v0 ushr 28) and 1) shl 16) or
            (((v1 ushr 2) and 1) shl 15) or
            (((v1 ushr 10) and 1) shl 14) or
            (((v1 ushr 18) and 1) shl 13) or
            (((v1 ushr 26) and 1) shl 12) or
            (((v0 ushr 2) and 1) shl 11) or
            (((v0 ushr 10) and 1) shl 10) or
            (((v0 ushr 18) and 1) shl 9) or
            (((v0 ushr 26) and 1) shl 8) or
            (((v1 ushr 0) and 1) shl 7) or
            (((v1 ushr 8) and 1) shl 6) or
            (((v1 ushr 16) and 1) shl 5) or
            (((v1 ushr 24) and 1) shl 4) or
            (((v0 ushr 0) and 1) shl 3) or
            (((v0 ushr 8) and 1) shl 2) or
            (((v0 ushr 16) and 1) shl 1) or
            (((v0 ushr 24) and 1))
        s1 = (((v1 ushr 7) and 1) shl 31) or
            (((v1 ushr 15) and 1) shl 30) or
            (((v1 ushr 23) and 1) shl 29) or
            (((v1 ushr 31) and 1) shl 28) or
            (((v0 ushr 7) and 1) shl 27) or
            (((v0 ushr 15) and 1) shl 26) or
            (((v0 ushr 23) and 1) shl 25) or
            (((v0 ushr 31) and 1) shl 24) or
            (((v1 ushr 5) and 1) shl 23) or
            (((v1 ushr 13) and 1) shl 22) or
            (((v1 ushr 21) and 1) shl 21) or
            (((v1 ushr 29) and 1) shl 20) or
            (((v0 ushr 5) and 1) shl 19) or
            (((v0 ushr 13) and 1) shl 18) or
            (((v0 ushr 21) and 1) shl 17) or
            (((v0 ushr 29) and 1) shl 16) or
            (((v1 ushr 3) and 1) shl 15) or
            (((v1 ushr 11) and 1) shl 14) or
            (((v1 ushr 19) and 1) shl 13) or
            (((v1 ushr 27) and 1) shl 12) or
            (((v0 ushr 3) and 1) shl 11) or
            (((v0 ushr 11) and 1) shl 10) or
            (((v0 ushr 19) and 1) shl 9) or
            (((v0 ushr 27) and 1) shl 8) or
            (((v1 ushr 1) and 1) shl 7) or
            (((v1 ushr 9) and 1) shl 6) or
            (((v1 ushr 17) and 1) shl 5) or
            (((v1 ushr 25) and 1) shl 4) or
            (((v0 ushr 1) and 1) shl 3) or
            (((v0 ushr 9) and 1) shl 2) or
            (((v0 ushr 17) and 1) shl 1) or
            (((v0 ushr 25) and 1))
        return intArrayOf(s0, s1)
    }

    private fun inversePermutation(s0: Int, s1: Int): ByteArray {
        val d = ByteArray(8)
        d[3] = ((((s1 ushr 24) and 1) shl 7) or
            (((s0 ushr 24) and 1) shl 6) or
            (((s1 ushr 16) and 1) shl 5) or
            (((s0 ushr 16) and 1) shl 4) or
            (((s1 ushr 8) and 1) shl 3) or
            (((s0 ushr 8) and 1) shl 2) or
            (((s1 ushr 0) and 1) shl 1) or
            (((s0 ushr 0) and 1))).toByte()
        d[2] = ((((s1 ushr 25) and 1) shl 7) or
            (((s0 ushr 25) and 1) shl 6) or
            (((s1 ushr 17) and 1) shl 5) or
            (((s0 ushr 17) and 1) shl 4) or
            (((s1 ushr 9) and 1) shl 3) or
            (((s0 ushr 9) and 1) shl 2) or
            (((s1 ushr 1) and 1) shl 1) or
            (((s0 ushr 1) and 1))).toByte()
        d[1] = ((((s1 ushr 26) and 1) shl 7) or
            (((s0 ushr 26) and 1) shl 6) or
            (((s1 ushr 18) and 1) shl 5) or
            (((s0 ushr 18) and 1) shl 4) or
            (((s1 ushr 10) and 1) shl 3) or
            (((s0 ushr 10) and 1) shl 2) or
            (((s1 ushr 2) and 1) shl 1) or
            (((s0 ushr 2) and 1))).toByte()
        d[0] = ((((s1 ushr 27) and 1) shl 7) or
            (((s0 ushr 27) and 1) shl 6) or
            (((s1 ushr 19) and 1) shl 5) or
            (((s0 ushr 19) and 1) shl 4) or
            (((s1 ushr 11) and 1) shl 3) or
            (((s0 ushr 11) and 1) shl 2) or
            (((s1 ushr 3) and 1) shl 1) or
            (((s0 ushr 3) and 1))).toByte()
        d[7] = ((((s1 ushr 28) and 1) shl 7) or
            (((s0 ushr 28) and 1) shl 6) or
            (((s1 ushr 20) and 1) shl 5) or
            (((s0 ushr 20) and 1) shl 4) or
            (((s1 ushr 12) and 1) shl 3) or
            (((s0 ushr 12) and 1) shl 2) or
            (((s1 ushr 4) and 1) shl 1) or
            (((s0 ushr 4) and 1))).toByte()
        d[6] = ((((s1 ushr 29) and 1) shl 7) or
            (((s0 ushr 29) and 1) shl 6) or
            (((s1 ushr 21) and 1) shl 5) or
            (((s0 ushr 21) and 1) shl 4) or
            (((s1 ushr 13) and 1) shl 3) or
            (((s0 ushr 13) and 1) shl 2) or
            (((s1 ushr 5) and 1) shl 1) or
            (((s0 ushr 5) and 1))).toByte()
        d[5] = ((((s1 ushr 30) and 1) shl 7) or
            (((s0 ushr 30) and 1) shl 6) or
            (((s1 ushr 22) and 1) shl 5) or
            (((s0 ushr 22) and 1) shl 4) or
            (((s1 ushr 14) and 1) shl 3) or
            (((s0 ushr 14) and 1) shl 2) or
            (((s1 ushr 6) and 1) shl 1) or
            (((s0 ushr 6) and 1))).toByte()
        d[4] = ((((s1 ushr 31) and 1) shl 7) or
            (((s0 ushr 31) and 1) shl 6) or
            (((s1 ushr 23) and 1) shl 5) or
            (((s0 ushr 23) and 1) shl 4) or
            (((s1 ushr 15) and 1) shl 3) or
            (((s0 ushr 15) and 1) shl 2) or
            (((s1 ushr 7) and 1) shl 1) or
            (((s0 ushr 7) and 1))).toByte()
        return d
    }

    private fun f(state0: Int, key: IntArray): Int {
        var state = state0
        val t1 = ((state and 1) shl 31) or
            ((state and 0xF8000000.toInt()) ushr 1) or
            ((state and 0x1F800000) ushr 3) or
            ((state and 0x01F80000) ushr 5) or
            ((state and 0x001F8000) ushr 7)
        val t2 = ((state and 0x0001F800) shl 15) or
            ((state and 0x00001F80) shl 13) or
            ((state and 0x000001F8) shl 11) or
            ((state and 0x1F) shl 9) or
            ((state and 0x80000000.toInt()) ushr 23)

        val k0 = ((t1 ushr 24) and 0xFF) xor key[0]
        val k1 = ((t1 ushr 16) and 0xFF) xor key[1]
        val k2 = ((t1 ushr 8) and 0xFF) xor key[2]
        val k3 = ((t2 ushr 24) and 0xFF) xor key[3]
        val k4 = ((t2 ushr 16) and 0xFF) xor key[4]
        val k5 = ((t2 ushr 8) and 0xFF) xor key[5]

        state = (SBOX[0][sboxBit(k0 ushr 2)] shl 28) or
            (SBOX[1][sboxBit(((k0 and 0x03) shl 4) or (k1 ushr 4))] shl 24) or
            (SBOX[2][sboxBit(((k1 and 0x0F) shl 2) or (k2 ushr 6))] shl 20) or
            (SBOX[3][sboxBit(k2 and 0x3F)] shl 16) or
            (SBOX[4][sboxBit(k3 ushr 2)] shl 12) or
            (SBOX[5][sboxBit(((k3 and 0x03) shl 4) or (k4 ushr 4))] shl 8) or
            (SBOX[6][sboxBit(((k4 and 0x0F) shl 2) or (k5 ushr 6))] shl 4) or
            SBOX[7][sboxBit(k5 and 0x3F)]

        return (((state ushr 16) and 1) shl 31) or
            (((state ushr 25) and 1) shl 30) or
            (((state ushr 12) and 1) shl 29) or
            (((state ushr 11) and 1) shl 28) or
            (((state ushr 3) and 1) shl 27) or
            (((state ushr 20) and 1) shl 26) or
            (((state ushr 4) and 1) shl 25) or
            (((state ushr 15) and 1) shl 24) or
            (((state ushr 31) and 1) shl 23) or
            (((state ushr 17) and 1) shl 22) or
            (((state ushr 9) and 1) shl 21) or
            (((state ushr 6) and 1) shl 20) or
            (((state ushr 27) and 1) shl 19) or
            (((state ushr 14) and 1) shl 18) or
            (((state ushr 1) and 1) shl 17) or
            (((state ushr 22) and 1) shl 16) or
            (((state ushr 30) and 1) shl 15) or
            (((state ushr 24) and 1) shl 14) or
            (((state ushr 8) and 1) shl 13) or
            (((state ushr 18) and 1) shl 12) or
            (((state ushr 0) and 1) shl 11) or
            (((state ushr 5) and 1) shl 10) or
            (((state ushr 29) and 1) shl 9) or
            (((state ushr 23) and 1) shl 8) or
            (((state ushr 13) and 1) shl 7) or
            (((state ushr 19) and 1) shl 6) or
            (((state ushr 2) and 1) shl 5) or
            (((state ushr 26) and 1) shl 4) or
            (((state ushr 10) and 1) shl 3) or
            (((state ushr 21) and 1) shl 2) or
            (((state ushr 28) and 1) shl 1) or
            (((state ushr 7) and 1))
    }

    private fun cryptBlock(input: ByteArray, key: Array<IntArray>): ByteArray {
        var (s0, s1) = initialPermutation(input).let { it[0] to it[1] }
        for (idx in 0 until 15) {
            val prev = s1
            s1 = f(s1, key[idx]) xor s0
            s0 = prev
        }
        s0 = f(s1, key[15]) xor s0
        return inversePermutation(s0, s1)
    }

    private fun keySchedule(key: ByteArray, offset: Int, mode: Int): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        val v0 = (key[offset].toInt() and 0xFF) or ((key[offset + 1].toInt() and 0xFF) shl 8) or
            ((key[offset + 2].toInt() and 0xFF) shl 16) or ((key[offset + 3].toInt() and 0xFF) shl 24)
        val v1 = (key[offset + 4].toInt() and 0xFF) or ((key[offset + 5].toInt() and 0xFF) shl 8) or
            ((key[offset + 6].toInt() and 0xFF) shl 16) or ((key[offset + 7].toInt() and 0xFF) shl 24)

        var c = 0
        var d = 0
        for (i in KEY_PERM_C.indices) {
            val b = KEY_PERM_C[i]
            val bit = if (b < 32) (v0 ushr (31 - b)) and 1 else (v1 ushr (63 - b)) and 1
            c = c or (bit shl (31 - i))
        }
        for (i in KEY_PERM_D.indices) {
            val b = KEY_PERM_D[i]
            val bit = if (b < 32) (v0 ushr (31 - b)) and 1 else (v1 ushr (63 - b)) and 1
            d = d or (bit shl (31 - i))
        }

        for (i in 0 until 16) {
            val s = KEY_RND_SHIFT[i]
            c = ((c shl s) or (c ushr (28 - s))) and 0xFFFFFFF0.toInt()
            d = ((d shl s) or (d ushr (28 - s))) and 0xFFFFFFF0.toInt()
            val togen = if (mode == DECRYPT) 15 - i else i
            for (j in 0 until 6) schedule[togen][j] = 0
            for (j in 0 until 24) {
                val bit = (c ushr (31 - KEY_COMPRESSION[j])) and 1
                schedule[togen][j / 8] = schedule[togen][j / 8] or (bit shl (7 - (j % 8)))
            }
            for (j in 24 until 48) {
                val bit = (d ushr (31 - (KEY_COMPRESSION[j] - 27))) and 1
                schedule[togen][j / 8] = schedule[togen][j / 8] or (bit shl (7 - (j % 8)))
            }
        }
        return schedule
    }

    /** 24 字节密钥 → 三组 16 轮子密钥 */
    fun keySetup(key: ByteArray, mode: Int): Array<Array<IntArray>> =
        if (mode == ENCRYPT) arrayOf(
            keySchedule(key, 0, ENCRYPT),
            keySchedule(key, 8, DECRYPT),
            keySchedule(key, 16, ENCRYPT)
        ) else arrayOf(
            keySchedule(key, 16, DECRYPT),
            keySchedule(key, 8, ENCRYPT),
            keySchedule(key, 0, DECRYPT)
        )

    fun tripleCrypt(data: ByteArray, schedule: Array<Array<IntArray>>): ByteArray {
        var out = data
        for (i in 0 until 3) out = cryptBlock(out, schedule[i])
        return out
    }
}
