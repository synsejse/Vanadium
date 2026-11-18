package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.spawning.SpawnCounts;
import com.synsenetwork.vanadium.spawning.SpawnTasks;
import com.synsenetwork.vanadium.tick.ChunkTickAccess;
import com.synsenetwork.vanadium.tick.ChunkTickBatch;
import com.synsenetwork.vanadium.tick.Stage;
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
import net.minecraft.world.level.gamerules.GameRules;
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
public abstract class ServerChunkCacheMixin implements ChunkTickAccess {
    @Shadow @Final public ChunkMap chunkMap;

    @Redirect(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"))
    private NaturalSpawner.SpawnState prepareSpawning(int count, ServerLevel level,
            NaturalSpawner.ChunkGetter chunks, LocalMobCapCalculator caps) {
        return vanadium$spawnCounts().prepare(count, chunks, caps);
    }

    @Shadow private NaturalSpawner.SpawnState lastSpawnState;

    @Unique
    private SpawnCounts vanadium$spawnCounts;

    @Unique
    private SpawnCounts vanadium$spawnCounts() {
        if (vanadium$spawnCounts == null) vanadium$spawnCounts = new SpawnCounts(level, chunkMap);
        return vanadium$spawnCounts;
    }

    /** A count skipped while nothing used it is made when first read; see {@link SpawnCounts}. */
    @ModifyReturnValue(method = "getLastSpawnState", at = @At("RETURN"))
    private NaturalSpawner.SpawnState countSkippedSpawnState(NaturalSpawner.SpawnState stored) {
        SpawnCounts counts = vanadium$spawnCounts;
        if (counts == null) return stored;
        NaturalSpawner.SpawnState state = counts.observe(stored);
        if (state != stored && state != null) lastSpawnState = state;
        return state;
    }

    @Shadow
    @Final
    @Mutable
    private Set<ChunkHolder> chunkHoldersToBroadcast =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
    private void beginChunkTicks(ProfilerFiller profiler, CallbackInfo ci) {
        Vanadium.scheduler.begin(Stage.SPAWNING);
        vanadium$spawns().clear();
    }

    /**
     * Ends spawning before vanilla's block-ticking pass. With parallel chunk ticks, the persistent
     * {@link ChunkTickBatch} replaces vanilla's iteration over ticking chunks; otherwise the vanilla
     * consumer ticks chunks inline.
     */
    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V"))
    private void tickChunksInWave(ChunkMap map, Consumer<LevelChunk> consumer, Operation<Void> original) {
        Vanadium.scheduler.run(Stage.SPAWNING);
        vanadium$spawns().runDeferred();
        Vanadium.scheduler.begin(Stage.CHUNK);
        if (Vanadium.config.enabled && Vanadium.config.parallelChunkTicks) {
            vanadium$chunkTicks().dispatch(Vanadium.scheduler, level.getGameRules().get(GameRules.RANDOM_TICK_SPEED));
        } else {
            original.call(map, consumer);
        }
        Vanadium.scheduler.run(Stage.CHUNK);
    }

    @Shadow @Final ServerLevel level;

    @Unique
    private ChunkTickBatch vanadium$chunkTickBatch;

    @Unique
    private SpawnTasks vanadium$spawnTasks;

    @Unique
    private SpawnTasks vanadium$spawns() {
        if (vanadium$spawnTasks == null) vanadium$spawnTasks = new SpawnTasks((ServerChunkCache) (Object) this);
        return vanadium$spawnTasks;
    }

    @Override
    public ChunkTickBatch vanadium$chunkTicks() {
        if (vanadium$chunkTickBatch == null) vanadium$chunkTickBatch = new ChunkTickBatch((ServerChunkCache) (Object) this, level);
        return vanadium$chunkTickBatch;
    }

    /** The spawning phase completes before vanilla's separate block-ticking phase begins. */
    @Redirect(method = "tickSpawningChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"))
    private void dispatchSpawning(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState info,
                                List<MobCategory> categories) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelSpawning) {
            NaturalSpawner.spawnForChunk(level, chunk, info, categories);
            return;
        }
        // A chunk whose spawn checks need the server thread reruns there after the wave.
        vanadium$spawns().enqueue(Vanadium.scheduler, chunk,
                () -> NaturalSpawner.spawnForChunk(level, chunk, info, categories));
    }

    @Redirect(method = "tickSpawningChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickThunder(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void dispatchThunder(ServerLevel level, LevelChunk chunk) {
        // Serial spawning keeps vanilla's order: each visited chunk's thunder runs here, inline, before its
        // eligibility checks and spawn attempt, whether or not an attempt follows.
        if (!Vanadium.config.enabled || !Vanadium.config.parallelChunkTicks || !Vanadium.config.parallelSpawning) {
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
        // Both getChunk counters stay off, as they were with per-chunk load locks.
    }

    @WrapMethod(method = "storeInCache")
    private synchronized void syncPutInCache(long pos, ChunkAccess chunk, ChunkStatus status, Operation<Void> original) {
        original.call(pos, chunk, status);
    }

}
