package com.synsenetwork.vanadium.integration

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import kotlin.random.Random

/**
 * Differential parity checks for entity hot-path mixins: `Entity.isInWall`, `Entity.checkInsideBlocks`,
 * and the packed path-type cache against uncached lookups across a block change.
 */
internal object EntityHotPathChecks {
    private const val CHUNK_X = 40
    private const val CHUNK_Z = -40
    private val ORIGIN = BlockPos(CHUNK_X * 16 + 8, 150, CHUNK_Z * 16 + 8)

    fun run(level: ServerLevel) {
        level.setChunkForced(CHUNK_X, CHUNK_Z, true)
        level.getChunk(CHUNK_X, CHUNK_Z)
        try {
            val suffocating = checkIsInWall(level)
            val insideHits = checkInsideBlocksParity(level)
            checkPathTypeCache(level)
            println("ENTITY_HOT_PATH_CHECKS_PASSED inWall=$suffocating insideBlocks=$insideHits")
        } finally {
            for (pos in BlockPos.betweenClosed(ORIGIN.offset(-4, -3, -4), ORIGIN.offset(4, 3, 4))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
            }
            level.setChunkForced(CHUNK_X, CHUNK_Z, false)
        }
    }

    private fun checkIsInWall(level: ServerLevel): Int {
        val palette = listOf(
            Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.GLASS.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState(), Blocks.MUD.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
            Blocks.OAK_STAIRS.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
        )
        val entities = listOf<EntityType<out Entity>>(EntityTypes.ZOMBIE, EntityTypes.IRON_GOLEM, EntityTypes.CHICKEN)
            .map { it.create(level, EntitySpawnReason.COMMAND)!! }
        val random = Random(26)
        var inWall = 0
        var cases = 0
        repeat(150) {
            val placed = HashMap<BlockPos, BlockState>()
            for (pos in BlockPos.betweenClosed(ORIGIN.offset(-2, -1, -2), ORIGIN.offset(2, 1, 2))) {
                val state = palette[random.nextInt(palette.size)]
                level.setBlock(pos, state, Block.UPDATE_CLIENTS)
                placed[pos.immutable()] = state
            }
            for (pos in placed.keys) check(level.getBlockState(pos) == placed[pos], "fixture block at $pos was not placed")
            for (entity in entities) repeat(12) { sample ->
                // Eyes inside the middle block and on its faces and edges, so boxes straddle block boundaries.
                fun coordinate(base: Int): Double = when (sample % 4) {
                    0 -> base + random.nextInt(0, 3) * 0.5
                    else -> base - 0.5 + random.nextDouble() * 2.0
                }
                val eyeY = if (sample % 3 == 0) ORIGIN.y + random.nextInt(0, 3) * 0.5 else ORIGIN.y - 0.25 + random.nextDouble() * 1.5
                entity.setPos(coordinate(ORIGIN.x), eyeY - entity.eyeHeight, coordinate(ORIGIN.z))
                entity.noPhysics = sample == 11
                val expected = vanillaIsInWall(entity)
                check(entity.isInWall() == expected) {
                    "isInWall ${entity.type} at ${entity.position()} (eye ${entity.eyeY}, noPhysics ${entity.noPhysics}) " +
                        "was ${!expected}, vanilla $expected"
                }
                cases++
                if (expected) inWall++
            }
        }
        check(inWall in 1 until cases, "isInWall fixtures need both outcomes: $inWall of $cases")
        return inWall
    }

    /** Vanilla 26.3 `Entity.isInWall`. */
    private fun vanillaIsInWall(entity: Entity): Boolean {
        if (entity.noPhysics) return false
        val level = entity.level()
        val checkWidth = entity.bbWidth * 0.8f
        val eyeBb = AABB.ofSize(entity.eyePosition, checkWidth.toDouble(), 1.0E-6, checkWidth.toDouble())
        return BlockPos.betweenClosedStream(eyeBb).anyMatch { pos ->
            val state = level.getBlockState(pos)
            !state.isAir && state.isSuffocating(level, pos) &&
                Shapes.joinIsNotEmpty(state.getCollisionShape(level, pos).move(pos), Shapes.create(eyeBb), BooleanOp.AND)
        }
    }

