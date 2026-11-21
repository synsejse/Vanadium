package com.synsenetwork.vanadium.mixin.server.level;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.synsenetwork.vanadium.spawning.SpawnCounts;
import com.synsenetwork.vanadium.stage.ChunkStage;
import com.synsenetwork.vanadium.stage.ChunkTickAccess;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
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

/** Hooks of the level's SPAWNING and CHUNK stages ({@link ChunkStage}) and the chunk cache's thread rules. */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements ChunkTickAccess {
    @Shadow @Final ServerLevel level;
    @Shadow private NaturalSpawner.SpawnState lastSpawnState;

    @Shadow
    @Final
    @Mutable
    private Set<ChunkHolder> chunkHoldersToBroadcast;

    @Unique
    private ChunkStage vanadium$chunkStage;

    /** Installed explicitly: Mixin may drop shadow-field initializers once the class adds fields of its own. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$install(CallbackInfo ci) {
        chunkHoldersToBroadcast = Collections.newSetFromMap(new ConcurrentHashMap<>());
        vanadium$chunkStage = new ChunkStage((ServerChunkCache) (Object) this, level);
    }

    @Override
    public ChunkStage vanadium$chunkStage() {
        return vanadium$chunkStage;
    }

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
    private void beginChunkTicks(ProfilerFiller profiler, CallbackInfo ci) {
        vanadium$chunkStage.beginTick();
    }

    @Redirect(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"))
    private NaturalSpawner.SpawnState prepareSpawning(int count, ServerLevel level,
            NaturalSpawner.ChunkGetter chunks, LocalMobCapCalculator caps) {
        return vanadium$chunkStage.getSpawnCounts().prepare(count, chunks, caps);
    }

    /** A count skipped while nothing used it is made when first read; see {@link SpawnCounts}. */
    @ModifyReturnValue(method = "getLastSpawnState", at = @At("RETURN"))
    private NaturalSpawner.SpawnState countSkippedSpawnState(NaturalSpawner.SpawnState stored) {
        NaturalSpawner.SpawnState state = vanadium$chunkStage.getSpawnCounts().observe(stored);
        if (state != stored && state != null) lastSpawnState = state;
        return state;
    }

    @Redirect(method = "tickSpawningChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickThunder(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void dispatchThunder(ServerLevel level, LevelChunk chunk) {
        vanadium$chunkStage.thunder(chunk);
    }

    @Redirect(method = "tickSpawningChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"))
    private void dispatchSpawning(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState info,
                                  List<MobCategory> categories) {
        vanadium$chunkStage.spawn(chunk, info, categories);
    }

    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V"))
    private void tickChunksInWave(ChunkMap map, Consumer<LevelChunk> consumer, Operation<Void> original) {
        vanadium$chunkStage.tickChunks(map, consumer, original);
    }

    @Redirect(method = {"getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", "getChunkNow"}, at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerChunkCache;mainThread:Ljava/lang/Thread;", opcode = Opcodes.GETFIELD))
    private Thread overwriteServerThread(ServerChunkCache mgr) {
        return Thread.currentThread();
    }

    /**
     * Vanilla's four-entry cache stores positions, statuses and chunks in three parallel non-volatile arrays
     * that {@code getChunk} scans without synchronization; {@link com.synsenetwork.vanadium.chunk.ParallelChunkCache}
     * already serves loaded FULL chunks and spawning-wave structure chunks lock-free. Skipping writes after
     * {@code <init>}'s initial {@code clearCache()} leaves the arrays immutable ({@code INVALID_CHUNK_POS}/null),
     * removing both the {@code storeInCache} monitor and torn parallel-array reads on misses.
     */
    @Inject(method = "storeInCache", at = @At("HEAD"), cancellable = true)
    private void vanadium$skipFourEntryCacheStore(long pos, ChunkAccess chunk, ChunkStatus status, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "clearCache", at = @At("HEAD"), cancellable = true)
    private void vanadium$skipFourEntryCacheClear(CallbackInfo ci) {
        if (vanadium$chunkStage != null) ci.cancel();
    }

}
