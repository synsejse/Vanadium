package com.synsenetwork.vanadium.integration

import com.mojang.authlib.GameProfile
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.entity.AccessibleEntities
import com.synsenetwork.vanadium.tick.SpawnPreparation
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WorkerPool
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.LocalMobCapCalculator
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.PotentialCalculator
import net.minecraft.world.level.biome.Biomes
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Predicate
import kotlin.random.Random

/**
 * Whole-level parallel spawn counting against a serial `NaturalSpawner.createState` over the same live
 * entities: above the parallel threshold, through Lithium's section iteration and C2ME's simulated-chunk
 * filter, with entities in simulated and merely loaded chunks, mob spawn charges (soul sand valley) and
 * fake players near some of the mobs.
 */
internal object SpawnCountChecks {
    /**
     * Forced chunks are simulated, and so is the full ring two chunks around them (C2ME keeps simulation level 33).
     * A loading-only ticket ten chunks east makes full, tracked chunks that are not simulated, which C2ME's filter skips.
     */
    private const val FORCED_RADIUS = 1
    private const val LOADED_RADIUS = FORCED_RADIUS + 2
    private const val UNSIMULATED_OFFSET = 10
    private const val UNSIMULATED_RADIUS = 2
    private const val ENTITIES = 2400

    fun run(server: MinecraftServer) {
        val level = checkNotNull(server.getLevel(Level.NETHER)) { "no nether" }
        val found = checkNotNull(level.findClosestBiome3d({ it.`is`(Biomes.SOUL_SAND_VALLEY) }, BlockPos(0, 64, 0), 3200, 32, 64)) {
            "no soul sand valley"
        }
        val center = ChunkPos.containing(found.first)
        val forced = area(center, FORCED_RADIUS)
        val unsimulated = ChunkPos(center.x() + UNSIMULATED_OFFSET, center.z())
        val loaded = area(center, LOADED_RADIUS) + area(unsimulated, UNSIMULATED_RADIUS)
        val entities = ArrayList<Entity>()
        try {
            for (chunk in forced) level.setChunkForced(chunk.x(), chunk.z(), true)
            level.chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, unsimulated, UNSIMULATED_RADIUS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            fun ready() = loaded.all { level.chunkSource.getChunkNow(it.x(), it.z()) != null }
            level.chunkSource.mainThreadProcessor.managedBlock { System.nanoTime() >= deadline || ready() }
            check(ready(), "fixture chunks around $center did not reach full status")
            spawn(level, loaded, center, found.first.y, entities)
            // A full chunk's sections turn visible (tracked or ticking) only once its status change lands.
            fun visible() = entities.all { level.getEntity(it.id) === it }
            level.chunkSource.mainThreadProcessor.managedBlock { System.nanoTime() >= deadline || visible() }
            check(visible(), "${entities.count { level.getEntity(it.id) !== it }} fixture entities did not become visible")
            compare(level, center, entities)
        } finally {
            entities.forEach { if (!it.isRemoved) it.discard() }
            for (chunk in forced) level.setChunkForced(chunk.x(), chunk.z(), false)
            level.chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, unsimulated, UNSIMULATED_RADIUS)
        }
    }

    private fun area(center: ChunkPos, radius: Int) =
        (-radius..radius).flatMap { dx -> (-radius..radius).map { dz -> ChunkPos(center.x() + dx, center.z() + dz) } }

    private fun spawn(level: ServerLevel, chunks: List<ChunkPos>, center: ChunkPos, y: Int, out: MutableList<Entity>) {
        val random = Random(26)
        // Charged monsters and creatures, uncharged mobs, persistent mobs (skipped) and MISC entities (skipped).
        val types: List<EntityType<*>> = listOf(EntityTypes.SKELETON, EntityTypes.GHAST, EntityTypes.ENDERMAN, EntityTypes.STRIDER,
            EntityTypes.ZOMBIFIED_PIGLIN, EntityTypes.PIG, EntityTypes.ARMOR_STAND)
        repeat(ENTITIES) { i ->
            // Several entities share a section; one dense section in the center holds a job's worth.
            val chunk = if (i % 5 == 0) center else chunks[random.nextInt(chunks.size)]
            val entity = types[i % types.size].create(level, EntitySpawnReason.COMMAND)!!
            entity.setPos(chunk.minBlockX + random.nextDouble(16.0), y + random.nextDouble(-20.0, 20.0), chunk.minBlockZ + random.nextDouble(16.0))
            entity.setNoGravity(true)
            if (entity is Mob) {
                entity.setNoAi(true)
                if (i % 11 == 0) entity.setPersistenceRequired()
            }
            check(level.addFreshEntity(entity), "fixture ${entity.type} was not added")
            out.add(entity)
        }
    }

    private fun compare(level: ServerLevel, center: ChunkPos, fixture: List<Entity>) {
        val players = List(2) {
            ServerPlayer(level.server, level, GameProfile(UUID.randomUUID(), "SpawnCount$it"), ClientInformation.createDefault())
        }
        // Player 0 is near the west half, player 1 near the north half; the north-west quarter has both.
        fun caps() = LocalMobCapCalculator(level.chunkSource.chunkMap).also { caps ->
            for (chunk in area(center, UNSIMULATED_OFFSET + UNSIMULATED_RADIUS)) {
                caps.playersNearChunk.put(chunk.pack(), listOfNotNull(
                    players[0].takeIf { chunk.x() <= center.x() }, players[1].takeIf { chunk.z() <= center.z() }))
            }
        }
        val chunks = NaturalSpawner.ChunkGetter { key, output ->
            level.chunkSource.getVisibleChunkIfPresent(key)?.fullChunkFuture?.getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)?.ifSuccess(output)
        }
        val reference = NaturalSpawner.createState(289, level, chunks, caps())

        // The captured input is Lithium's sliceable section iteration, possibly inside C2ME's filter.
        val captured = SpawnPreparation.capture(289, level, chunks, caps())
        val sections = (unwrap(captured) as? AccessibleEntities<*>
            ?: error("spawn count input ${captured.javaClass.name} is not the sliceable section iteration")).snapshot()
        check(sections.size >= fixture.size && sections.size >= 1024, "only ${sections.size} entities in accessible sections")
        val filter = c2meFilter(captured)
        if (filter != null) {
            val simulated = fixture.count { filter.test(it) }
            check(simulated in 1 until fixture.size, "fixture entities do not span simulated ($simulated) and unsimulated chunks")
        }
        val countable = fixture.count { (filter == null || filter.test(it)) && it.type.category != MobCategory.MISC &&
            !(it is Mob && (it.isPersistenceRequired || it.requiresCustomPersistence())) }
        check(reference.mobCategoryCounts.values.sum() >= countable, "serial reference missed fixture entities: ${reference.mobCategoryCounts}")
        check(reference.spawnPotential.charges.isNotEmpty(), "no spawn charges at ${center}: fixture is not in a charged biome")
        check(players.all { it in reference.localMobCapCalculator.playerMobCounts }, "fixture mobs are not near both players")

        val previous = Vanadium.scheduler
        try {
            for (workers in intArrayOf(1, 3, 8)) {
                WorkerPool(workers).use { pool ->
                    Vanadium.scheduler = TickScheduler(pool, 1)
                    val parallel = SpawnPreparation.count(289, level, SpawnPreparation.capture(289, level, chunks, caps()), chunks, caps(), ::caps)
                    same(reference, parallel, "workers=$workers")
                }
            }
        } finally {
            Vanadium.scheduler = previous
        }
        // The production entry point, on the live scheduler.
        same(reference, SpawnPreparation.count(289, level, chunks, caps(), level.chunkSource.chunkMap).also {
            // Its job calculators ask the chunk map, which knows no fake players: compare only player-independent fields.
            it.localMobCapCalculator.playerMobCounts.clear()
            it.localMobCapCalculator.playersNearChunk.clear()
        }, "entry point", players = false)
        println("SPAWN_COUNT_CHECKS_PASSED entities=${sections.size} filtered=${filter != null} " +
            "charges=${reference.spawnPotential.charges.size} counts=${reference.mobCategoryCounts}")
    }

    private fun same(expected: NaturalSpawner.SpawnState, actual: NaturalSpawner.SpawnState, what: String, players: Boolean = true) {
        check(expected.spawnableChunkCount == actual.spawnableChunkCount, "$what: spawnable chunk count")
        check(expected.mobCategoryCounts == actual.mobCategoryCounts, "$what: counts ${actual.mobCategoryCounts} != ${expected.mobCategoryCounts}")
        val expectedCharges = expected.spawnPotential.charges
        val actualCharges = actual.spawnPotential.charges
        check(expectedCharges.size == actualCharges.size, "$what: ${actualCharges.size} charges != ${expectedCharges.size}")
        for (i in expectedCharges.indices) {
            check(POS.get(expectedCharges[i]) == POS.get(actualCharges[i]) && CHARGE.getDouble(expectedCharges[i]) == CHARGE.getDouble(actualCharges[i]),
                "$what: charge $i differs")
        }
        for (pos in listOf(BlockPos.ZERO, POS.get(expectedCharges.first()) as BlockPos, BlockPos(-1000, 40, 1000))) {
            check(expected.spawnPotential.getPotentialEnergyChange(pos, 0.7) == actual.spawnPotential.getPotentialEnergyChange(pos, 0.7),
                "$what: spawn potential at $pos")
        }
        if (!players) return
        val expectedCaps = expected.localMobCapCalculator
        val actualCaps = actual.localMobCapCalculator
        check(expectedCaps.playerMobCounts.keys == actualCaps.playerMobCounts.keys, "$what: players with local counts")
        for ((player, counts) in expectedCaps.playerMobCounts) {
            check(counts.counts == actualCaps.playerMobCounts.getValue(player).counts,
                "$what: local counts for ${player.gameProfile.name}: ${actualCaps.playerMobCounts.getValue(player).counts} != ${counts.counts}")
        }
        check(expectedCaps.playersNearChunk == actualCaps.playersNearChunk, "$what: players near chunks")
    }

    private val FILTERING = runCatching { Class.forName("com.ishland.c2me.base.common.util.FilteringIterable") }.getOrNull()
    private val POS = PotentialCalculator.PointCharge::class.java.getDeclaredField("pos").apply { isAccessible = true }
    private val CHARGE = PotentialCalculator.PointCharge::class.java.getDeclaredField("charge").apply { isAccessible = true }

    private fun field(source: Any, name: String): Any? =
        FILTERING!!.getDeclaredField(name).apply { isAccessible = true }.get(source)

    private fun unwrap(source: Iterable<Entity>): Any? = if (FILTERING?.isInstance(source) == true) field(source, "delegate") else source

    @Suppress("UNCHECKED_CAST")
    private fun c2meFilter(source: Iterable<Entity>): Predicate<Entity>? =
        if (FILTERING?.isInstance(source) == true) field(source, "predicate") as Predicate<Entity> else null
}
