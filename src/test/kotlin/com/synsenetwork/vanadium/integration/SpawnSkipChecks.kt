package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.spawning.SpawnCounts
import com.synsenetwork.vanadium.spawning.SpawnPreparation
import com.synsenetwork.vanadium.stage.ChunkTickAccess
import kotlin.random.Random
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LocalMobCapCalculator
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.gamerules.GameRules

/**
 * The spawn count of real ticks against vanilla's own `NaturalSpawner.createState` over the same entities: with
 * `spawn_mobs` true (counted every tick, through the parallel column split), then false (skipped until read; read
 * off the server thread it is null, read on it the count is made then), and every tick once it has been read.
 * The fixture is enough mobs in enough columns for the counting wave, simulated by C2ME.
 */
internal object SpawnSkipChecks {
    private const val CHUNK_X = -96
    private const val CHUNK_Z = 96
    private const val RADIUS = 2
    private const val ENTITIES = 1800

    fun schedule(level: ServerLevel, checks: TickedChecks) {
        val source = level.chunkSource
        val chunkArea = (-RADIUS..RADIUS).flatMap { dx -> (-RADIUS..RADIUS).map { dz -> ChunkPos(CHUNK_X + dx, CHUNK_Z + dz) } }
        val entities = ArrayList<Entity>()
        val previousRule = level.gameRules.get(GameRules.SPAWN_MOBS)
        val chunks = SpawnCountChecks.fullChunksNow(source)
        fun reference() = NaturalSpawner.createState(source.chunkMap.distanceManager.naturalSpawnChunkCount, level, chunks,
            LocalMobCapCalculator(source.chunkMap))
        fun stored() = LAST_SPAWN_STATE.get(source) as NaturalSpawner.SpawnState?
        fun deferred() = DEFERRED.get((source as ChunkTickAccess).`vanadium$chunkStage`().spawnCounts) != null

        checks.then("spawn skip fixture") {
            for (chunk in chunkArea) level.setChunkForced(chunk.x(), chunk.z(), true)
            val random = Random(96)
            val types: List<EntityType<*>> = listOf(EntityTypes.PIG, EntityTypes.SILVERFISH, EntityTypes.BAT, EntityTypes.ARMOR_STAND)
            for (i in 0 until ENTITIES) {
                val chunk = chunkArea[i % chunkArea.size]
                val chunkAccess = level.getChunk(chunk.x(), chunk.z())
                check(chunkAccess.pos == chunk, "fixture chunk $chunk")
                val entity = types[i % types.size].create(level, EntitySpawnReason.COMMAND)!!
                entity.setPos(chunk.minBlockX + random.nextDouble(16.0), 120 + random.nextDouble(60.0), chunk.minBlockZ + random.nextDouble(16.0))
                entity.setNoGravity(true)
                if (entity is Mob) {
                    entity.setNoAi(true)
                    if (i % 13 == 0) entity.setPersistenceRequired()
                }
                check(level.addFreshEntity(entity), "fixture ${entity.type} was not added")
                entities.add(entity)
            }
            level.gameRules.set(GameRules.SPAWN_MOBS, true, level.server)
        }
        // Visible, and counted: C2ME only counts entities in chunks it simulates, which takes ticket updates.
        checks.awaitAsync("spawn skip fixture simulated", 200) {
            val filter = SpawnCountChecks.c2meFilter(SpawnPreparation.capture(0, level, chunks, LocalMobCapCalculator(source.chunkMap)))
            entities.all { level.getEntity(it.id) === it && (filter == null || filter.test(it)) }
        }
        checks.then("spawn counts with spawn_mobs true") {
            check(!deferred(), "count skipped while spawn_mobs is true")
            val expected = reference()
            check(expected.mobCategoryCounts.values.sum() >= ENTITIES / 2, "fixture mobs not counted: ${expected.mobCategoryCounts}")
            val state = checkNotNull(source.lastSpawnState) { "no spawn state" }
            check(state === stored(), "getLastSpawnState replaced a counted state")
            SpawnCountChecks.same(expected, state, "spawn_mobs true")
            for (enemies in listOf(true, false)) for (persistent in listOf(true, false)) {
                check(NaturalSpawner.getFilteredSpawningCategories(expected, enemies, persistent) ==
                    NaturalSpawner.getFilteredSpawningCategories(state, enemies, persistent), "spawning categories differ")
            }
            level.gameRules.set(GameRules.SPAWN_MOBS, false, level.server)
        }
        checks.then("spawn count skipped with spawn_mobs false") {
            check(deferred(), "count made with spawn_mobs false and nothing reading it")
            val placeholder = checkNotNull(stored()) { "no stored spawn state" }
            check(placeholder.mobCategoryCounts.isEmpty() && placeholder.spawnPotential.charges.isEmpty(), "placeholder holds counts")
            val expected = reference()
            check(placeholder.spawnableChunkCount == expected.spawnableChunkCount, "placeholder spawnable chunk count")
            // Another thread cannot count: it sees no state yet.
            var offThread: Any? = Unit
            val reader = Thread.ofPlatform().start { offThread = source.lastSpawnState }
            check(reader.join(java.time.Duration.ofSeconds(10)), "off-thread reader stuck")
            check(offThread == null, "off-thread read returned $offThread")
            // The server thread, between ticks, gets the count made now.
            val state = checkNotNull(source.lastSpawnState) { "server-thread read returned null" }
            check(state !== placeholder && stored() === state, "count not stored as lastSpawnState")
            check(!deferred(), "still deferred after a count")
            SpawnCountChecks.same(expected, state, "read while skipped")
            check(source.lastSpawnState === state, "second read recounted")
        }
        checks.then("spawn counts every tick once read") {
            check(!deferred(), "count skipped after it was read")
            SpawnCountChecks.same(reference(), checkNotNull(stored()), "after a read")
        }
        // The fixture's centre chunk is simulated and spawnable: tickSpawningChunk's dispatch order there.
        checks.awaitAsync("spawn dispatch chunk ticking", 100) { StageDispatchChecks.ready(level, ChunkPos(CHUNK_X, CHUNK_Z)) }
        checks.then("spawn dispatch order") { StageDispatchChecks.spawning(level, ChunkPos(CHUNK_X, CHUNK_Z)) }
        // Its neighbours are forced too, so its block entities may tick in the wave.
        checks.then("block-entity dispatch") { StageDispatchChecks.blockEntities(level, ChunkPos(CHUNK_X, CHUNK_Z)) }
        checks.then("spawn skip cleanup") {
            level.gameRules.set(GameRules.SPAWN_MOBS, previousRule, level.server)
            entities.forEach { if (!it.isRemoved) it.discard() }
            for (chunk in chunkArea) level.setChunkForced(chunk.x(), chunk.z(), false)
            println("SPAWN_SKIP_CHECKS_PASSED entities=${entities.size}")
        }
    }

    private val LAST_SPAWN_STATE = ServerChunkCache::class.java.getDeclaredField("lastSpawnState").apply { isAccessible = true }

    /** The placeholder [SpawnCounts] stores in place of a count; null while the stored state is real. */
    private val DEFERRED = SpawnCounts::class.java.getDeclaredField("deferred").apply { isAccessible = true }
}
