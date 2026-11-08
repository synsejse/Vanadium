package com.synsenetwork.vanadium.tick

/** Reusable cell of tasks run serially in insertion order. Coordinates are in cell units. */
class Cell(val cellX: Int, val cellZ: Int) : Runnable {
    private val tasks = ArrayList<Runnable>()

    var lastActiveTick = 0L
        internal set

    /** Position in the current [NeighborhoodWave]; written by the server thread before the wave. */
    internal var waveIndex = 0

    val taskCount: Int get() = tasks.size

    val hasTasks: Boolean get() = tasks.isNotEmpty()

    fun add(task: Runnable) {
        tasks.add(task)
    }

    fun clearTasks() = tasks.clear()

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
