package com.synsenetwork.vanadium.integration

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import kotlin.random.Random

/**
 * Vanadium's loop `Entity.isInWall` against vanilla's stream version (copied below) in random block
 * neighbourhoods, and the packed path-type cache against uncached lookups across a block change.
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
            checkPathTypeCache(level)
            println("ENTITY_HOT_PATH_CHECKS_PASSED inWall=$suffocating")
        } finally {
            for (pos in BlockPos.betweenClosed(ORIGIN.offset(-2, -2, -2), ORIGIN.offset(2, 2, 2))) {
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
