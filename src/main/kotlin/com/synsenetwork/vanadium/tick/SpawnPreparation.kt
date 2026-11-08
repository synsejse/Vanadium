package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.Vanadium
import net.minecraft.server.level.ChunkMap
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.LocalMobCapCalculator
import net.minecraft.world.level.NaturalSpawner
import java.lang.reflect.Field
import java.util.function.Predicate

/** Count stable entity batches independently, then combine state before any spawn callback can run. */
object SpawnPreparation {
    private const val MIN_PARALLEL_ENTITIES = 1024
    private const val TARGET_ENTITIES_PER_JOB = 256
    private val C2ME_FILTER = FilteredSource.find("com.ishland.c2me.base.common.util.FilteringIterable")

    @JvmStatic
    fun count(
        chunkCount: Int,
        entities: Iterable<Entity>,
        chunks: NaturalSpawner.ChunkGetter,
        localCaps: LocalMobCapCalculator,
        chunkMap: ChunkMap,
    ): NaturalSpawner.SpawnState {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelSpawning) {
            return NaturalSpawner.createState(chunkCount, entities, chunks, localCaps)
        }
        // C2ME restricts counting to simulated chunks through an unsized filtering wrapper; unwrap it so
        // the per-entity chunk lookup runs inside the parallel batches instead of a serial copy.
        var source = entities
        var filter: Predicate<Entity>? = null
        val unwrapped = C2ME_FILTER?.takeIf { it.matches(entities) }?.delegate(entities)
        if (unwrapped is Collection<Entity>) {
            filter = C2ME_FILTER!!.predicate(entities)
            source = unwrapped
        }
        if (source is Collection<Entity> && source.size < MIN_PARALLEL_ENTITIES) {
            return NaturalSpawner.createState(chunkCount, source.filteredBy(filter), chunks, localCaps)
        }
        // Unsized, possibly single-pass iterables from other mods are copied once.
        val snapshot = if (source is Collection<Entity>) ArrayList(source) else source.toCollection(ArrayList())
        if (snapshot.size < MIN_PARALLEL_ENTITIES) {
            return NaturalSpawner.createState(chunkCount, snapshot.filteredBy(filter), chunks, localCaps)
        }

        val jobs = minOf(Vanadium.scheduler.workerCount + 1, (snapshot.size + TARGET_ENTITIES_PER_JOB - 1) / TARGET_ENTITIES_PER_JOB)
        val batchSize = (snapshot.size + jobs - 1) / jobs
        val partial = arrayOfNulls<NaturalSpawner.SpawnState>(jobs)
        Vanadium.scheduler.prepare(List(jobs) { job ->
            val batch = snapshot.subList(job * batchSize, minOf(snapshot.size, (job + 1) * batchSize))
            Runnable {
                partial[job] = NaturalSpawner.createState(chunkCount, batch.filteredBy(filter), chunks, LocalMobCapCalculator(chunkMap))
            }
        })
        // Merge contiguous batches in source order, including charge order for floating-point sums.
        val result = partial[0]!!
        for (i in 1 until jobs) merge(result, partial[i]!!)
        return result
    }

    internal fun merge(result: NaturalSpawner.SpawnState, state: NaturalSpawner.SpawnState) {
        for (entry in state.mobCategoryCounts.object2IntEntrySet()) {
            result.mobCategoryCounts.addTo(entry.key, entry.intValue)
        }
        result.spawnPotential.charges.addAll(state.spawnPotential.charges)
        val target = result.localMobCapCalculator
        val source = state.localMobCapCalculator
        target.playersNearChunk.putAll(source.playersNearChunk)
        source.playerMobCounts.forEach { (player, counts) ->
            val existing = target.playerMobCounts[player]
            if (existing == null) {
                target.playerMobCounts[player] = counts
            } else {
                for (entry in counts.counts.object2IntEntrySet()) existing.counts.mergeInt(entry.key, entry.intValue, Integer::sum)
            }
        }
    }

    /** A lazy filtered view; createState iterates it once. */
    private fun Iterable<Entity>.filteredBy(filter: Predicate<Entity>?): Iterable<Entity> =
        if (filter == null) this else Iterable { iterator().asSequence().filter(filter::test).iterator() }

    /** Reflective view of a mod's `(delegate, predicate)` filtering iterable. */
    private class FilteredSource(private val type: Class<*>, private val delegate: Field, private val predicate: Field) {
        fun matches(entities: Iterable<Entity>): Boolean = entities.javaClass == type

        @Suppress("UNCHECKED_CAST")
        fun delegate(entities: Iterable<Entity>): Iterable<Entity> = delegate.get(entities) as Iterable<Entity>

        @Suppress("UNCHECKED_CAST")
        fun predicate(entities: Iterable<Entity>): Predicate<Entity> = predicate.get(entities) as Predicate<Entity>

        companion object {
            /** Null when the mod is absent or its shape changed: callers keep the generic snapshot path. */
            fun find(className: String): FilteredSource? = try {
                val type = Class.forName(className, false, SpawnPreparation::class.java.classLoader)
                FilteredSource(type,
                    type.getDeclaredField("delegate").apply { isAccessible = true },
                    type.getDeclaredField("predicate").apply { isAccessible = true })
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}
