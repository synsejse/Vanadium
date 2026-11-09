package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.HashCommon

/**
 * Key encoding for `ConcurrentHashMap<Long, *>` backings of packed positions. Under `Long.hashCode`,
 * packed chunk keys hash to `x ^ z`, so a 32x32 chunk area collapses onto 32 hashes and the map degrades
 * to tree bins. Stored keys go through murmur3's bijective 64-bit finalizer. (fastutil's `HashCommon.mix`
 * is unsuitable: its xor-folding cancels with `Long.hashCode` and the map's own spreading.)
 */
internal object PackedPositionKeys {
    private const val C1 = -0xae502812aa7333L // 0xff51afd7ed558ccd
    private const val C2 = -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
    private val C1_INVERSE = inverse(C1)
    private val C2_INVERSE = inverse(C2)

    fun mix(key: Long): Long = HashCommon.murmurHash3(key)

    /** Each step inverts itself (xor-shift by at least 32) or multiplies by the modular inverse. */
    fun unmix(key: Long): Long {
        var k = key
        k = k xor (k ushr 33)
        k *= C2_INVERSE
        k = k xor (k ushr 33)
        k *= C1_INVERSE
        return k xor (k ushr 33)
    }

    /** Multiplicative inverse modulo 2^64 of an odd constant (Newton iteration). */
    private fun inverse(odd: Long): Long {
        var x = odd
        repeat(6) { x *= 2 - odd * x }
        return x
    }
}
