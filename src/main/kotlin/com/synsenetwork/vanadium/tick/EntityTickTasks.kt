package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.concurrent.Interactions
import com.synsenetwork.vanadium.config.DeveloperProperties
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
     * entity types with the costliest ticks first ([PersistentTickTasks]), on as many threads as finish the rest
     * while the longest tick runs ([TickScheduler.flatParticipants]). The wave has a home per possible participant
     * ([WaveHomes]): each thread ticks the same entities from tick to tick, so their data stays in its core's caches.
     *
     * When the game does not [runsNormally] (`/tick freeze`, between `/tick step`s), vanilla's body skips every
     * entity but players and vehicles carrying one (`TickRateManager.isEntityFrozen`), so a wave would wake workers
     * only to check each entity. The tick list then runs [body] serially on this thread, exactly as vanilla does;
     * tracking keeps collecting membership changes, and the next normal tick dispatches them.
     */
    fun dispatch(scheduler: TickScheduler, body: Consumer<Entity>, runsNormally: Boolean) {
        if (!runsNormally) {
            list.forEach(body)
            return
        }
        tasks.dispatch(Vanadium.config.serialEntityRulesKey(), scheduler.detailedDiagnostics,
            if (HOMES) scheduler.workerCount + 1 else 0,
            { entities, longest -> scheduler.flatParticipants(Stage.ENTITY, entities, longest) }, body,
            list::forEach) { wave, entities, participants, homes ->
            scheduler.runFlat(Stage.ENTITY, wave, entities, participants, homes)
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

    private companion object {
        /** Whether the entity wave has homes ([WaveHomes]); `-Dvanadium.entityHomes=false` claims it in list order. */
        private val HOMES = DeveloperProperties.boolean("vanadium.entityHomes", true)
    }
}
