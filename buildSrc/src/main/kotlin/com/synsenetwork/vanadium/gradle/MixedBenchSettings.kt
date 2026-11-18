package com.synsenetwork.vanadium.gradle

/**
 * `-Pmixed.*` properties of the manual mixed whole-server benchmark, validated before launch.
 * One invocation measures one mode in a fresh world and JVM; compare modes by alternating invocations.
 */
class MixedBenchSettings(property: (String) -> String?) {
    private val properties = StrictProperties(property)

    /** `enabled` runs Vanadium normally; `serial` sets `enabled = false` (structural mixins stay; not a no-mod control). */
    val mode = properties.choice("mixed.mode", "enabled", MODES)
    val players = properties.int("mixed.players", 8, 1..64)
    val bases = properties.int("mixed.bases", 4, 1..16)
    /** Long enough for an explorer's first round trip (≈1,370 ticks), which generates its view corridor. */
    val warmupTicks = properties.int("mixed.warmup", 1600, 0..1_000_000)
    val windowTicks = properties.int("mixed.window", 1200, 20..1_000_000)
    val windows = properties.int("mixed.windows", 5, 1..1_000)
    val sprint = properties.bool("mixed.sprint", false)
    val workers = properties.int("mixed.workers", 0, 0..1_024)
    val isolation = properties.int("mixed.isolation", 2, 1..16)
    /**
     * false writes `parallelSpawning = false` (vanilla serial spawn count, and vanilla's per-chunk thunder, checks
     * and attempt inline in its order) for a serial-spawning arm of an enabled run. Only meaningful with `mixed.mode=enabled`.
     */
    val parallelSpawning = properties.bool("mixed.parallelSpawning", true).also {
        require(it || mode == "enabled") { "mixed.parallelSpawning=false needs mixed.mode=enabled (serial mode disables it already)" }
    }
    val c2meParallelism: Int? = properties.intOrNull("mixed.c2me", 1..1_024)
    val viewDistance = properties.int("mixed.viewDistance", 8, 3..32)
    val simulationDistance = properties.int("mixed.simulationDistance", 6, 3..32)
    val heap = (properties["mixed.heap"] ?: "4g").also {
        require(it.matches(Regex("[1-9][0-9]*[mMgG]"))) { "mixed.heap must look like 4g or 6144m, not '$it'" }
    }
    /** One collector per run: `g1` (default) or `zgc` (generational, the only ZGC mode on Java 25). */
    val gc = properties.choice("mixed.gc", "g1", GCS.keys)
    /** GC and safepoint unified logging into the run directory's `gc.log`, decorated with JVM uptime in nanoseconds. */
    val gcLog = properties.bool("mixed.gcLog", false)
    /**
     * Ticks of JFR (`-Plive.jfr=true`) before the first window, so starting the recording is not measured. At least
     * one: with no lead, starting JFR would fall inside the first window's first completion gap.
     */
    val jfrLeadTicks = properties.int("mixed.jfrLead", 200, 1..1_000_000)
    val extraJvmArgs: List<String> = properties["mixed.jvmArgs"]?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty().also { args ->
        args.firstOrNull { COLLECTOR_FLAG.matches(it) }?.let {
            throw IllegalArgumentException("mixed.jvmArgs must not select a collector ('$it'); use mixed.gc=${GCS.keys.joinToString("|")}")
        }
        // The requested gc.log must be the one mixed.gcLog configures: no other writer of it, and logging not disabled.
        if (gcLog) args.firstOrNull { it.startsWith("-Xloggc") || it.startsWith("-Xlog:") && ("gc.log" in it || "disable" in it) }?.let {
            throw IllegalArgumentException("mixed.jvmArgs must not reconfigure or disable gc.log with mixed.gcLog=true ('$it')")
        }
    }

    /** Setup and readiness take minutes; measured ticks are allowed half speed (10 TPS) before the run is killed. */
    val timeoutMinutes: Long get() = 15L + (warmupTicks.toLong() + windowTicks.toLong() * windows) / 600

    /**
     * Run-relative files a passing run must leave, with their minimum line counts (a `*` matches within one directory
     * level). Line counts are lower bounds: readiness adds ticks, and `jfr_start` a boundary.
     */
    fun expectedOutputs(): Map<String, Long> = linkedMapOf(
        "mixed-bench/windows.csv" to windows + 1L,
        "mixed-bench/ticks.csv" to 1L + warmupTicks + windowTicks.toLong() * windows,
        "mixed-bench/boundaries.csv" to 4L + windows,
        "mixed-bench/threads.csv" to 2L,
        "mixed-bench/entities.csv" to 2L,
        "vanadium/results/mixed-bench-*.json" to 1L,
    ).apply { if (gcLog) put("gc.log", 1L) }

    val jvmArgs: List<String> get() = listOf("-Xms$heap", "-Xmx$heap", GCS.getValue(gc)) + (if (gcLog) listOf(GC_LOG) else emptyList()) + extraJvmArgs

    val systemProperties: Map<String, String> get() = linkedMapOf(
        "vanadium.mixed.mode" to mode,
        "vanadium.mixed.players" to "$players",
        "vanadium.mixed.bases" to "$bases",
        "vanadium.mixed.warmup" to "$warmupTicks",
        "vanadium.mixed.window" to "$windowTicks",
        "vanadium.mixed.windows" to "$windows",
        "vanadium.mixed.sprint" to "$sprint",
        "vanadium.mixed.parallelSpawning" to "$parallelSpawning",
        "vanadium.mixed.jfrLead" to "$jfrLeadTicks",
    )

    val liveSettings: LiveSettings get() = LiveSettings(
        serverProperties = linkedMapOf(
            "level-type" to "minecraft:normal",
            "difficulty" to "normal",
            "view-distance" to "$viewDistance",
            "simulation-distance" to "$simulationDistance",
            "max-players" to "$players",
            // A stalled tick (or setup) ends the run after five minutes with vanilla's crash report and full thread dump.
            "max-tick-time" to "300000",
            // The simulated clients follow terrain height; vanilla's floating check is not part of the workload.
            "allow-flight" to "true",
        ),
        // The key is written only when set, so default runs keep the configuration earlier results were measured with.
        vanadium = linkedMapOf("enabled" to "${mode == "enabled"}", "workers" to "$workers", "isolation" to "$isolation")
            .apply { if (!parallelSpawning) put("parallelSpawning", "false") },
        c2meParallelism = c2meParallelism,
    )

    companion object {
        val MODES = listOf("enabled", "serial")
        val GCS = linkedMapOf("g1" to "-XX:+UseG1GC", "zgc" to "-XX:+UseZGC")

        /**
         * Every GC and safepoint message, unrotated. `uptimenanos` is the clock of `mixed-bench/ticks.csv`
         * (`end_uptime_ns`); `time` gives wall-clock time for matching JFR in Mission Control.
         */
        const val GC_LOG = "-Xlog:gc*,safepoint:file=gc.log:time,uptime,uptimenanos,level,tags:filecount=0"

        private val COLLECTOR_FLAG = Regex("-XX:[+-]Use(Serial|Parallel|ParallelOld|G1|Z|Shenandoah|Epsilon|ConcMarkSweep)GC")
    }
}
