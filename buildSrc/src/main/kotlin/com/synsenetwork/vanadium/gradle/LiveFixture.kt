package com.synsenetwork.vanadium.gradle

/**
 * Test mods and their run tasks share one definition; none of these jars enters the release. Launch values that
 * depend on `-P` properties are resolved by [LiveLaunch].
 */
enum class LiveFixture(
    val runName: String,
    val packageDirectory: String,
    val modName: String,
    val jarTask: String,
    val timeoutMinutes: Long,
    val resources: List<String> = emptyList(),
    /** Overrides Loom's generic run task description; null keeps it. */
    val description: String? = null,
) {
    INTEGRATION("integration", "integration", "integration-checks", "integrationChecksJar", 5),
    FEATURE_BENCH("featureBench", "featurebench", "feature-bench", "featureBenchJar", 20, listOf("feature-bench.mixins.json")),

    /** Manual whole-server benchmark; its timeout grows with the requested ticks (see MixedBenchSettings). */
    MIXED_BENCH("mixedBench", "mixedbench", "mixed-bench", "mixedBenchJar", 10,
        description = "Manual realistic whole-server benchmark (one mode per run; see docs/PROFILING.md)"),

    /** Manual cold-first-use explosion benchmark (one arm per run; see ColdExplosionSettings). */
    COLD_EXPLOSION("coldExplosion", "coldexplosion", "cold-explosion", "coldExplosionJar", 10, listOf("cold-explosion.mixins.json"),
        "Manual first-explosion (cold first use) benchmark (one arm per run; see docs/PROFILING.md)");

    val descriptor: String get() = "$modName.fabric.mod.json"
    val jarName: String get() = "$modName.jar"
    val runTask: String get() = "run${runName.replaceFirstChar(Char::uppercase)}"
    /** Only the integration suite is routine verification; the others are opt-in benchmarks. */
    val group: String get() = if (this == INTEGRATION) "verification" else "benchmark"
}
