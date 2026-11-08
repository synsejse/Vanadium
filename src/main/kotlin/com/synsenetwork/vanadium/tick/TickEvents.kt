package com.synsenetwork.vanadium.tick

import jdk.jfr.Category
import jdk.jfr.Description
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

/**
 * JFR events for Vanadium's parallel sections. They cost a branch when no recording is running, and
 * share a timeline with JFR's monitor, park and allocation events. Server-thread only.
 * Summarize with `python3 scripts/jfr-summary.py <recording.jfr>`.
 */
@Name("vanadium.ServerTick")
@Label("Vanadium Server Tick")
@Category("Vanadium")
@Description("Server tick work, from START_SERVER_TICK to END_SERVER_TICK (idle time excluded)")
@StackTrace(false)
class ServerTickEvent : Event()

@Name("vanadium.Wave")
@Label("Vanadium Stage Wave")
@Category("Vanadium")
@Description("One stage's neighborhood-exclusion wave; the server thread participates")
@StackTrace(false)
class WaveEvent : Event() {
    @field:Label("Stage")
    @JvmField var stage: String? = null

    @field:Label("Dimension")
    @JvmField var dimension: String? = null

    @field:Label("Cells")
    @JvmField var cells = 0

    @field:Label("Tasks")
    @JvmField var tasks = 0

    @field:Label("Participants")
    @field:Description("Threads that can claim cells, including the server thread")
    @JvmField var participants = 0

    @field:Label("Cell Size")
    @JvmField var cellSize = 0
}

@Name("vanadium.Preparation")
@Label("Vanadium Preparation")
@Category("Vanadium")
@Description("Independent preparation jobs run across the worker pool")
@StackTrace(false)
class PreparationEvent : Event() {
    @field:Label("Kind")
    @JvmField var kind: String? = null

    @field:Label("Jobs")
    @JvmField var jobs = 0

    @field:Label("Participants")
    @JvmField var participants = 0
}
