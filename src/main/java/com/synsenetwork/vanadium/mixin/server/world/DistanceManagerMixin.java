package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.ConcurrentLongLinkedOpenHashSet;
import com.synsenetwork.vanadium.concurrent.ConcurrentLong2ByteMap;
import com.synsenetwork.vanadium.mixin.world.SimulationChunkTrackerAccessor;
import it.unimi.dsi.fastutil.longs.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {

    @Shadow
    @Final
    @Mutable
    Set<ChunkHolder> chunksToUpdateFutures = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Shadow
    @Final
    @Mutable
    LongSet ticketsToRelease = new ConcurrentLongLinkedOpenHashSet();

    @Shadow
    @Final
    private SimulationChunkTracker simulationChunkTracker;

    /** Iterates the concurrent level map directly; vanilla's fastIterable converts every entry. */
    @WrapMethod(method = "forEachEntityTickingChunk")
    private void forEachEntityTickingChunkDirect(LongConsumer consumer, Operation<Void> original) {
        if (((SimulationChunkTrackerAccessor) simulationChunkTracker).vanadium$chunks() instanceof ConcurrentLong2ByteMap chunks) {
            chunks.forEachEntry((key, level) -> {
                if (ChunkLevel.isEntityTicking(level)) consumer.accept(key);
            });
        } else {
            original.call(consumer);
        }
    }
}
