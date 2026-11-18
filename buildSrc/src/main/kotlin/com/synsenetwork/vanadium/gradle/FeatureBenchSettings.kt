package com.synsenetwork.vanadium.gradle

/**
 * `-Pbench.*` properties of the paired feature benchmark. The fixture reads workers, scale and filter itself; rounds
 * and JFR are validated here because they decide which outputs a passing run leaves.
 */
class FeatureBenchSettings(property: (String) -> String?) {
    private val properties = StrictProperties(property)

    private val workers = properties["bench.workers"] ?: "4"
    val rounds = properties.int("bench.rounds", 12, 0..1_000_000)
    private val scale = properties["bench.scale"] ?: "1"
    /** A regex over case names; empty selects every case. */
    private val filter = properties["bench.filter"] ?: ""
    /** One recording per case and arm, taken after the timing samples. */
    val jfr = properties.bool("bench.jfr", true)

    val systemProperties: Map<String, String> get() = linkedMapOf(
        "vanadium.bench.workers" to workers,
        "vanadium.bench.rounds" to "$rounds",
        "vanadium.bench.scale" to scale,
        "vanadium.bench.filter" to filter,
        "vanadium.bench.jfr" to "$jfr",
    )

    // Cases run for minutes without ticking the server.
    val liveSettings: LiveSettings get() = LiveSettings(serverProperties = linkedMapOf("max-tick-time" to "-1"))

    /** Run-relative files a passing run leaves: at least one selected case, each with an AB/BA pair per round. */
    fun expectedOutputs(): Map<String, Long> = linkedMapOf(
        "feature-bench/environment.txt" to 1L,
        "feature-bench/samples.csv" to 1L + 2L * rounds,
    ).apply {
        if (jfr) {
            put("feature-bench/*-off.jfr", 1L)
            put("feature-bench/*-on.jfr", 1L)
        }
    }
}
