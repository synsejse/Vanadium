package com.synsenetwork.vanadium.mixin.entity.ai.pathing;

import com.synsenetwork.vanadium.navigation.PathTypeSlots;
import java.util.concurrent.atomic.AtomicLongArray;
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
 * it serialized all pathfinding. Each slot here is one atomic long holding a whole entry
 * ({@link PathTypeSlots}), so a reader sees either a complete entry or a miss, no lock is taken and
 * no entry is allocated. Lost races only cost a recomputation.
 */
@Mixin(PathTypeCache.class)
public abstract class PathTypeCacheMixin {
    @Unique private final AtomicLongArray vanadium$slots = PathTypeSlots.create();

    /**
     * @author Vanadium
     * @reason Lock-free cache shared by parallel pathfinding.
     */
    @Overwrite
    public PathType getOrCompute(BlockGetter level, BlockPos pos) {
        long key = pos.asLong();
        PathType cached = PathTypeSlots.get(vanadium$slots, key);
        if (cached != null) {
            return cached;
        }
        PathType type = WalkNodeEvaluator.getPathTypeFromState(level, pos);
        PathTypeSlots.put(vanadium$slots, key, type);
        return type;
    }

    /**
     * @author Vanadium
     * @reason Lock-free cache shared by parallel pathfinding.
     */
    @Overwrite
    public void invalidate(BlockPos pos) {
        PathTypeSlots.invalidate(vanadium$slots, pos.asLong());
    }
}
