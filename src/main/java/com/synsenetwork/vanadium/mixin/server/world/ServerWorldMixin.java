package com.synsenetwork.vanadium.mixin.server.world;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;

import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.chunk.ParallelChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.ticks.LevelTicks;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Mixin(ServerLevel.class)
public abstract class ServerWorldMixin implements WorldGenLevel {

    @Unique
    ConcurrentLinkedDeque<BlockEventData> syncedBlockEventCLinkedQueue = new ConcurrentLinkedDeque<BlockEventData>();

    @Shadow
    @Final
    @Mutable
    Set<Mob> navigatingMobs = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Shadow
    @Final
    @Mutable
    private ObjectLinkedOpenHashSet<BlockEventData> blockEvents = null;

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/chunk/ChunkGenerator;IIZLnet/minecraft/world/level/entity/ChunkStatusUpdateListener;Ljava/util/function/Supplier;)Lnet/minecraft/server/level/ServerChunkCache;"))
    private ServerChunkCache overwriteServerChunkManager(ServerLevel world, LevelStorageSource.LevelStorageAccess session, DataFixer dataFixer, StructureTemplateManager structureTemplateManager, Executor workerExecutor, ChunkGenerator chunkGenerator, int viewDistance, int simulationDistance, boolean dsync, ChunkStatusUpdateListener chunkStatusChangeListener, Supplier<SavedDataStorage> persistentStateManagerFactory) {
        return new ParallelChunkManager(world, session, dataFixer, structureTemplateManager, workerExecutor, chunkGenerator, viewDistance, simulationDistance, dsync, chunkStatusChangeListener, persistentStateManagerFactory);
    }

    /**
     * Runs each scheduler's due ticks as a parallel wave. The ticker handed to
     * {@link net.minecraft.world.ticks.LevelTicks#runCollectedTicks} enqueues into cells instead of
     * executing, and the wave runs only after tick() returns — the scheduler's monitor
     * (ACC_SYNCHRONIZED via SyncAllMixin) is released by then, so workers scheduling follow-up
     * ticks (fluid spread does every step) can't deadlock against the barrier. Per-chunk order is
     * preserved (cells run their tasks in insertion order); only cross-chunk interleaving becomes
     * nondeterministic, matching the rest of the cell model.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"))
    private void overwriteScheduledTicks(LevelTicks instance, long time, int maxTicks, BiConsumer ticker) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelScheduledTicks) {
            instance.tick(time, maxTicks, ticker);
            return;
        }
        Vanadium.scheduler.begin(Stage.SCHEDULED_TICK);
        instance.tick(time, maxTicks, (BiConsumer<BlockPos, Object>) (pos, type) ->
                Vanadium.scheduler.enqueue(Stage.SCHEDULED_TICK, pos.getX() >> 4, pos.getZ() >> 4,
                        () -> ticker.accept(pos, type), null));
        Vanadium.scheduler.run(Stage.SCHEDULED_TICK);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void preEntityTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        Vanadium.scheduler.begin(Stage.ENTITY);
    }

    @SuppressWarnings("unchecked")
    @Redirect(method = "lambda$tick$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;guardEntityTick(Ljava/util/function/Consumer;Lnet/minecraft/world/entity/Entity;)V"))
    private void overwriteEntityTicking(ServerLevel instance, Consumer consumer, Entity entity) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelEntities
                || (entity.portalProcess != null && entity.portalProcess.isInsidePortalThisTick())
                || entity instanceof Projectile
                || Vanadium.config.isSerialEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))) {
            consumer.accept(entity);
            return;
        }
        Vanadium.scheduler.enqueue(Stage.ENTITY, entity.chunkPosition().x(), entity.chunkPosition().z(),
                () -> consumer.accept(entity), Vanadium.scheduler.detailedDiagnostics()
                        ? BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString() : null);
    }

    @Redirect(method = "blockEvent", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;add(Ljava/lang/Object;)Z"))
    private boolean overwriteQueueAdd(ObjectLinkedOpenHashSet<BlockEventData> objectLinkedOpenHashSet, Object object) {
        return syncedBlockEventCLinkedQueue.add((BlockEventData) object);
    }

    @Redirect(method = "clearBlockEvents", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;removeIf(Ljava/util/function/Predicate;)Z"))
    private boolean overwriteQueueRemoveIf(ObjectLinkedOpenHashSet<BlockEventData> objectLinkedOpenHashSet, Predicate<BlockEventData> filter) {
        return syncedBlockEventCLinkedQueue.removeIf(filter);
    }

    @Redirect(method = "runBlockEvents", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;isEmpty()Z"))
    private boolean overwriteEmptyCheck(ObjectLinkedOpenHashSet<BlockEventData> objectLinkedOpenHashSet) {
        return syncedBlockEventCLinkedQueue.isEmpty();
    }

    @Redirect(method = "runBlockEvents", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;removeFirst()Ljava/lang/Object;"))
    private Object overwriteQueueRemoveFirst(ObjectLinkedOpenHashSet<BlockEventData> objectLinkedOpenHashSet) {
        return syncedBlockEventCLinkedQueue.removeFirst();
    }

    @Redirect(method = "runBlockEvents", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;addAll(Ljava/util/Collection;)Z"))
    private boolean overwriteQueueAddAll(ObjectLinkedOpenHashSet<BlockEventData> instance, Collection<? extends BlockEventData> c) {
        return syncedBlockEventCLinkedQueue.addAll(c);
    }

    @Redirect(method = "sendBlockUpdated", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;isUpdatingNavigations:Z", opcode = Opcodes.PUTFIELD))
    private void skipSendBlockUpdatedCheck(ServerLevel instance, boolean value) {

    }
}
