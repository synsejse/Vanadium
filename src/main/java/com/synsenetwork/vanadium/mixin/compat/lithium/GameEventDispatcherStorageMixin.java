package com.synsenetwork.vanadium.mixin.compat.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.concurrent.locks.StampedLock;
import net.caffeinemc.mods.lithium.common.world.GameEventDispatcherStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Lithium keeps one map of chunk game-event dispatchers per world. Entities with moving vibration listeners
 * update it on workers while every game event reads it. Writes take a shared write lock; reads are
 * optimistic and retry under a read lock if a write overlapped them (a torn read may throw or miss).
 * Chunk load and unload callbacks also write it; see {@code ChunkStatusTrackerMixin}.
 */
@Mixin(GameEventDispatcherStorage.class)
public abstract class GameEventDispatcherStorageMixin {
    @Unique
    private static final StampedLock vanadium$lock = new StampedLock();

    @WrapMethod(method = "addChunk")
    private void vanadium$addChunk(long pos, Int2ObjectMap<?> dispatchers, Operation<Void> original) {
        long stamp = vanadium$lock.writeLock();
        try {
            original.call(pos, dispatchers);
        } finally {
            vanadium$lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "removeChunk")
    private void vanadium$removeChunk(long pos, Operation<Void> original) {
        long stamp = vanadium$lock.writeLock();
        try {
            original.call(pos);
        } finally {
            vanadium$lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "replace")
    private void vanadium$replace(long pos, Int2ObjectMap<?> dispatchers, Operation<Void> original) {
        long stamp = vanadium$lock.writeLock();
        try {
            original.call(pos, dispatchers);
        } finally {
            vanadium$lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "get")
    private Int2ObjectMap<?> vanadium$get(long pos, Operation<Int2ObjectMap<?>> original) {
        long stamp = vanadium$lock.tryOptimisticRead();
        if (stamp != 0L) {
            try {
                Int2ObjectMap<?> result = original.call(pos);
                if (vanadium$lock.validate(stamp)) return result;
            } catch (RuntimeException ignored) {
                // A read that overlapped a resize; retried below under the read lock.
            }
        }
        stamp = vanadium$lock.readLock();
        try {
            return original.call(pos);
        } finally {
            vanadium$lock.unlockRead(stamp);
        }
    }
}
