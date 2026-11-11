package com.synsenetwork.vanadium.mixin.compat.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.tick.WorkerPool;
import net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Lithium's chunk load and unload callbacks update hopper block trackers and game-event dispatchers that
 * workers use. They run on the server thread, which can change chunk status in the middle of a parallel wave
 * (a task it runs loading a chunk); those callbacks then wait for the wave to finish, in order. A chunk only
 * becomes inaccessible there; its block entities are removed later, when it unloads outside the wave.
 */
@Mixin(ChunkStatusTracker.class)
public abstract class ChunkStatusTrackerMixin {
    @WrapMethod(method = "onChunkAccessible")
    private static void vanadium$afterWave(ServerLevel level, LevelChunk chunk, Operation<Void> original) {
        WorkerPool.runAfterParallelWave(() -> original.call(level, chunk));
    }

    @WrapMethod(method = "onChunkInaccessible")
    private static void vanadium$afterWave(ServerLevel level, ChunkPos pos, Operation<Void> original) {
        WorkerPool.runAfterParallelWave(() -> original.call(level, pos));
    }
}
