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
        for (name in listOf("enabled", "parallelTracking", "isolation", "slowWaveMillis")) {
            assertTrue(name in LiveOptions.names, name)
        }
        for (name in listOf("workers", "cellSize", "chunkCache", "parallelChunkLoads", "serialEntityTypes", "serialBlockEntityTypes")) {
            assertFalse(name in LiveOptions.names, name)
        }
        assertTrue(LiveOptions.isBoolean("parallelTracking"))
        assertFalse(LiveOptions.isBoolean("isolation"))
    }

    @Test
    fun parsesValuesAndRestoresInvalidOnes() {
        val config = VanadiumConfig()
        assertNull(LiveOptions.set(config, "parallelTracking", "off"))
        assertFalse(config.parallelTracking)
        assertNull(LiveOptions.set(config, "parallelTracking", "TRUE"))
        assertTrue(config.parallelTracking)
        assertNull(LiveOptions.set(config, "isolation", "4"))
        assertEquals(4, config.isolation)

        assertNotNull(LiveOptions.set(config, "parallelTracking", "maybe"))
        assertTrue(config.parallelTracking)
        assertNotNull(LiveOptions.set(config, "isolation", "0")) // Rejected by validatePostLoad.
        assertEquals(4, config.isolation)
        assertNotNull(LiveOptions.set(config, "isolation", "four"))
        assertNotNull(LiveOptions.set(config, "workers", "8"))
        assertEquals(0, config.workers)
    }

    @Test
    fun idleWorkersTakesOneOfItsModes() {
        val config = VanadiumConfig()
        assertTrue("idleWorkers" in LiveOptions.names)
        assertEquals(listOf("balanced", "hot", "lean"), LiveOptions.choices("idleWorkers"))
        assertNull(LiveOptions.set(config, "idleWorkers", "HOT"))
        assertEquals("hot", config.idleWorkers)
        assertNotNull(LiveOptions.set(config, "idleWorkers", "warm"))
        assertEquals("hot", config.idleWorkers)
        assertNull(LiveOptions.choices("parallelTracking"))
    }

    @Test
    fun optionNamesKeepTheirOrder() {
        assertEquals(
            listOf(
                "enabled", "isolation", "parallelEntities", "skipFailedEntityTicks", "parallelBlockEntities",
                "parallelChunkTicks", "parallelScheduledTicks", "parallelSpawning", "parallelTracking",
                "parallelChunkPackets", "consolidateFlushes", "crowdedSectionIndex",
                "idleWorkers", "slowWaveMillis", "detailedTickDiagnostics",
            ),
            LiveOptions.names.toList(),
        )
    }

    @Suppress("unused")
    private class Fields {
        @JvmField var flag = true
        @JvmField var count = 1
        @JvmField var workers = 2
        @JvmField var mode = "a"
        @JvmField var label = "unlisted"
        @JvmField var ids: MutableList<String> = ArrayList()
        @JvmField var ratio = 0.5
        @Transient @JvmField var cached = false
    }

    @Test
    fun onlyListedTextFieldsAreLive() {
        // A new text field without accepted values stays out instead of registering and failing on set.
        assertEquals(listOf("flag", "count", "mode"), LiveOptions.liveFields(Fields::class.java, setOf("mode")).keys.toList())
        assertEquals(listOf("flag", "count"), LiveOptions.liveFields(Fields::class.java, emptySet()).keys.toList())
    }
}
