package com.synsenetwork.vanadium.config

import oshi.SystemInfo

/**
 * System physical cores from Minecraft's cross-platform hardware library. Queried once per JVM,
 * only for automatic worker sizing; the result is capped by the JVM's available processor count.
 */
internal object CpuTopology {
    val cores: Int? by lazy { detect { SystemInfo().hardware.processor.physicalProcessorCount } }

    /** Hardware/native access is optional: unsupported platforms retain the logical-processor fallback. */
    internal fun detect(query: () -> Int): Int? = try {
        query().takeIf { it > 0 }
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
