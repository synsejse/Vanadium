package com.synsenetwork.vanadium.spawning

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.WorkerPool
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LocalMobCapCalculator
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.gamerules.GameRules

/**
 * One level's natural-spawn count (`ServerChunkCache.tickChunks`' `NaturalSpawner.createState`), owned by its
 * [com.synsenetwork.vanadium.stage.ChunkStage] and skipped while
 * nothing can use it.
 *
 * Vanilla 26.3 counts every tick, even when `spawn_mobs` is false. The count then decides nothing: the spawn
 * categories are empty, so no chunk reads it, and custom spawners do not run. It is only stored as the chunk
 * source's `lastSpawnState`, which `getLastSpawnState` hands to the client debug screen (integrated servers),
 * to `/perf`'s debug report and to mods. On a dedicated server with `spawn_mobs` false, the tick therefore stores
 * an empty placeholder (with vanilla's spawnable chunk count) and counts nothing until the state is first read:
 * - read on the server thread outside a parallel wave, it is counted then, from the current entities, stored as
 *   `lastSpawnState` and returned; vanilla's would be from the last chunk tick, before that tick's entity
 *   updates. Every later tick counts like vanilla.
 * - read anywhere else (another thread, or the server thread while workers run), nothing can count safely:
 *   it returns null, as before the first tick, and every later tick counts like vanilla.
 * Integrated servers always count: their client may draw the debug screen at any time.
 */
class SpawnCounts(private val level: ServerLevel, private val chunkMap: ChunkMap) {
    /** Set once the stored state has been read while deferred; from then on every tick counts. */
    @Volatile
    private var observed = false

    /** The placeholder stored as `lastSpawnState` in place of a count, or null when the stored state is real. */
    @Volatile
    private var deferred: NaturalSpawner.SpawnState? = null
    private var deferredChunkCount = 0
    private var deferredChunks: NaturalSpawner.ChunkGetter? = null

    /** Replaces `tickChunks`' `createState`: vanilla's count without parallel spawning, else a parallel one or none. */
    fun prepare(chunkCount: Int, chunks: NaturalSpawner.ChunkGetter, localCaps: LocalMobCapCalculator): NaturalSpawner.SpawnState {
        val config = Vanadium.config
        val parallel = config.enabled && config.parallelSpawning
        if (!parallel || observed || !level.server.isDedicatedServer || level.gameRules.get(GameRules.SPAWN_MOBS)) {
            deferred = null
            deferredChunks = null
            if (!parallel) return NaturalSpawner.createState(chunkCount, level, chunks, localCaps)
            return SpawnPreparation.count(chunkCount, level, chunks, localCaps, chunkMap)
        }
        // Keep the placeholder compatible with other mods' wrappers around vanilla counting.
        val placeholder = SpawnPreparation.createState(chunkCount, level, emptyList(), chunks, localCaps)
        deferredChunkCount = chunkCount
        deferredChunks = chunks
        deferred = placeholder
        return placeholder
    }

    /**
     * What `getLastSpawnState` returns for the [stored] `lastSpawnState`: the stored state, unless it is the
     * placeholder; then the count it stands for, or null where none can be made (see the class comment).
     * A returned count replaces the placeholder in `lastSpawnState`.
     */
    fun observe(stored: NaturalSpawner.SpawnState?): NaturalSpawner.SpawnState? {
        val placeholder = deferred
        if (placeholder == null || stored !== placeholder) return stored
        observed = true
        val chunks = deferredChunks ?: return null
        if (!level.server.isSameThread || WorkerPool.isParallelWaveCaller()) return null
        val state = NaturalSpawner.createState(deferredChunkCount, level, chunks, LocalMobCapCalculator(chunkMap))
        deferred = null
        deferredChunks = null
        return state
    }
}
