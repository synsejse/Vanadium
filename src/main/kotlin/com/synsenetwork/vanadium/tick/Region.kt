package com.synsenetwork.vanadium.tick

/**
 * A rectangle of chunks planned for a stage's waves; its tasks run serially in enqueue order. Regions are
 * pooled by their [RegionGrid], which replans them as the work moves. Coordinates are chunk coordinates, inclusive.
 */
class Region : Runnable {
    var minX = 0
        internal set
    var minZ = 0
        internal set
    var maxX = 0
        internal set
    var maxZ = 0
        internal set

    internal val tasks = ArrayList<Runnable>()

    /** Position in the current plan; written by the server thread before the wave. */
    internal var waveIndex = 0

    /** Tasks when the plan was made; orders regions heaviest first. */
    internal var weight = 0

    val taskCount: Int get() = tasks.size

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

    override fun toString(): String = "chunks ($minX,$minZ)..($maxX,$maxZ)"
}
