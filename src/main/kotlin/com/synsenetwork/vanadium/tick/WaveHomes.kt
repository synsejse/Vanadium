package com.synsenetwork.vanadium.tick

/**
 * Where a wave's tasks belong, so that each participant runs the same tasks from wave to wave and their data
 * stays in its core's caches ([WorkerPool.runWave]). The task list is cut into [phases] runs, each into [homes]
 * cells: cell (phase, home) holds tasks [starts] at phase * homes + home until the next start. Home h belongs to
 * participant slot h mod p of a wave with p participants (the caller is slot 0, worker i slot i + 1): with
 * homes = workers + 1 and every worker in the wave, each slot owns one home, and a wave with fewer participants
 * moves only some homes to another owner.
 *
 * Participants claim phase by phase: their own homes' cells from the front, then other homes' cells of the same
 * phase from the end, nearest home first. Order within a phase is otherwise free.
 *
 * Owned by the wave's caller; [WorkerPool.runWave] copies it before publishing the wave.
 */
class WaveHomes(homes: Int) {
    var homes: Int = homes
        private set
    var phases: Int = 0
        private set

    /** Cell starts, phase-major, then the list's end: [phases] * [homes] + 1 used. */
    var starts: IntArray = IntArray(homes + 1)
        private set

    /** Starts a layout of [phases] phases over [homes] homes; then [set] every cell start and the end. */
    fun reset(phases: Int, homes: Int = this.homes) {
        require(phases >= 0 && homes >= 1)
        this.phases = phases
        this.homes = homes
        if (starts.size < phases * homes + 1) starts = IntArray(phases * homes + 1)
    }

    /** The first task of (phase, home), or with phase = [phases] and home 0, the list's end. */
    fun set(phase: Int, home: Int, start: Int) {
        starts[phase * homes + home] = start
    }
}
