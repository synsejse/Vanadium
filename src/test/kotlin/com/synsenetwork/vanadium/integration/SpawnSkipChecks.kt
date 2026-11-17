package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.tick.SpawnCounts
import com.synsenetwork.vanadium.tick.SpawnPreparation
import net.minecraft.server.level.ChunkHolder
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
import kotlin.random.Random

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
        val chunks = NaturalSpawner.ChunkGetter { key, output ->
            source.getVisibleChunkIfPresent(key)?.fullChunkFuture?.getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)?.ifSuccess(output)
        }
        fun reference() = NaturalSpawner.createState(source.chunkMap.distanceManager.naturalSpawnChunkCount, level, chunks,
            LocalMobCapCalculator(source.chunkMap))
        fun stored() = LAST_SPAWN_STATE.get(source) as NaturalSpawner.SpawnState?
        fun counts() = spawnCounts(source)

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
        checks.await("spawn skip fixture simulated", 200) {
            val filter = SpawnCountChecks.c2meFilter(SpawnPreparation.capture(0, level, chunks, LocalMobCapCalculator(source.chunkMap)))
            entities.all { level.getEntity(it.id) === it && (filter == null || filter.test(it)) }
        }
        checks.then("spawn counts with spawn_mobs true") {
            check(!counts().isDeferred(), "count skipped while spawn_mobs is true")
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
            check(counts().isDeferred(), "count made with spawn_mobs false and nothing reading it")
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
            check(!counts().isDeferred(), "still deferred after a count")
            SpawnCountChecks.same(expected, state, "read while skipped")
            check(source.lastSpawnState === state, "second read recounted")
        }
        checks.then("spawn counts every tick once read") {
            check(!counts().isDeferred(), "count skipped after it was read")
            SpawnCountChecks.same(reference(), checkNotNull(stored()), "after a read")
        }
        checks.then("spawn skip cleanup") {
            level.gameRules.set(GameRules.SPAWN_MOBS, previousRule, level.server)
            entities.forEach { if (!it.isRemoved) it.discard() }
            for (chunk in chunkArea) level.setChunkForced(chunk.x(), chunk.z(), false)
            println("SPAWN_SKIP_CHECKS_PASSED entities=${entities.size}")
        }
    }

    private val LAST_SPAWN_STATE = ServerChunkCache::class.java.getDeclaredField("lastSpawnState").apply { isAccessible = true }

    private val SPAWN_COUNTS = ServerChunkCache::class.java.declaredFields.single { it.type == SpawnCounts::class.java }
        .apply { isAccessible = true }

    private fun spawnCounts(source: ServerChunkCache): SpawnCounts = checkNotNull(SPAWN_COUNTS.get(source) as SpawnCounts?) { "no spawn counts" }
}
