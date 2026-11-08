package com.synsenetwork.vanadium.tick

/**
 * One grid square's worth of queued tick tasks, run serially in insertion order. Reused across ticks:
 * [clearTasks] keeps the list's capacity, and [lastActiveTick] drives idle eviction in [CellGrid].
 * Coordinates are in cell units.
 */
class Cell(val cellX: Int, val cellZ: Int) : Runnable {
    private val tasks = ArrayList<Runnable>()

    var lastActiveTick = 0L
        private set

    /** Position in the current [NeighborhoodWave]; written by the server thread before the wave. */
    internal var waveIndex = 0

    val taskCount: Int get() = tasks.size

    fun add(task: Runnable) {
        tasks.add(task)
    }

    fun hasTasks(): Boolean = tasks.isNotEmpty()

    fun clearTasks() = tasks.clear()

    fun markActive(tick: Long) {
        lastActiveTick = tick
    }

    override fun run() {
        for (task in tasks) task.run()
    }

    /** Detailed diagnostics only; the normal tick loop does not invoke a per-task observer. */
    internal inline fun run(beforeTask: (Runnable) -> Unit) {
        for (task in tasks) {
            beforeTask(task)
            task.run()
        }
    }
}
