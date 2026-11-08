package com.synsenetwork.vanadium.mixin.entity.ai.pathing;

import com.synsenetwork.vanadium.tracking.PathTypeEntry;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * The level-wide path type cache is read by every pathfinding mob on every worker. Vanilla keeps
 * positions and types in two parallel arrays, which tear under concurrent writes; a monitor around
 * it serialized all pathfinding. Each slot here holds one immutable entry, so a reader sees either
 * a complete entry or a miss, and no lock is taken. Lost races only cost a recomputation.
 */
@Mixin(PathTypeCache.class)
public abstract class PathTypeCacheMixin {
    @Unique private static final int SIZE = 4096;
    @Unique private final PathTypeEntry[] vanadium$entries = new PathTypeEntry[SIZE];

    /**
     * @author Vanadium
     * @reason Lock-free cache shared by parallel pathfinding.
     */
    @Overwrite
    public PathType getOrCompute(BlockGetter level, BlockPos pos) {
        long key = pos.asLong();
        int index = vanadium$index(key);
        PathTypeEntry entry = vanadium$entries[index];
        if (entry != null && entry.key() == key) {
            return entry.type();
        }
        PathType type = WalkNodeEvaluator.getPathTypeFromState(level, pos);
        vanadium$entries[index] = new PathTypeEntry(key, type);
        return type;
    }

    /**
     * @author Vanadium
     * @reason Lock-free cache shared by parallel pathfinding.
     */
    @Overwrite
    public void invalidate(BlockPos pos) {
        long key = pos.asLong();
        int index = vanadium$index(key);
        PathTypeEntry entry = vanadium$entries[index];
        if (entry != null && entry.key() == key) {
            vanadium$entries[index] = null;
        }
    }

    @Unique
    private static int vanadium$index(long key) {
        return (int) HashCommon.mix(key) & (SIZE - 1);
    }
}
