package com.synsenetwork.vanadium.concurrent

import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixedLongKeysTest {
    @Test
    fun mixIsInvertible() {
        for (key in longArrayOf(0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE)) assertEquals(key, MixedLongKeys.unmix(MixedLongKeys.mix(key)))
        val random = SplittableRandom(42)
        repeat(100_000) {
            val key = random.nextLong()
            assertEquals(key, MixedLongKeys.unmix(MixedLongKeys.mix(key)))
        }
    }

    /** Raw packed chunk keys hash to x ^ z; mixed keys must stay below ConcurrentHashMap's treeify threshold. */
    @Test
    fun packedChunkKeysSpreadAcrossBins() {
        val table = 8192
        val bins = IntArray(table)
        for (x in -32 until 32) for (z in -32 until 32) {
            val packed = (x.toLong() and 0xFFFFFFFFL) or ((z.toLong() and 0xFFFFFFFFL) shl 32)
            val h = MixedLongKeys.mix(packed).hashCode()
            bins[(h xor (h ushr 16)) and (table - 1)]++
        }
        assertTrue(bins.max() < 8, "packed chunk keys clustered: ${bins.max()}")
    }
}
