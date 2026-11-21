package com.synsenetwork.vanadium.navigation;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;

/** Direct primitive-coordinate chunk and block reads on {@code PathNavigationRegion}, without allocating {@code EmptyLevelChunk} or {@code BlockPos}. */
public interface PathNavigationRegionAccess {
    @Nullable ChunkAccess vanadium$getChunkOrNull(int chunkX, int chunkZ);

    BlockState vanadium$getBlockState(int x, int y, int z);

    int vanadium$minY();

    int vanadium$maxY();

    int vanadium$minSectionY();
}
