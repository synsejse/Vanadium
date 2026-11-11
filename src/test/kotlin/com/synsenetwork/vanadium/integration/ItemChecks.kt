package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.concurrent.Interactions
import com.synsenetwork.vanadium.tick.WorkerPool
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.gamerules.GameRules
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Items in the entity wave: stacks merging into each other and mobs taking them never duplicate or lose items. */
internal object ItemChecks {
    fun run(level: ServerLevel) {
        val unprotected = Interactions.timeouts + Interactions.cycles
        WorkerPool(3).use { pool ->
            repeat(16) { checkMerges(level, pool) }
            repeat(8) { checkOneMobTakesAnItem(level, pool) }
        }
        check(Interactions.timeouts + Interactions.cycles == unprotected) { "an item interaction ran unprotected" }
        println("ITEM_MERGE_PICKUP_CHECKS_PASSED merges=16 pickups=8")
    }

    private fun wave(pool: WorkerPool, ticks: List<Pair<Entity, (Entity) -> Unit>>) {
        val tasks = ticks.map { (entity, body) -> Runnable { Interactions.tick(entity, Consumer(body)) } }
        Interactions.runWave { pool.runWave(tasks) }
    }

    /** Every stack's tick merges with both neighbours while they do the same in reverse. */
    private fun checkMerges(level: ServerLevel, pool: WorkerPool) {
        val merge = ItemEntity::class.java.getDeclaredMethod("tryToMerge", ItemEntity::class.java).apply { isAccessible = true }
        val items = List(32) { ItemEntity(level, 0.0, 100.0, 0.0, ItemStack(Items.STONE, 16)) }
        val start = CyclicBarrier(4)
        wave(pool, items.mapIndexed { i, item ->
            item to { _: Entity ->
                if (i < 4) start.await(10, TimeUnit.SECONDS)
                repeat(8) {
                    merge.invoke(item, items[(i + 1) % items.size])
                    merge.invoke(item, items[(i + items.size - 1) % items.size])
                }
            }
        })
        val left = items.filterNot { it.isRemoved }
        val total = left.sumOf { it.item.count }
        check(total == 512) { "concurrent merges changed the total: $total" }
        check(left.all { it.item.count in 1..64 }) { "a merge overflowed or emptied a stack" }
        check(left.size < items.size) { "nothing merged" }
    }

    /** Six mobs reach for one sword in the same tick: one gets it. */
    private fun checkOneMobTakesAnItem(level: ServerLevel, pool: WorkerPool) {
        check(level.gameRules.get(GameRules.MOB_GRIEFING)) { "fixture world must allow mob griefing" }
        val sword = ItemEntity(level, 0.5, 120.0, 0.5, ItemStack(Items.IRON_SWORD))
        sword.setNoGravity(true)
        sword.setNoPickUpDelay()
        check(level.addFreshEntity(sword)) { "item was not added" }
        try {
            val mobs = List(6) {
                EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND)!!.apply {
                    setPos(0.5, 120.0, 0.5)
                    setNoGravity(true)
                    setCanPickUpLoot(true)
                }
            }
            val start = CyclicBarrier(4)
            wave(pool, mobs.mapIndexed { i, mob ->
                mob to { _: Entity ->
                    if (i < 4) start.await(10, TimeUnit.SECONDS)
                    mob.aiStep()
                }
            })
            val holders = mobs.count { it.mainHandItem.`is`(Items.IRON_SWORD) }
            check(holders == 1 && sword.isRemoved) { "$holders mobs took one sword (removed=${sword.isRemoved})" }
        } finally {
            if (!sword.isRemoved) sword.discard()
        }
    }
}
