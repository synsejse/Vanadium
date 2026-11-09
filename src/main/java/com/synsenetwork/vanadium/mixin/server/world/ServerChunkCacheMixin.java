package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tick.ChunkTickBatch;
import com.synsenetwork.vanadium.tick.ChunkTickSink;
import com.synsenetwork.vanadium.tick.SpawnPreparation;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow @Final public ChunkMap chunkMap;

    @Redirect(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"))
    private NaturalSpawner.SpawnState prepareSpawning(int count, ServerLevel level,
            NaturalSpawner.ChunkGetter chunks, LocalMobCapCalculator caps) {
        return SpawnPreparation.count(count, level, chunks, caps, chunkMap);
    }

    @Shadow
    @Final
    @Mutable
    private Set<ChunkHolder> chunkHoldersToBroadcast =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
    private void beginChunkTicks(ProfilerFiller profiler, CallbackInfo ci) {
        Vanadium.scheduler.begin(Stage.SPAWNING);
    }

    /**
     * Ends spawning before vanilla's block-ticking pass. With parallel chunk ticks, ChunkMapMixin then
     * records each ticking holder instead of invoking vanilla's per-chunk consumer; otherwise the
     * vanilla consumer ticks chunks inline.
     */
    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V"))
    private void finishSpawning(ProfilerFiller profiler, CallbackInfo ci) {
        Vanadium.scheduler.run(Stage.SPAWNING);
        Vanadium.scheduler.begin(Stage.CHUNK);
        if (Vanadium.config.enabled && Vanadium.config.parallelChunkTicks) {
            ChunkTickBatch batch = vanadium$chunkTicks();
            batch.begin(level.getGameRules().get(GameRules.RANDOM_TICK_SPEED));
            ((ChunkTickSink) chunkMap).vanadium$setChunkTickSink(batch);
        }
    }

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V", shift = At.Shift.AFTER))
    private void finishChunkTicks(ProfilerFiller profiler, CallbackInfo ci) {
        ((ChunkTickSink) chunkMap).vanadium$setChunkTickSink(null);
        vanadium$chunkTicks().dispatch(Vanadium.scheduler);
        Vanadium.scheduler.run(Stage.CHUNK);
    }

    @Shadow @Final ServerLevel level;

    @Unique
    private ChunkTickBatch vanadium$chunkTickBatch;

    @Unique
    private ChunkTickBatch vanadium$chunkTicks() {
        if (vanadium$chunkTickBatch == null) vanadium$chunkTickBatch = new ChunkTickBatch(level);
        return vanadium$chunkTickBatch;
    }

    /** The spawning phase completes before vanilla's separate block-ticking phase begins. */
    @Redirect(method = "tickSpawningChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"))
    private void dispatchSpawning(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState info,
                                List<MobCategory> categories) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelSpawning) {
            // Thunder for this chunk must finish before its inline spawn attempt.
            if (Vanadium.config.enabled && Vanadium.config.parallelChunkTicks) {
                Vanadium.scheduler.run(Stage.SPAWNING);
            }
            NaturalSpawner.spawnForChunk(level, chunk, info, categories);
            return;
        }
        Vanadium.scheduler.enqueue(Stage.SPAWNING, chunk.getPos().x(), chunk.getPos().z(),
                () -> NaturalSpawner.spawnForChunk(level, chunk, info, categories), null);
    }

    @Redirect(method = "tickSpawningChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickThunder(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void dispatchThunder(ServerLevel level, LevelChunk chunk) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelChunkTicks) {
            level.tickThunder(chunk);
            return;
        }
        Vanadium.scheduler.enqueue(Stage.SPAWNING, chunk.getPos().x(), chunk.getPos().z(),
                () -> level.tickThunder(chunk), null);
    }

    @Redirect(method = {"getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", "getChunkNow"}, at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerChunkCache;mainThread:Ljava/lang/Thread;", opcode = Opcodes.GETFIELD))
    private Thread overwriteServerThread(ServerChunkCache mgr) {
        return Thread.currentThread();
    }

    @Redirect(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;incrementCounter(Ljava/lang/String;)V"))
    private void overwriteProfilerVisit(ProfilerFiller instance, String s) {
        if (Vanadium.config.parallelChunkLoads) return;
        instance.incrementCounter("getChunkCacheMiss");
    }

    @WrapMethod(method = "storeInCache")
    private synchronized void syncPutInCache(long pos, ChunkAccess chunk, ChunkStatus status, Operation<Void> original) {
        original.call(pos, chunk, status);
    }

}
