package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.IdleWorkers
import com.synsenetwork.vanadium.tick.ServerSpin
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.block.Blocks

/**
 * `idleWorkers` against the real server at 20 TPS: `/vanadium set` switches between all three modes, and from the
 * next tick the pool parks its workers between ticks (`lean`, `balanced`) or keeps them spinning (`hot`), and arms
 * the server thread's spin for the next tick (`balanced`, `hot`) or not (`lean`). A frozen server keeps ticking;
 * with serial entities and tracking it runs nothing in parallel, so even `hot` parks everything and leaves the
 * server thread unarmed. Worker threads are observed only by their state: spinning is runnable, parked is waiting.
 */
internal object IdleWorkerChecks {
    private const val CHUNK_X = 40
    private const val CHUNK_Z = -40
    private const val FLOOR_Y = 200

    /** How long after a tick ends workers may take to park, or must keep spinning, in milliseconds. */
    private const val SETTLE_MILLIS = 20L

    fun schedule(level: ServerLevel, checks: TickedChecks) {
        val server = level.server
        val mobs = ArrayList<Mob>()
        val original = Vanadium.config.idleWorkers
        var workers = emptyList<Thread>()
        fun set(mode: IdleWorkers) {
            val source = server.createCommandSourceStack()
            check(server.commands.dispatcher.execute("vanadium set idleWorkers ${mode.configName}", source) == 1) {
                "/vanadium set idleWorkers ${mode.configName} failed"
            }
            check(Vanadium.config.idleWorkersMode() == mode)
        }

        checks.then("idle workers: force a chunk and build a pen") {
            check(level.setChunkForced(CHUNK_X, CHUNK_Z, true)) { "could not force idle-worker chunk" }
            level.getChunk(CHUNK_X, CHUNK_Z)
            val x0 = CHUNK_X * 16 + 1
            val z0 = CHUNK_Z * 16 + 1
            for (dx in 0..13) for (dz in 0..13) {
                level.setBlockAndUpdate(BlockPos(x0 + dx, FLOOR_Y, z0 + dz), Blocks.STONE.defaultBlockState())
                val wall = dx == 0 || dz == 0 || dx == 13 || dz == 13
                for (dy in 1..2) {
                    level.setBlockAndUpdate(BlockPos(x0 + dx, FLOOR_Y + dy, z0 + dz),
                        if (wall) Blocks.BARRIER.defaultBlockState() else Blocks.AIR.defaultBlockState())
                }
            }
            workers = Thread.getAllStackTraces().keys.filter { it.name.startsWith("Vanadium-Worker-") }
            check(workers.size == Vanadium.scheduler.workerCount) { "found ${workers.size} of ${Vanadium.scheduler.workerCount} workers" }
        }
        val center = BlockPos(CHUNK_X * 16 + 8, FLOOR_Y + 1, CHUNK_Z * 16 + 8)
        checks.awaitAsync("idle workers: the pen to tick entities", 100) { level.isPositionEntityTicking(center) }
        checks.then("idle workers: spawn enough mobs for parallel entity waves") {
            repeat(96) { i ->
                val mob = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND)!!
                mob.setPos(center.x + 0.5 + (i % 10) - 4.5, FLOOR_Y + 1.0, center.z + 0.5 + (i / 10) - 4.5)
                mob.setPersistenceRequired()
                check(level.addFreshEntity(mob)) { "could not add $mob" }
                mobs.add(mob)
            }
        }
        for (mode in listOf(IdleWorkers.HOT, IdleWorkers.BALANCED, IdleWorkers.LEAN, IdleWorkers.HOT)) {
            checks.then("idle workers: set $mode") { set(mode) }
            checks.awaitAsync("idle workers: $mode between paced ticks", 100) {
                // Applied at the next tick start; a tick that ran nothing in parallel arms nothing, so wait for one.
                Vanadium.idleWorkersActive() == mode && when (mode) {
                    IdleWorkers.LEAN -> ServerSpin.armedWindowNanos == 0L && allParkWithin(workers)
                    IdleWorkers.BALANCED -> ServerSpin.armedWindowNanos > 0L && allParkWithin(workers)
                    IdleWorkers.HOT -> ServerSpin.armedWindowNanos == Long.MAX_VALUE && someKeepSpinning(workers)
                }
            }
        }
        val entities = Vanadium.config.parallelEntities
        val tracking = Vanadium.config.parallelTracking
        var frozenTicks = -1
        checks.awaitAsync("idle workers: a frozen hot server keeps ticking", 100) {
            // Frozen here, in a paced step: ending a sprint restores the frozen state it began with. Frozen entities
            // are still dispatched (each only checks it is frozen) and tracking still runs, so with many entities a
            // frozen tick may run parallel waves and keep hot workers spinning.
            if (frozenTicks < 0) server.tickRateManager().setFrozen(true)
            check(server.tickRateManager().isFrozen) { "the server unfroze" }
            ++frozenTicks >= 3
        }
        checks.awaitAsync("idle workers: a frozen hot server without parallel work keeps nobody spinning", 100) {
            check(server.tickRateManager().isFrozen) { "the server unfroze" }
            if (Vanadium.config.parallelEntities || Vanadium.config.parallelTracking) {
                Vanadium.config.parallelEntities = false
                Vanadium.config.parallelTracking = false
                frozenTicks = 0
                return@awaitAsync false
            }
            // Three ticks in a row with nothing armed and every worker parked.
            if (ServerSpin.armedWindowNanos == 0L && allParkWithin(workers)) ++frozenTicks >= 3 else { frozenTicks = 0; false }
        }
        checks.then("idle workers: unfreeze and restore") {
            server.tickRateManager().setFrozen(false)
            Vanadium.config.parallelEntities = entities
            Vanadium.config.parallelTracking = tracking
            mobs.forEach { it.discard() }
            mobs.clear()
            set(IdleWorkers.parse(original)!!)
            level.setChunkForced(CHUNK_X, CHUNK_Z, false)
            println("IDLE_WORKER_CHECKS_PASSED")
        }
    }

    /** Whether every worker is parked within [SETTLE_MILLIS]: none may still be spinning towards the next tick. */
    private fun allParkWithin(workers: List<Thread>): Boolean {
        val deadline = System.nanoTime() + SETTLE_MILLIS * 1_000_000L
        while (true) {
            if (workers.all { it.state == Thread.State.WAITING }) return true
            if (System.nanoTime() - deadline > 0) return false
            Thread.onSpinWait()
        }
    }

    /** Whether some worker is still runnable (spinning) after [SETTLE_MILLIS], when a parking one would be long parked. */
    private fun someKeepSpinning(workers: List<Thread>): Boolean {
        Thread.sleep(SETTLE_MILLIS)
        return workers.any { it.state == Thread.State.RUNNABLE }
    }
}
