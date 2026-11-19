package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.live.TestPlayer
import com.synsenetwork.vanadium.live.runCommand
import com.synsenetwork.vanadium.tick.IdleWorkers
import com.synsenetwork.vanadium.tick.ServerSpin
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * `idleWorkers` against the real server at 20 TPS: `/vanadium set` switches between all three modes, and from the
 * next tick the pool parks its workers between ticks (`lean`, `balanced`) or keeps them spinning (`hot`), and arms
 * the server thread's spin for the next tick (`balanced`, `hot`) or not (`lean`). A frozen server keeps ticking, but
 * as vanilla it ticks only players and the vehicles carrying them, serially: with tracking serial too it runs nothing
 * in parallel, so even `hot` parks everything and leaves the server thread unarmed, until `/tick step` runs the entity
 * wave again. Worker threads are observed only by their state: spinning is runnable, parked is waiting.
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
        val tracking = Vanadium.config.parallelTracking
        var rider: TestPlayer? = null
        // Ticks of the ridden pig and of every other pig since the game froze, and frozen ticks since then.
        val frozenAt = HashMap<Mob, Int>()
        var frozenTicks = -1
        fun pigTicks(mob: Mob) = mob.tickCount - frozenAt.getValue(mob)
        checks.then("idle workers: a player rides one pig") {
            val player = TestPlayer(level, "IdleRider", Vec3.atBottomCenterOf(center)).also { rider = it }.player
            check(player.startRiding(mobs[0], true, false)) { "the player could not ride ${mobs[0]}" }
        }
        checks.awaitAsync("idle workers: a frozen hot server keeps ticking", 100) {
            // Frozen here, in a paced step: ending a sprint restores the frozen state it began with. The game stops
            // running normally from the next tick.
            if (frozenTicks < 0) server.tickRateManager().setFrozen(true)
            check(server.tickRateManager().isFrozen) { "the server unfroze" }
            if (++frozenTicks == 1) mobs.forEach { frozenAt[it] = it.tickCount }
            frozenTicks >= 3
        }
        var parkedTicks = 0
        checks.awaitAsync("idle workers: a frozen hot server ticks only the ridden pig, and nothing in parallel", 100) {
            check(server.tickRateManager().isFrozen) { "the server unfroze" }
            frozenTicks++
            // Frozen pigs never tick; the one carrying a player does, every tick, as in vanilla.
            check(mobs.drop(1).all { pigTicks(it) == 0 }) { "a frozen pig ticked" }
            check(pigTicks(mobs[0]) == frozenTicks - 1) { "the ridden pig ticked ${pigTicks(mobs[0])} of ${frozenTicks - 1} frozen ticks" }
            // Entities no longer wake workers while frozen; tracking (vanilla's work too) is made serial so nothing else does.
            if (Vanadium.config.parallelTracking) {
                Vanadium.config.parallelTracking = false
                return@awaitAsync false
            }
            check(Vanadium.config.parallelEntities) { "the entity wave must stay enabled for this check" }
            // Three ticks in a row with nothing armed and every worker parked.
            if (ServerSpin.armedWindowNanos == 0L && allParkWithin(workers)) ++parkedTicks >= 3 else { parkedTicks = 0; false }
        }
        var steppedParallel = false
        // Paced like the frozen steps: an unpaced step sprints, which unfreezes the game until the sprint ends.
        checks.awaitAsync("idle workers: step two ticks", 1) {
            check(server.runCommand("tick step 2") == 1) { "/tick step 2 failed" }
            mobs.forEach { frozenAt[it] = it.tickCount }
            true
        }
        checks.awaitAsync("idle workers: stepped ticks run the entity wave on hot workers", 20) {
            // A stepped tick runs normally: every pig ticks, and the wave makes it a parallel one.
            if (ServerSpin.armedWindowNanos == Long.MAX_VALUE) steppedParallel = true
            if (server.tickRateManager().isSteppingForward || mobs.any { pigTicks(it) < 2 }) return@awaitAsync false
            check(mobs.all { pigTicks(it) == 2 }) { "pigs ticked ${mobs.map(::pigTicks).toSet()} times over two stepped ticks" }
            check(steppedParallel) { "no stepped tick ran in parallel" }
            true
        }
        checks.then("idle workers: unfreeze and restore") {
            server.tickRateManager().setFrozen(false)
            Vanadium.config.parallelTracking = tracking
            rider?.close()
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
