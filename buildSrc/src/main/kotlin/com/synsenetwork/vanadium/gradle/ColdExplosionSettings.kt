package com.synsenetwork.vanadium.gradle

/**
 * `-Pcold.*` properties of the manual cold-first-use explosion benchmark, validated before launch. One invocation
 * runs one arm in a fresh world and JVM; compare arms by alternating invocations (see docs/PROFILING.md).
 */
class ColdExplosionSettings(private val property: (String) -> String?) {
    /** What arena 0 does before the measured blasts: `sham` (nothing), `warmup` (one creeper blast), `load` (load-only control). */
    val arm = choice("cold.arm", "sham", ARMS)
    /** `serial` sets `enabled = false` (structural mixins stay): the same blasts off the parallel entity wave. */
    val mode = choice("cold.mode", "enabled", listOf("enabled", "serial"))
    /** Measured blasts (arenas 1..N); the first is the comparison, the rest are warm in every arm. */
    val blasts = int("cold.blasts", 8, 2..11)
    val spacing = int("cold.spacing", 100, 60..1_200)
    /** AI cows in the pen, so the entity wave is large enough to use workers; 0 leaves a tiny wave. */
    val filler = int("cold.filler", 200, 0..400)
    val workers = int("cold.workers", 0, 0..1_024)
    /** Class-load unified logging into the run's `class-load.log`, on the JVM-uptime clock of `ticks.csv`. */
    val classLog = bool("cold.classLog", false)

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

    private fun choice(name: String, default: String, choices: List<String>) = (property(name) ?: default).also {
        require(it in choices) { "$name must be one of $choices, not '$it'" }
    }

    private fun int(name: String, default: Int, range: IntRange): Int {
        val value = property(name) ?: return default
        val parsed = value.toIntOrNull()
        require(parsed != null && parsed in range) { "$name must be an integer in $range, not '$value'" }
        return parsed
    }

    private fun bool(name: String, default: Boolean) = property(name)?.let {
        requireNotNull(it.toBooleanStrictOrNull()) { "$name must be true or false, not '$it'" }
    } ?: default

    companion object {
        val ARMS = listOf("sham", "warmup", "load")

        /** Every loaded class with its JVM uptime in nanoseconds (`[<n>ns] <class> source: ...`). */
        const val CLASS_LOG = "-Xlog:class+load=info:file=class-load.log:uptimenanos"
    }
}
