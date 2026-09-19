package com.synsenetwork.vanadium.featurebench;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.chunk.ChunkPacketPreparation;
import com.synsenetwork.vanadium.tick.SpawnPreparation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

final class SpawnPacketBench {
    static void spawning(ServerLevel level, Measurements measurements) throws Exception {
        var map = level.getChunkSource().chunkMap;
        var chunk = level.getChunkAt(new BlockPos(0, 100, 0));
        List<Entity> all = new ArrayList<>();
        for (int i = 0; i < 8192; i++) {
            var mob = i % 2 == 0 ? new Pig(EntityTypes.PIG, level) : new Zombie(EntityTypes.ZOMBIE, level);
            mob.setPos(i % 16, 100, i / 16 % 16);
            if (i % 3 == 0) mob.setPersistenceRequired();
            all.add(mob);
        }
        NaturalSpawner.ChunkGetter chunks = (pos, consumer) -> consumer.accept(chunk);
        for (int count : new int[]{256, 2048, 8192}) {
            List<Entity> entities = all.subList(0, count);
            measurements.compare("spawn-count-" + count, on -> Vanadium.config.parallelSpawning = on,
                    () -> FeatureBench.sink = SpawnPreparation.count(289, entities, chunks,
                            new LocalMobCapCalculator(map), map));
        }
    }

    static void packets(ServerLevel level, Measurements measurements) throws Exception {
        List<LevelChunk> chunks = new ArrayList<>();
        try {
            for (int x = 0; x < 32; x++) {
                level.setChunkForced(x, 0, true);
                chunks.add(level.getChunk(x, 0));
            }
            for (boolean complex : new boolean[]{false, true}) {
                if (complex) {
                    Block[] palette = {Blocks.STONE, Blocks.DIRT, Blocks.GRANITE, Blocks.DIORITE,
                            Blocks.ANDESITE, Blocks.COBBLESTONE, Blocks.SANDSTONE, Blocks.GLASS,
                            Blocks.OAK_PLANKS, Blocks.BRICKS, Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK,
                            Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.LAPIS_BLOCK, Blocks.COAL_BLOCK};
                    // Eight dense palette sections per chunk. Fixture edits are outside all measurements.
                    for (LevelChunk chunk : chunks) {
                        for (int section = 4; section < 12; section++) {
                            for (int pos = 0; pos < 4096; pos++) {
                                chunk.getSections()[section].setBlockState(pos & 15, pos >> 8, pos >> 4 & 15,
                                        palette[(pos * 13 + (pos >> 4)) & 15].defaultBlockState());
                            }
                        }
                    }
                }
                for (int size : new int[]{8, 32}) {
                    List<LevelChunk> batch = chunks.subList(0, size);
                    measurements.compare("chunk-packets-" + size + "-" + (complex ? "palette" : "flat"),
                            on -> Vanadium.config.parallelChunkPackets = on, () -> {
                                if (Vanadium.config.parallelChunkPackets) {
                                    try (ChunkPacketPreparation ignored = new ChunkPacketPreparation()) {
                                        ChunkPacketPreparation.prepare(batch);
                                        for (LevelChunk chunk : batch) FeatureBench.sink = new ClientboundLevelChunkPacketData(chunk);
                                    }
                                } else {
                                    for (LevelChunk chunk : batch) FeatureBench.sink = new ClientboundLevelChunkPacketData(chunk);
                                }
                            });
                }
            }
        } finally {
            for (LevelChunk chunk : chunks) level.setChunkForced(chunk.getPos().x(), chunk.getPos().z(), false);
        }
    }
}
