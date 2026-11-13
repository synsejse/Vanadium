package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.concurrent.Interactions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.entity.EntityTickList
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import java.util.function.Consumer

/**
 * Per-level ENTITY collection: vanilla's whole per-entity body runs as each entity's task, and the tasks form
 * one wave with no regions. Each task owns its entity ([Interactions]). Tasks persist across ticks
 * ([PersistentTickTasks]): the tick list reports adds and removes, and portal entry and mounting report
 * possible serial-rule changes, so an unchanged level dispatches without visiting its entities.
 * [active] is the tick list's map, which `EntityTickListMixin` installs once and never swaps.
 */
class EntityTickTasks(private val list: EntityTickList, private val active: Int2ObjectMap<Entity>) : PersistentTickTasks.Rules<Entity> {
    val tasks = PersistentTickTasks(this)

    /**
     * Runs this tick's entities: serial-rule entities inline with [body], in order, then the rest as one wave,
     * costliest entity types first ([PersistentTickTasks]).
     */
    fun dispatch(scheduler: TickScheduler, body: Consumer<Entity>) {
        tasks.dispatch(Vanadium.config.serialEntityRulesKey(), scheduler.detailedDiagnostics, scheduler.workerCount + 1, body,
            list::forEach) { wave, entities ->
            scheduler.runFlat(Stage.ENTITY, wave, entities)
        }
        scheduler.profile?.let { tasks.recordLastWave(it, Stage.ENTITY) }
    }

    override fun id(item: Entity): Int = item.id

    /** By identity: a respawned player reuses the old player's id. [active] is never swapped (see the mixin). */
    override fun isMember(item: Entity): Boolean = active.get(item.id) === item

    override fun fixedSerial(item: Entity): Boolean = EntityTickRules.fixedSerial(item)

    override fun changingSerial(item: Entity): Boolean = EntityTickRules.changingSerial(item)

    override fun owner(item: Entity): Entity? = item.vehicle

    override fun label(item: Entity): String = BuiltInRegistries.ENTITY_TYPE.getKey(item.type).toString()

    override fun kind(item: Entity): Any = item.type

    override fun tick(item: Entity, body: Consumer<Entity>) = Interactions.tick(item, body)
}
