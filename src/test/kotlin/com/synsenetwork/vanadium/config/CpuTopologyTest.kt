package com.synsenetwork.vanadium.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CpuTopologyTest {
    @Test
    fun usesTheReportedPhysicalCoreCount() {
        // Topology and package handling belong to OSHI on every platform.
        for (cores in listOf(1, 4, 24, 36)) assertEquals(cores, CpuTopology.detect { cores })
    }

    @Test
    fun invalidCountsUseTheFallback() {
        assertNull(CpuTopology.detect { 0 })
        assertNull(CpuTopology.detect { -1 })
    }

    @Test
    fun unsupportedOrRestrictedHardwareQueriesUseTheFallback() {
        assertNull(CpuTopology.detect { throw UnsupportedOperationException("unsupported OS") })
        assertNull(CpuTopology.detect { throw SecurityException("restricted native access") })
        assertNull(CpuTopology.detect { throw IllegalStateException("hardware unavailable") })
    }

    @Test
    fun missingNativeLibrariesUseTheFallback() {
        assertNull(CpuTopology.detect { throw UnsatisfiedLinkError("native library unavailable") })
        assertNull(CpuTopology.detect { throw NoClassDefFoundError("hardware provider unavailable") })
    }

    @Test
    fun fatalErrorsAreNotSwallowed() {
        assertFailsWith<OutOfMemoryError> { CpuTopology.detect { throw OutOfMemoryError("test") } }
    }
}
