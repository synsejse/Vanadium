package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.entity.AccessibleEntities
import net.minecraft.server.level.ServerLevel
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

    private class SourceCapture {
        var entities: Iterable<Entity>? = null
    }

    private val sourceCapture = ThreadLocal<SourceCapture?>()

    @JvmStatic
    fun count(chunkCount: Int, level: ServerLevel, chunks: NaturalSpawner.ChunkGetter,
              localCaps: LocalMobCapCalculator, chunkMap: ChunkMap): NaturalSpawner.SpawnState {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelSpawning) {
            return NaturalSpawner.createState(chunkCount, level, chunks, localCaps)
        }
        return count(chunkCount, level, capture(chunkCount, level, chunks, localCaps), chunks, localCaps) { LocalMobCapCalculator(chunkMap) }
    }

    /**
     * The entity input `createState` would count, after other mods' wrappers (notably Lithium's section
     * iteration and C2ME's simulated-chunk filter). This empty pass creates only an empty state and reads no
     * entity: both wrappers are lazy, and per-entity work stays in the worker slices.
     */
    internal fun capture(chunkCount: Int, level: ServerLevel, chunks: NaturalSpawner.ChunkGetter,
                         localCaps: LocalMobCapCalculator): Iterable<Entity> {
        val capture = SourceCapture()
        sourceCapture.set(capture)
        try {
            NaturalSpawner.createState(chunkCount, level, chunks, localCaps)
        } finally {
            sourceCapture.remove()
        }
        return checkNotNull(capture.entities)
    }

    @JvmStatic
    fun count(
        chunkCount: Int,
        level: ServerLevel,
        entities: Iterable<Entity>,
        chunks: NaturalSpawner.ChunkGetter,
        localCaps: LocalMobCapCalculator,
        chunkMap: ChunkMap,
    ): NaturalSpawner.SpawnState = count(chunkCount, level, entities, chunks, localCaps) { LocalMobCapCalculator(chunkMap) }

    /** [jobCaps] makes each parallel job's empty local cap calculator (vanilla's: a fresh one over the chunk map). */
    internal fun count(
        chunkCount: Int,
        level: ServerLevel,
        entities: Iterable<Entity>,
        chunks: NaturalSpawner.ChunkGetter,
        localCaps: LocalMobCapCalculator,
        jobCaps: () -> LocalMobCapCalculator,
    ): NaturalSpawner.SpawnState {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelSpawning) {
            return createState(chunkCount, level, entities, chunks, localCaps)
        }
        // C2ME restricts counting to simulated chunks through an unsized filtering wrapper; unwrap it so
        // the per-entity chunk lookup runs inside the parallel batches instead of a serial copy.
        var source = entities
        var filter: Predicate<Entity>? = null
        val unwrapped = C2ME_FILTER?.takeIf { it.matches(entities) }?.delegate(entities)
        if (unwrapped is Collection<Entity> || unwrapped is AccessibleEntities<*>) {
            filter = C2ME_FILTER!!.predicate(entities)
            source = unwrapped
        }
        // Lithium's section iteration: split the section list by entity position without reading entities here.
        if (source is AccessibleEntities<*>) {
            @Suppress("UNCHECKED_CAST")
            val sections = (source as AccessibleEntities<Entity>).snapshot()
            return countSlices(chunkCount, level, sections.size, chunks, localCaps, jobCaps) { from, to ->
                sections.range(from, to, filter)
            }
        }
        if (source is Collection<Entity> && source.size < MIN_PARALLEL_ENTITIES) {
            return createState(chunkCount, level, source.filteredBy(filter), chunks, localCaps)
        }
        // Unsized, possibly single-pass iterables from other mods are copied once.
        val snapshot = if (source is Collection<Entity>) ArrayList(source) else source.toCollection(ArrayList())
        return countSlices(chunkCount, level, snapshot.size, chunks, localCaps, jobCaps) { from, to ->
            snapshot.subList(from, to).filteredBy(filter)
        }
    }

    /** Counts [size] entities, read through [slice] in contiguous ranges, in one preparation wave once there are enough. */
    private inline fun countSlices(
        chunkCount: Int,
        level: ServerLevel,
        size: Int,
        chunks: NaturalSpawner.ChunkGetter,
        localCaps: LocalMobCapCalculator,
        noinline jobCaps: () -> LocalMobCapCalculator,
        crossinline slice: (from: Int, to: Int) -> Iterable<Entity>,
    ): NaturalSpawner.SpawnState {
        if (size < MIN_PARALLEL_ENTITIES) return createState(chunkCount, level, slice(0, size), chunks, localCaps)
        val jobs = minOf(Vanadium.scheduler.workerCount + 1, (size + TARGET_ENTITIES_PER_JOB - 1) / TARGET_ENTITIES_PER_JOB)
        val batchSize = (size + jobs - 1) / jobs
        val partial = arrayOfNulls<NaturalSpawner.SpawnState>(jobs)
        Vanadium.scheduler.prepare(Preparation.SPAWN_COUNTS, List(jobs) { job ->
            val batch = slice(job * batchSize, minOf(size, (job + 1) * batchSize))
            Runnable {
                partial[job] = createState(chunkCount, level, batch, chunks, jobCaps())
            }
        })
        // Merge contiguous batches in source order, including charge order for floating-point sums.
        val result = partial[0]!!
        for (i in 1 until jobs) merge(result, partial[i]!!)
        return result
    }

    private val countingEntities = ThreadLocal<Iterable<Entity>?>()

    /** Use vanilla's level-aware 26.3 counting logic on one stable slice. */
    fun createState(chunkCount: Int, level: ServerLevel, entities: Iterable<Entity>,
                    chunks: NaturalSpawner.ChunkGetter, localCaps: LocalMobCapCalculator): NaturalSpawner.SpawnState {
        val previous = countingEntities.get()
        countingEntities.set(entities)
        try {
            return NaturalSpawner.createState(chunkCount, level, chunks, localCaps)
        } finally {
            if (previous == null) countingEntities.remove() else countingEntities.set(previous)
        }
    }

    @JvmStatic
    fun entitiesForCount(original: Iterable<Entity>): Iterable<Entity> {
        sourceCapture.get()?.let {
            it.entities = original
            return emptyList()
        }
        return countingEntities.get() ?: original
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
