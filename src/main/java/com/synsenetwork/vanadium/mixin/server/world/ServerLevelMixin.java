package com.synsenetwork.vanadium.mixin.server.world;

import com.mojang.datafixers.DataFixer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.chunk.ParallelChunkCache;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tick.ScheduledTickAccess;
import com.synsenetwork.vanadium.tick.EntityBatch;
import com.synsenetwork.vanadium.tracking.NavigationAccess;
import com.synsenetwork.vanadium.tracking.NavigationIndex;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.ticks.LevelTicks;
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

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements NavigationAccess {

    @Shadow
    @Final
    @Mutable
    Set<Mob> navigatingMobs = new NavigationIndex();

    @Override
    public NavigationIndex vanadium$navigations() {
        return (NavigationIndex) navigatingMobs;
    }

    @Redirect(method = "sendBlockUpdated", at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;"))
    private Iterator<Mob> navigationCandidates(Set<Mob> mobs, BlockPos pos) {
        return Vanadium.config.enabled ? vanadium$navigations().candidates(pos).iterator() : mobs.iterator();
    }

    @Shadow
    @Final
    private ObjectLinkedOpenHashSet<BlockEventData> blockEvents;

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/chunk/ChunkGenerator;IIZLnet/minecraft/world/level/entity/ChunkStatusUpdateListener;)Lnet/minecraft/server/level/ServerChunkCache;"))
    private ServerChunkCache createChunkCache(ServerLevel world, LevelStorageSource.LevelStorageAccess session, DataFixer dataFixer, StructureTemplateManager structureTemplateManager, Executor workerExecutor, ChunkGenerator chunkGenerator, int viewDistance, int simulationDistance, boolean dsync, ChunkStatusUpdateListener chunkStatusChangeListener) {
        return new ParallelChunkCache(world, session, dataFixer, structureTemplateManager, workerExecutor, chunkGenerator, viewDistance, simulationDistance, dsync, chunkStatusChangeListener);
    }

    /** Keep pending/executed ticks visible until the wave finishes, outside the scheduler monitor. */
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"))
    private <T> void dispatchScheduledTicks(LevelTicks<T> ticks, long time, int maxTicks, BiConsumer<BlockPos, T> ticker) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelScheduledTicks) {
            ticks.tick(time, maxTicks, ticker);
            return;
        }
        ScheduledTickAccess<T> access = (ScheduledTickAccess<T>) ticks;
        Vanadium.scheduler.begin(Stage.SCHEDULED_TICK);
        try {
            for (var tick : access.vanadium$collect(time, maxTicks)) {
                Vanadium.scheduler.enqueue(Stage.SCHEDULED_TICK, tick.pos().getX() >> 4, tick.pos().getZ() >> 4,
                        () -> {
                            if (access.vanadium$claim(tick)) ticker.accept(tick.pos(), tick.type());
                        }, null);
            }
            Vanadium.scheduler.run(Stage.SCHEDULED_TICK);
        } finally {
            access.vanadium$finish();
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void beginEntityTicks(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        Vanadium.scheduler.begin(Stage.ENTITY);
    }

    /**
     * Collects entities into ENTITY cells with vanilla's complete per-entity body (removal and frozen
     * checks, despawn check, ticking-range lookup, vehicle handling, tick), so the server thread only
     * sorts entities into cells. Serial-rule entities run the body inline, as before the wave.
     */
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void dispatchEntityTicks(EntityTickList list, Consumer<Entity> body) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelEntities) {
            list.forEach(body);
            return;
        }
        // Record only; workers map entities to cells, then cells are filled in iteration order.
        list.forEach(vanadium$entityBatch::add);
        vanadium$entityBatch.dispatch(Vanadium.scheduler, body);
    }

    @Unique
    private final EntityBatch vanadium$entityBatch = new EntityBatch();

    @WrapMethod(method = "blockEvent")
    private void queueBlockEvent(BlockPos pos, Block block, int type, int data, Operation<Void> original) {
        synchronized (blockEvents) {
            original.call(pos, block, type, data);
        }
    }

    @WrapMethod(method = "clearBlockEvents")
    private void clearBlockEvents(BoundingBox area, Operation<Void> original) {
        synchronized (blockEvents) {
            original.call(area);
        }
    }

    @WrapMethod(method = "runBlockEvents")
    private void runBlockEvents(Operation<Void> original) {
        // One serial consumer; callbacks can reentrantly enqueue into the same ordered set.
        synchronized (blockEvents) {
            original.call();
        }
    }

    @Redirect(method = "sendBlockUpdated", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;isUpdatingNavigations:Z", opcode = Opcodes.PUTFIELD))
    private void skipSendBlockUpdatedCheck(ServerLevel instance, boolean value) {

    }

    /**
     * Biome reads (mob AI, weather, spawning) ask for {@code BIOMES} status, which vanilla serves through
     * the chunk-load path: a per-chunk load lock, the synchronized four-entry cache and its shared arrays.
     * For a loaded full chunk vanilla returns its {@code ImposterProtoChunk}, which delegates biome reads
     * to the full chunk, so the lock-free full-chunk lookup gives the same answer.
     */
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
        ServerChunkCache source = ((ServerLevel) (Object) this).getChunkSource();
        int chunkX = QuartPos.toSection(quartX);
        int chunkZ = QuartPos.toSection(quartZ);
        LevelChunk loaded = source.getChunkNow(chunkX, chunkZ);
        if (loaded != null) return loaded.getNoiseBiome(quartX, quartY, quartZ);
        ChunkAccess chunk = source.getChunk(chunkX, chunkZ, ChunkStatus.BIOMES, false);
        return chunk != null ? chunk.getNoiseBiome(quartX, quartY, quartZ)
                : ((ServerLevel) (Object) this).getUncachedNoiseBiome(quartX, quartY, quartZ);
    }
}
