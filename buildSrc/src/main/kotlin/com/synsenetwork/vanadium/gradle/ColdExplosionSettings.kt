package com.synsenetwork.vanadium.gradle

/**
 * `-Pcold.*` properties of the manual cold-first-use explosion benchmark, validated before launch. One invocation
 * runs one arm in a fresh world and JVM; compare arms by alternating invocations (see docs/profiling.html).
 */
class ColdExplosionSettings(property: (String) -> String?) {
    private val properties = StrictProperties(property)

    /** What arena 0 does before the measured blasts: `sham` (nothing), `warmup` (one creeper blast), `load` (load-only control). */
    val arm = properties.choice("cold.arm", "sham", ARMS)
    /** `serial` sets `enabled = false` (structural mixins stay): the same blasts off the parallel entity wave. */
    val mode = properties.choice("cold.mode", "enabled", listOf("enabled", "serial"))
    /** Measured blasts (arenas 1..N); the first is the comparison, the rest are warm in every arm. */
    val blasts = properties.int("cold.blasts", 8, 2..11)
    val spacing = properties.int("cold.spacing", 100, 60..1_200)
    /** AI cows in the pen, so the entity wave is large enough to use workers; 0 leaves a tiny wave. */
    val filler = properties.int("cold.filler", 200, 0..400)
    val workers = properties.int("cold.workers", 0, 0..1_024)
    /** Class-load unified logging into the run's `class-load.log`, on the JVM-uptime clock of `ticks.csv`. */
    val classLog = properties.bool("cold.classLog", false)

    val jvmArgs: List<String> get() = listOf("-Xms2g", "-Xmx2g", "-XX:+UseG1GC") + if (classLog) listOf(CLASS_LOG) else emptyList()

    val systemProperties: Map<String, String> get() = linkedMapOf(
        "vanadium.cold.arm" to arm,
        "vanadium.cold.mode" to mode,
        "vanadium.cold.blasts" to "$blasts",
        "vanadium.cold.spacing" to "$spacing",
        "vanadium.cold.filler" to "$filler",
        "vanadium.cold.classLog" to "$classLog",
    )

    val liveSettings: LiveSettings get() = LiveSettings(
        serverProperties = linkedMapOf("difficulty" to "normal", "view-distance" to "8", "simulation-distance" to "6"),
        vanadium = linkedMapOf("enabled" to "${mode == "enabled"}", "workers" to "$workers", "isolation" to "2"),
        c2meParallelism = null,
    )

    /** Run-relative files a passing run leaves, with minimum line counts (header plus rows). */
    fun expectedOutputs(): Map<String, Long> = linkedMapOf(
        "cold-explosion/blasts.csv" to 1L + blasts + if (arm == "warmup") 1 else 0,
        "cold-explosion/ticks.csv" to 2L + 300 + (blasts - 1L) * spacing,
        "vanadium/results/cold-explosion-*.json" to 1L,
    ).apply {
        if (classLog) {
            put("class-load.log", 1L)
            put("cold-explosion/blast-classes.csv", 1L)
        }
    }

    companion object {
        val ARMS = listOf("sham", "warmup", "load")

        /** Every loaded class with its JVM uptime in nanoseconds (`[<n>ns] <class> source: ...`). */
        const val CLASS_LOG = "-Xlog:class+load=info:file=class-load.log:uptimenanos"
    }
}
