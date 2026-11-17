package com.synsenetwork.vanadium.gradle

/** Test mods and launch settings share one definition; none of these jars enters the release. */
enum class LiveFixture(
    val runName: String,
    val packageDirectory: String,
    val modName: String,
    val jarTask: String,
    val timeoutMinutes: Long,
    val resources: List<String> = emptyList(),
) {
    INTEGRATION("integration", "integration", "integration-checks", "integrationChecksJar", 5),
    FEATURE_BENCH("featureBench", "featurebench", "feature-bench", "featureBenchJar", 20, listOf("feature-bench.mixins.json")),

    /** Manual whole-server benchmark; its timeout grows with the requested ticks (see MixedBenchSettings). */
    MIXED_BENCH("mixedBench", "mixedbench", "mixed-bench", "mixedBenchJar", 10);

    val descriptor: String get() = "$modName.fabric.mod.json"
    val jarName: String get() = "$modName.jar"
}
