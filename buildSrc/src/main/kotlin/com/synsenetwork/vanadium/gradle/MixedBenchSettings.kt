package com.synsenetwork.vanadium.gradle

/**
 * `-Pmixed.*` properties of the manual mixed whole-server benchmark, validated before launch.
 * One invocation measures one mode in a fresh world and JVM; compare modes by alternating invocations.
 */
class MixedBenchSettings(private val property: (String) -> String?) {
    /** `enabled` runs Vanadium normally; `serial` sets `enabled = false` (structural mixins stay; not a no-mod control). */
    val mode = (property("mixed.mode") ?: "enabled").also {
        require(it in MODES) { "mixed.mode must be one of $MODES, not '$it'" }
    }
    val players = int("mixed.players", 8, 1..64)
    val bases = int("mixed.bases", 4, 1..16)
    /** Long enough for an explorer's first round trip (≈1,370 ticks), which generates its view corridor. */
    val warmupTicks = int("mixed.warmup", 1600, 0..1_000_000)
    val windowTicks = int("mixed.window", 1200, 20..1_000_000)
    val windows = int("mixed.windows", 5, 1..1_000)
    val sprint = bool("mixed.sprint", false)
    val workers = int("mixed.workers", 0, 0..1_024)
    val isolation = int("mixed.isolation", 2, 1..16)
    /**
     * false writes `parallelSpawning = false` (vanilla serial spawn count, and vanilla's per-chunk thunder, checks
     * and attempt inline in its order) for a serial-spawning arm of an enabled run. Only meaningful with `mixed.mode=enabled`.
     */
    val parallelSpawning = bool("mixed.parallelSpawning", true).also {
        require(it || mode == "enabled") { "mixed.parallelSpawning=false needs mixed.mode=enabled (serial mode disables it already)" }
    }
    val c2meParallelism: Int? = property("mixed.c2me")?.let { parse("mixed.c2me", it, 1..1_024) }
    val viewDistance = int("mixed.viewDistance", 8, 3..32)
    val simulationDistance = int("mixed.simulationDistance", 6, 3..32)
    val heap = (property("mixed.heap") ?: "4g").also {
        require(it.matches(Regex("[1-9][0-9]*[mMgG]"))) { "mixed.heap must look like 4g or 6144m, not '$it'" }
    }
    /** One collector per run: `g1` (default) or `zgc` (generational, the only ZGC mode on Java 25). */
    val gc = (property("mixed.gc") ?: "g1").also {
        require(it in GCS) { "mixed.gc must be one of ${GCS.keys}, not '$it'" }
    }
    /** GC and safepoint unified logging into the run directory's `gc.log`, decorated with JVM uptime in nanoseconds. */
    val gcLog = bool("mixed.gcLog", false)
    /** Ticks of JFR (`-Plive.jfr=true`) before the first window, so starting the recording is not measured. */
    val jfrLeadTicks = int("mixed.jfrLead", 200, 0..1_000_000)
    val extraJvmArgs: List<String> = property("mixed.jvmArgs")?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty().also { args ->
        args.firstOrNull { COLLECTOR_FLAG.matches(it) }?.let {
            throw IllegalArgumentException("mixed.jvmArgs must not select a collector ('$it'); use mixed.gc=${GCS.keys.joinToString("|")}")
        }
    }

    /** Setup and readiness take minutes; measured ticks are allowed half speed (10 TPS) before the run is killed. */
    val timeoutMinutes: Long get() = 15L + (warmupTicks.toLong() + windowTicks.toLong() * windows) / 600

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

    private fun int(name: String, default: Int, range: IntRange) = property(name)?.let { parse(name, it, range) } ?: default

    private fun parse(name: String, value: String, range: IntRange): Int {
        val parsed = value.toIntOrNull()
        require(parsed != null && parsed in range) { "$name must be an integer in $range, not '$value'" }
        return parsed
    }

    private fun bool(name: String, default: Boolean) = property(name)?.let {
        requireNotNull(it.toBooleanStrictOrNull()) { "$name must be true or false, not '$it'" }
    } ?: default

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
