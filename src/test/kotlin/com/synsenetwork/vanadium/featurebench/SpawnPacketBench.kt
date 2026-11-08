package com.synsenetwork.vanadium.featurebench

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.chunk.ChunkPacketPreparation
import com.synsenetwork.vanadium.tick.SpawnPreparation
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.level.LocalMobCapCalculator
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.block.Blocks

internal object SpawnPacketBench {
    fun spawning(level: ServerLevel, measurements: Measurements) {
        val map = level.chunkSource.chunkMap
        val chunk = level.getChunkAt(BlockPos(0, 100, 0))
        val all = List<Entity>(8192) { i ->
            val mob = if (i % 2 == 0) Pig(EntityTypes.PIG, level) else Zombie(EntityTypes.ZOMBIE, level)
            mob.setPos((i % 16).toDouble(), 100.0, (i / 16 % 16).toDouble())
            if (i % 3 == 0) mob.setPersistenceRequired()
            mob
        }
        val chunks = NaturalSpawner.ChunkGetter { _, consumer -> consumer.accept(chunk) }
        for (count in intArrayOf(256, 2048, 8192)) {
            val entities = all.subList(0, count)
            measurements.compare("spawn-count-$count", { Vanadium.config.parallelSpawning = it }) {
                FeatureBench.sink = SpawnPreparation.count(289, entities, chunks, LocalMobCapCalculator(map), map)
            }
        }
    }

    fun packets(level: ServerLevel, measurements: Measurements) {
        val chunks = (0 until 32).map { x ->
            level.setChunkForced(x, 0, true)
            level.getChunk(x, 0)
        }
        try {
            for (complex in listOf(false, true)) {
                if (complex) {
                    val palette = listOf(Blocks.STONE, Blocks.DIRT, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
                        Blocks.COBBLESTONE, Blocks.SANDSTONE, Blocks.GLASS, Blocks.OAK_PLANKS, Blocks.BRICKS, Blocks.GOLD_BLOCK,
                        Blocks.IRON_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.LAPIS_BLOCK, Blocks.COAL_BLOCK)
                    // Eight dense palette sections per chunk. Fixture edits are outside all measurements.
                    for (chunk in chunks) for (section in 4 until 12) for (pos in 0 until 4096) {
                        chunk.sections[section].setBlockState(pos and 15, pos shr 8, pos shr 4 and 15,
                            palette[(pos * 13 + (pos shr 4)) and 15].defaultBlockState())
                    }
                }
                for (size in intArrayOf(8, 32)) {
                    val batch = chunks.subList(0, size)
                    measurements.compare("chunk-packets-$size-${if (complex) "palette" else "flat"}",
                        { Vanadium.config.parallelChunkPackets = it }) {
                        if (Vanadium.config.parallelChunkPackets) {
                            ChunkPacketPreparation().use {
                                ChunkPacketPreparation.prepare(batch)
                                for (chunk in batch) FeatureBench.sink = ClientboundLevelChunkPacketData(chunk)
                            }
                        } else {
                            for (chunk in batch) FeatureBench.sink = ClientboundLevelChunkPacketData(chunk)
                        }
                    }
                }
            }
        } finally {
            for (chunk in chunks) level.setChunkForced(chunk.pos.x(), chunk.pos.z(), false)
        }
    }
}
