package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.HashCommon

/**
 * Bijective 64-bit key mixing for packed chunk and section positions. Raw packed chunk keys hash to
 * `x ^ z` under `Long.hashCode`, and raw packed section keys keep `x` in bits 42..63 above the low
 * bits used by `NonBlockingHashMapLong.hash`. Passing keys through Murmur3's bijective finalizer
 * spreads all coordinate bits uniformly while allowing views to recover the original key.
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
