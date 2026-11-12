package com.synsenetwork.vanadium.collections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class LongByteMapVersionTest {
    @Test
    fun versionAdvancesOnlyWhenAnEntryMayChange() {
        val map = ConcurrentLongByteMap(versioned = true)
        map.defaultReturnValue(33)
        var version = map.version
        fun changed(expected: Boolean, action: () -> Unit) {
            action()
            if (expected) assertNotEquals(version, map.version) else assertEquals(version, map.version)
            version = map.version
        }
        changed(true) { map.put(1L, 31) }
        changed(false) { map.put(1L, 31) }
        changed(true) { map.put(1L, 32) }
        changed(false) { map.remove(2L) }
        changed(false) { map[1L] }
        changed(true) { map.remove(1L) }
        changed(true) { map.put(-5L, 20) }
        changed(true) {
            val entries = map.long2ByteEntrySet().iterator()
            entries.next()
            entries.remove()
        }
        assertEquals(0, map.size)
        changed(true) { map.clear() }
        assertEquals(33.toByte(), map.get(1L))
        assertFailsWith<IllegalStateException> { ConcurrentLongByteMap().version }
    }
}
