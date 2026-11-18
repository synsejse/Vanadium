package com.synsenetwork.vanadium.mixedbench

import jdk.jfr.Category
import jdk.jfr.Description
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

/**
 * Tick IDs and benchmark boundaries on the JFR timeline, matching `mixed-bench/ticks.csv` and `boundaries.csv`.
 * Instant events committed on the server thread when a tick completes or a boundary is marked.
 */
@Name("vanadium.MixedBenchTick")
@Label("Mixed Bench Tick")
@Category("Vanadium", "Mixed Bench")
@Description("A completed server tick: its ID, vanilla tickServer duration and completion uptime")
@StackTrace(false)
class MixedBenchTickEvent : Event() {
    @field:Label("Tick")
    @JvmField var tick = 0

    @field:Label("Phase")
    @JvmField var phase: String? = null

    @field:Label("Window")
    @JvmField var window = -1

    @field:Label("Tick Duration (ns)")
    @JvmField var tickNanos = 0L

    @field:Label("Completion Uptime (ns)")
    @JvmField var endUptimeNanos = 0L
}

@Name("vanadium.MixedBenchBoundary")
@Label("Mixed Bench Boundary")
@Category("Vanadium", "Mixed Bench")
@Description("A phase or window boundary of the mixed benchmark")
@StackTrace(false)
class MixedBenchBoundaryEvent : Event() {
    @field:Label("Boundary")
    @JvmField var boundary: String? = null

    @field:Label("Window")
    @JvmField var window = -1

    @field:Label("Last Completed Tick")
    @JvmField var tick = 0

    @field:Label("Tick Completion Uptime (ns)")
    @JvmField var endUptimeNanos = 0L
}
