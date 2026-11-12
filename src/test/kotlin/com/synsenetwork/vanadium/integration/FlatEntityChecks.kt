package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.concurrent.InteractionAccess
import com.synsenetwork.vanadium.concurrent.Interactions
import com.synsenetwork.vanadium.concurrent.Interactions.Kind
import com.synsenetwork.vanadium.tick.EntityTickRules
import com.synsenetwork.vanadium.tick.WorkerPool
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Entities ticking in one wave with no isolation: what they do to each other stays whole and never deadlocks. */
internal object FlatEntityChecks {
    fun run(level: ServerLevel) {
        Interactions.takeCounts()
        val unprotected = Interactions.timeouts + Interactions.cycles
        val skipped = Interactions.skipped
        WorkerPool(3).use { pool ->
            checkPushesAreNeverLost(level, pool)
            checkLeftoverPushesApplyBeforeTheWaveReturns(level, pool)
            checkForeignDamageWaitsForTheOwner(level, pool)
            checkMutualDamage(level, pool)
            checkOwnAndSharedWrites(level, pool)
            checkAFailedTickIsSkippedOnce(level, pool)
        }
        check(Interactions.timeouts + Interactions.cycles == unprotected) { "an interaction ran unprotected" }
        check(Interactions.skipped == skipped + 3) { "failed ticks were not counted: ${Interactions.skipped - skipped}" }
        check(!Interactions.active) { "interaction hooks stayed active after the waves" }
        val tnt = EntityTypes.TNT.create(level, EntitySpawnReason.COMMAND)!!
        check(EntityTickRules.requiresSerial(tnt) && !EntityTickRules.requiresSerial(zombie(level))) { "serial rules for flat ticking are wrong" }
        println("FLAT_ENTITY_CHECKS_PASSED")
    }

