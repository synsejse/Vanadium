package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.Stage;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkManagerMixin extends ChunkSource {

    @Shadow
    @Final
    public ServerChunkCache.MainThreadExecutor mainThreadProcessor;

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    @Mutable
    private Set<ChunkHolder> chunkHoldersToBroadcast =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At("HEAD"))
    private void preChunkTick(ProfilerFiller profiler, long timeDiff, CallbackInfo ci) {
        Vanadium.scheduler.begin(Stage.CHUNK);
    }

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V"))
    private void finishSpawning(ProfilerFiller profiler, long timeDiff, CallbackInfo ci) {
        Vanadium.scheduler.run(Stage.CHUNK);
    }

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V", shift = At.Shift.AFTER))
    private void finishRandomTicks(ProfilerFiller profiler, long timeDiff, CallbackInfo ci) {
        Vanadium.scheduler.run(Stage.CHUNK);
    }

    /** The spawning phase completes before 26.2's separate block-ticking phase begins. */
    @Redirect(method = "tickSpawningChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"))
    private void overwriteSpawn(ServerLevel serverWorld, LevelChunk chunk, NaturalSpawner.SpawnState info,
                                List<MobCategory> categories) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelSpawning) {
            // Thunder for this chunk must finish before its inline spawn attempt.
            if (Vanadium.config.enabled && Vanadium.config.parallelChunkTicks) {
                Vanadium.scheduler.run(Stage.CHUNK);
            }
            NaturalSpawner.spawnForChunk(serverWorld, chunk, info, categories);
            return;
        }
        Vanadium.scheduler.enqueue(Stage.CHUNK, chunk.getPos().x(), chunk.getPos().z(),
                () -> NaturalSpawner.spawnForChunk(serverWorld, chunk, info, categories), null);
    }

    @Redirect(method = "tickSpawningChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickThunder(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void tickThunder(ServerLevel serverWorld, LevelChunk chunk) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelChunkTicks) {
            serverWorld.tickThunder(chunk);
            return;
        }
        Vanadium.scheduler.enqueue(Stage.CHUNK, chunk.getPos().x(), chunk.getPos().z(),
                () -> serverWorld.tickThunder(chunk), null);
    }

    @Redirect(method = "lambda$tickChunks$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
    private void overwriteTickChunk(ServerLevel serverWorld, LevelChunk chunk, int randomTickSpeed) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelChunkTicks) {
            serverWorld.tickChunk(chunk, randomTickSpeed);
            return;
        }
        Vanadium.scheduler.enqueue(Stage.CHUNK, chunk.getPos().x(), chunk.getPos().z(),
                () -> serverWorld.tickChunk(chunk, randomTickSpeed), null);
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
