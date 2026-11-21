package com.synsenetwork.vanadium.mixin.world.level;

import com.synsenetwork.vanadium.navigation.PathNavigationRegionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Direct primitive-coordinate chunk and block reads on {@link PathNavigationRegion} without {@code EmptyLevelChunk} or {@code BlockPos} allocation. */
@Mixin(PathNavigationRegion.class)
public abstract class PathNavigationRegionMixin implements PathNavigationRegionAccess {
    @Shadow @Final protected int centerX;
    @Shadow @Final protected int centerZ;
    @Shadow @Final protected ChunkAccess[][] chunks;
    @Shadow @Final protected Level level;

    @Unique private int vanadium$minY;
    @Unique private int vanadium$maxY;
    @Unique private int vanadium$minSectionY;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initBounds(Level level, BlockPos start, BlockPos end, CallbackInfo ci) {
        this.vanadium$minY = level.getMinY();
        this.vanadium$maxY = level.getMaxY();
        this.vanadium$minSectionY = level.getMinSectionY();
    }

    @Override
    public @Nullable ChunkAccess vanadium$getChunkOrNull(int chunkX, int chunkZ) {
        int xc = chunkX - this.centerX;
        if (xc < 0 || xc >= this.chunks.length) {
            return null;
        }
        ChunkAccess[] row = this.chunks[xc];
        int zc = chunkZ - this.centerZ;
        if (zc < 0 || zc >= row.length) {
            return null;
        }
        return row[zc];
    }

    @Override
    public BlockState vanadium$getBlockState(int x, int y, int z) {
        if (y < this.vanadium$minY || y > this.vanadium$maxY) {
            return Blocks.AIR.defaultBlockState();
        }
        int xc = (x >> 4) - this.centerX;
        if (xc < 0 || xc >= this.chunks.length) {
            return Blocks.AIR.defaultBlockState();
        }
        ChunkAccess[] row = this.chunks[xc];
        int zc = (z >> 4) - this.centerZ;
        if (zc < 0 || zc >= row.length) {
            return Blocks.AIR.defaultBlockState();
        }
        ChunkAccess chunk = row[zc];
        if (chunk == null) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunkSection[] sections = chunk.getSections();
        int sy = (y >> 4) - this.vanadium$minSectionY;
        if (sy < 0 || sy >= sections.length) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunkSection section = sections[sy];
        if (section == null || section.hasOnlyAir()) {
            return Blocks.AIR.defaultBlockState();
        }
        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    @Override
    public int vanadium$minY() {
        return this.vanadium$minY;
    }

    @Override
    public int vanadium$maxY() {
        return this.vanadium$maxY;
    }

    @Override
    public int vanadium$minSectionY() {
        return this.vanadium$minSectionY;
    }
}