    private fun zombie(level: ServerLevel): LivingEntity = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND)!!

    private fun wave(pool: WorkerPool, vararg ticks: Pair<Entity, (Entity) -> Unit>) {
        val tasks = ticks.map { (entity, body) -> Runnable { Interactions.tick(entity, Consumer(body)) } }
        Interactions.runWave { pool.runWave(tasks) }
    }

    /** Eight ticks push one bystander and each other at once: every impulse arrives exactly once. */
    private fun checkPushesAreNeverLost(level: ServerLevel, pool: WorkerPool) {
        val bystander = zombie(level)
        val pushers = List(8) { zombie(level) }
        val start = CyclicBarrier(4)
        wave(pool, *pushers.mapIndexed { i, pusher ->
            pusher to { _: Entity ->
                if (i < 4) start.await(10, TimeUnit.SECONDS)
                repeat(100) {
                    bystander.push(1.0, 0.0, 0.0)
                    pushers[(i + 1) % pushers.size].push(0.0, 0.0, 1.0)
                }
                pusher.push(0.0, 2.0, 0.0) // Own push: applied immediately.
                check(pusher.deltaMovement.y == 2.0) { "own push was deferred" }
            }
        }.toTypedArray())
        check(bystander.deltaMovement == Vec3(800.0, 0.0, 0.0)) { "bystander got ${bystander.deltaMovement}" }
        for (pusher in pushers) {
            check(pusher.deltaMovement == Vec3(0.0, 2.0, 100.0)) { "pusher got ${pusher.deltaMovement}" }
            check((pusher as InteractionAccess).`vanadium$impulse`().get() == null) { "an inbox was left full" }
        }
        val counts = Interactions.takeCounts()
        check(counts[Kind.PUSH] == 1600L && counts[Kind.VELOCITY] == 0L) { "push counts: $counts" }
    }

    /**
     * Pushes on entities that do not tick in the wave (or ticked before them) are left in their inboxes. Many of
     * them apply as a parallel wave, each target owned as in its tick; either way, before the wave returns, and
     * a removed entity's push is dropped as before.
     */
    private fun checkLeftoverPushesApplyBeforeTheWaveReturns(level: ServerLevel, pool: WorkerPool) {
        for (bystanders in listOf(8, 400)) {
            val targets = List(bystanders) { zombie(level) }
            val removed = zombie(level).also { it.discard() }
            val pushers = List(4) { zombie(level) }
            val start = CyclicBarrier(4)
            val drainsBefore = Interactions.parallelPushDrains
            val tasks = pushers.map { pusher ->
                Runnable {
                    Interactions.tick(pusher) {
                        start.await(10, TimeUnit.SECONDS)
                        for (target in targets) target.push(0.5, 0.0, 0.0)
                        removed.push(1.0, 0.0, 0.0)
                    }
                }
            }
            Interactions.runWave({ pool.runWave(tasks) }, pool)
            for (target in targets) {
                check(target.deltaMovement == Vec3(2.0, 0.0, 0.0)) { "a bystander got ${target.deltaMovement} of $bystanders" }
                check((target as InteractionAccess).`vanadium$impulse`().get() == null) { "a bystander's inbox was left full" }
            }
            check(removed.deltaMovement == Vec3.ZERO) { "a removed entity was pushed: ${removed.deltaMovement}" }
            val parallel = Interactions.parallelPushDrains - drainsBefore
            check(parallel == if (bystanders >= 128) 1L else 0L) { "$bystanders leftovers drained in $parallel parallel rounds" }
            val counts = Interactions.takeCounts()
            check(counts[Kind.PUSH] == 4L * (bystanders + 1) && counts[Kind.VELOCITY] == 0L) { "leftover push counts: $counts" }
        }
    }

    /** Damage from another tick lands only after the victim's own tick has finished. */
    private fun checkForeignDamageWaitsForTheOwner(level: ServerLevel, pool: WorkerPool) {
        val victim = zombie(level)
        val attacker = zombie(level)
        val full = victim.health
        val victimTicking = CountDownLatch(1)
        var healthAtEndOfOwnTick = -1f
        wave(pool,
            victim to { _: Entity ->
                victimTicking.countDown()
                // The attacker can only be queued on this entity's lock; then finish with it still waiting.
                val lock = (victim as InteractionAccess).`vanadium$tickLock`()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!lock.hasQueuedThreads()) {
                    check(System.nanoTime() < deadline) { "the attacker never waited for the victim's tick" }
                    Thread.onSpinWait()
                }
                healthAtEndOfOwnTick = victim.health
            },
            attacker to { _: Entity ->
                check(victimTicking.await(10, TimeUnit.SECONDS)) { "victim tick gate timed out" }
                check(victim.hurtServer(level, level.damageSources().generic(), 3f)) { "damage was rejected" }
            })
        check(healthAtEndOfOwnTick == full) { "damage landed in the middle of the victim's tick" }
        check(victim.health == full - 3f) { "damage was lost: ${victim.health}" }
        check(Interactions.takeCounts()[Kind.HURT] == 1L) { "damage was not a single foreign write" }
    }

    /** Two ticks damaging each other at the same moment both land, without waiting on each other forever. */
    private fun checkMutualDamage(level: ServerLevel, pool: WorkerPool) {
        val a = zombie(level)
        val b = zombie(level)
        val full = a.health
        val both = CyclicBarrier(2)
        val fight = { victim: LivingEntity ->
            { _: Entity ->
                both.await(10, TimeUnit.SECONDS)
                repeat(50) {
                    victim.invulnerableTime = 0
                    victim.hurtServer(level, level.damageSources().generic(), 0.1f)
                }
            }
        }
        wave(pool, a to fight(b), b to fight(a))
        check(a.health < full && b.health < full) { "mutual damage was lost: ${a.health}, ${b.health}" }
        Interactions.takeCounts()
    }

    /** One failed tick is skipped; an entity that keeps failing still stops the wave. */
    private fun checkAFailedTickIsSkippedOnce(level: ServerLevel, pool: WorkerPool) {
        val flaky = zombie(level)
        val healthy = zombie(level)
        var healthyTicks = 0
        var fail = true
        val ticks = arrayOf<Pair<Entity, (Entity) -> Unit>>(
            flaky to { _: Entity -> if (fail) throw NoSuchElementException("removed by another tick") },
            healthy to { _: Entity -> healthyTicks++ })
        wave(pool, *ticks)
        fail = false
        wave(pool, *ticks) // A clean tick resets the count.
        fail = true
        wave(pool, *ticks)
        wave(pool, *ticks)
        val failure = runCatching { wave(pool, *ticks) }.exceptionOrNull()
        check(failure != null && generateSequence(failure) { it.cause }.any { it is NoSuchElementException }) {
            "an entity failing three ticks in a row did not stop the wave: $failure"
        }
        check(healthyTicks == 5) { "a failed tick disturbed another entity's ticks: $healthyTicks" }
        check(!Interactions.active) { "a failed wave left the hooks active" }
    }

    /** A tick's writes to itself take no lock; its block changes and writes to others are counted foreign writes. */
    private fun checkOwnAndSharedWrites(level: ServerLevel, pool: WorkerPool) {
        val self = zombie(level)
        val other = zombie(level)
        val pos = BlockPos(3, level.maxY - 4, 3)
        level.getChunkAt(pos)
        wave(pool,
            self to { _: Entity ->
                self.setDeltaMovement(1.0, 2.0, 3.0)
                self.setPos(1.0, 80.0, 1.0)
                self.health = 5f
                check(Interactions.takeCounts().values.all { it == 0L }) { "own writes were treated as foreign" }
                check(level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3)) { "block change failed" }
                other.setDeltaMovement(4.0, 5.0, 6.0)
                other.setPos(2.0, 80.0, 2.0)
                other.health = 6f
            },
            other to { _: Entity -> })
        check(level.getBlockState(pos).`is`(Blocks.STONE)) { "block change was lost" }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
        check(other.deltaMovement == Vec3(4.0, 5.0, 6.0) && other.x == 2.0 && other.health == 6f) { "foreign writes were lost" }
        val counts = Interactions.takeCounts()
        check(counts[Kind.BLOCK] == 1L && counts[Kind.VELOCITY] == 1L && counts[Kind.POSITION]!! >= 1L && counts[Kind.DATA]!! >= 1L) {
            "foreign write counts: $counts"
        }
    }
}
