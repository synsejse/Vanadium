package com.synsenetwork.vanadium.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveOptionsTest {
    @Test
    fun offersBooleanAndIntegerOptionsExceptWorkers() {
        for (name in listOf("enabled", "parallelTracking", "chunkCache", "cellSize", "slowWaveMillis")) {
            assertTrue(name in LiveOptions.names, name)
        }
        for (name in listOf("workers", "serialEntityTypes", "serialBlockEntityTypes")) {
            assertFalse(name in LiveOptions.names, name)
        }
        assertTrue(LiveOptions.isBoolean("parallelTracking"))
        assertFalse(LiveOptions.isBoolean("cellSize"))
    }

    @Test
    fun parsesValuesAndRestoresInvalidOnes() {
        val config = VanadiumConfig()
        assertNull(LiveOptions.set(config, "parallelTracking", "off"))
        assertFalse(config.parallelTracking)
        assertNull(LiveOptions.set(config, "parallelTracking", "TRUE"))
        assertTrue(config.parallelTracking)
        assertNull(LiveOptions.set(config, "cellSize", "4"))
        assertEquals(4, config.cellSize)

        assertNotNull(LiveOptions.set(config, "parallelTracking", "maybe"))
        assertTrue(config.parallelTracking)
        assertNotNull(LiveOptions.set(config, "cellSize", "-1")) // Rejected by validatePostLoad.
        assertEquals(4, config.cellSize)
        assertNotNull(LiveOptions.set(config, "cellSize", "four"))
        assertNotNull(LiveOptions.set(config, "workers", "8"))
        assertEquals(0, config.workers)
    }
}