    private fun checkInsideBlocksParity(level: ServerLevel): Int {
        val checkMethod = Entity::class.java.getDeclaredMethod(
            "checkInsideBlocks",
            List::class.java,
            InsideBlockEffectApplier.StepBasedCollector::class.java,
        ).apply { isAccessible = true }
        val stuckSpeedField = Entity::class.java.getDeclaredField("stuckSpeedMultiplier").apply { isAccessible = true }

        val palette = listOf(
            Blocks.AIR.defaultBlockState(),
            Blocks.AIR.defaultBlockState(),
            Blocks.AIR.defaultBlockState(),
            Blocks.COBWEB.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            Blocks.POWDER_SNOW.defaultBlockState(),
            Blocks.SWEET_BERRY_BUSH.defaultBlockState(),
            Blocks.HONEY_BLOCK.defaultBlockState(),
            Blocks.SOUL_SAND.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
            Blocks.CAULDRON.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
        )
        val types = listOf<EntityType<out Entity>>(
            EntityTypes.ZOMBIE,
            EntityTypes.CHICKEN,
            EntityTypes.IRON_GOLEM,
            EntityTypes.SHULKER,
        )
        val random = Random(2631)
        var hitEffects = 0
        var cases = 0

        repeat(40) { round ->
            for (pos in BlockPos.betweenClosed(ORIGIN.offset(-4, -2, -4), ORIGIN.offset(4, 2, 4))) {
                val state = if (round == 0) Blocks.AIR.defaultBlockState() else palette[random.nextInt(palette.size)]
                level.setBlock(pos, state, Block.UPDATE_CLIENTS)
            }
            for (type in types) {
                repeat(8) { sample ->
                    val from = Vec3(
                        ORIGIN.x - 1.5 + random.nextDouble() * 3.0,
                        ORIGIN.y - 0.5 + random.nextDouble() * 1.5,
                        ORIGIN.z - 1.5 + random.nextDouble() * 3.0,
                    )
                    val delta = when (sample) {
                        // Stationary / sub-epsilon (Case A)
                        0 -> Vec3(0.0, 0.0, 0.0)
                        1 -> Vec3(1.0E-6, -1.0E-6, 1.0E-6)
                        // Small move within same block bounds (Case B)
                        2 -> Vec3(0.03, 0.0, -0.02)
                        // Axis-aligned negative boundary hit
                        3 -> Vec3(-0.35, 0.0, 0.0)
                        // Diagonal across block boundary (Case C)
                        4 -> Vec3(0.85, 0.35, -0.75)
                        // Fast >1 block move (movedFar = true)
                        5 -> Vec3(2.4, -0.8, 1.9)
                        // Fast multi-step move exceeding 16 steps when combined
                        6 -> Vec3(-3.2, 0.6, -2.7)
                        else -> Vec3(random.nextDouble(-1.8, 1.8), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.8, 1.8))
                    }
                    val to = from.add(delta)
                    val movement = if (sample % 2 == 0) {
                        Entity.Movement(from, to, delta)
                    } else {
                        Entity.Movement(from, to)
                    }

                    val actualEnt = type.create(level, EntitySpawnReason.COMMAND)!!
                    val expectedEnt = type.create(level, EntitySpawnReason.COMMAND)!!
                    actualEnt.setPos(to.x, to.y, to.z)
                    expectedEnt.setPos(to.x, to.y, to.z)

                    val actualCollector = InsideBlockEffectApplier.StepBasedCollector()
                    val expectedCollector = InsideBlockEffectApplier.StepBasedCollector()
                    checkMethod.invoke(actualEnt, listOf(movement), actualCollector)
                    vanillaCheckInsideBlocks(expectedEnt, listOf(movement), expectedCollector)

                    actualCollector.applyAndClear(actualEnt)
                    expectedCollector.applyAndClear(expectedEnt)

                    check(actualEnt.isInPowderSnow == expectedEnt.isInPowderSnow) {
                        "checkInsideBlocks isInPowderSnow mismatch for $type sample=$sample from=$from to=$to: " +
                            "actual=${actualEnt.isInPowderSnow} expected=${expectedEnt.isInPowderSnow}"
                    }
                    check(actualEnt.remainingFireTicks == expectedEnt.remainingFireTicks) {
                        "checkInsideBlocks fireTicks mismatch for $type sample=$sample from=$from to=$to: " +
                            "actual=${actualEnt.remainingFireTicks} expected=${expectedEnt.remainingFireTicks}"
                    }
                    val actualStuck = stuckSpeedField.get(actualEnt) as Vec3
                    val expectedStuck = stuckSpeedField.get(expectedEnt) as Vec3
                    check(actualStuck == expectedStuck) {
                        "checkInsideBlocks stuckSpeed mismatch for $type sample=$sample from=$from to=$to: " +
                            "actual=$actualStuck expected=$expectedStuck"
                    }
                    check(actualEnt.fallDistance == expectedEnt.fallDistance) {
                        "checkInsideBlocks fallDistance mismatch for $type sample=$sample from=$from to=$to: " +
                            "actual=${actualEnt.fallDistance} expected=${expectedEnt.fallDistance}"
                    }
                    if (actualEnt.isInPowderSnow || actualStuck != Vec3.ZERO) hitEffects++
                    cases++
                }
            }
        }
        check(hitEffects > 0, "checkInsideBlocks fixture never triggered an inside-block effect across $cases cases")
        return hitEffects
    }

    /** Unmodified vanilla 26.3 `Entity.checkInsideBlocks` oracle for differential parity checks. */
    private fun vanillaCheckInsideBlocks(
        entity: Entity,
        movements: List<Entity.Movement>,
        collector: InsideBlockEffectApplier.StepBasedCollector,
    ) {
        val visited = LongOpenHashSet()
        for (movement in movements) {
            var from = movement.from()
            val delta = movement.to().subtract(movement.from())
            var maxSteps = 16
            if (movement.axisDependentOriginalMovement().isPresent && delta.lengthSqr() > 0.0) {
                for (axis in Direction.axisStepOrder(movement.axisDependentOriginalMovement().get())) {
                    val axisDelta = delta.get(axis)
                    if (axisDelta != 0.0) {
                        val next = from.relative(axis.positive, axisDelta)
                        maxSteps -= vanillaCheckInsideSegment(entity, from, next, collector, visited, maxSteps)
                        from = next
                    }
                }
            } else {
                maxSteps -= vanillaCheckInsideSegment(entity, movement.from(), movement.to(), collector, visited, 16)
            }
            if (maxSteps <= 0) {
                vanillaCheckInsideSegment(entity, movement.to(), movement.to(), collector, visited, 1)
            }
        }
        visited.clear()
    }

    private fun vanillaCheckInsideSegment(
        entity: Entity,
        from: Vec3,
        to: Vec3,
        collector: InsideBlockEffectApplier.StepBasedCollector,
        visitedBlocks: LongOpenHashSet,
        maxSteps: Int,
    ): Int {
        val box = entity.makeBoundingBox(to).deflate(1.0E-5f.toDouble())
        val movedFar = from.distanceToSqr(to) > Mth.square(0.9999900000002526)
        val level = entity.level()
        var lastStep = 0
        BlockGetter.forEachBlockIntersectedBetween(from, to, box) { pos, step ->
            if (!entity.isAlive || step >= maxSteps) return@forEachBlockIntersectedBetween false
            lastStep = step
            val state = level.getBlockState(pos)
            if (state.isAir) return@forEachBlockIntersectedBetween true
            val shape = state.getEntityInsideCollisionShape(level, pos, entity)
            val hitBlock = shape === Shapes.block() ||
                entity.collidedWithShapeMovingFrom(from, to, shape.move(Vec3(pos)).toAabbs())
            val hitFluid = entity.collidedWithFluid(state.fluidState, pos, from, to)
            if ((!hitBlock && !hitFluid) || !visitedBlocks.add(pos.asLong())) {
                return@forEachBlockIntersectedBetween true
            }
            if (hitBlock) {
                val far = movedFar || box.intersects(pos)
                collector.advanceStep(step)
                state.entityInside(level, pos, entity, collector, far)
                entity.onInsideBlock(state)
            }
            if (hitFluid) {
                collector.advanceStep(step)
                state.fluidState.entityInside(level, pos, entity, collector)
            }
            true
        }
        return lastStep + 1
    }

    private fun checkPathTypeCache(level: ServerLevel) {
        val cache = level.pathTypeCache
        val positions = BlockPos.betweenClosed(ORIGIN.offset(-2, -2, -2), ORIGIN.offset(2, 2, 2)).map { it.immutable() }
        val states = listOf(Blocks.AIR, Blocks.STONE, Blocks.WATER, Blocks.LAVA, Blocks.OAK_FENCE, Blocks.CACTUS, Blocks.OAK_LEAVES)
        repeat(3) { round ->
            for ((i, pos) in positions.withIndex()) {
                level.setBlock(pos, states[(i + round) % states.size].defaultBlockState(), Block.UPDATE_CLIENTS)
            }
            for (pos in positions) {
                val expected = WalkNodeEvaluator.getPathTypeFromState(level, pos)
                check(cache.getOrCompute(level, pos) == expected, "path type at $pos: cache miss differs from $expected")
                check(cache.getOrCompute(level, pos) == expected, "path type at $pos: cache hit differs from $expected")
            }
        }
    }
}
