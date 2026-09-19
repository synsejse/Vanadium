package com.synsenetwork.vanadium.mixin.server.world;

import com.mojang.datafixers.DataFixer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.chunk.ParallelChunkManager;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tick.ScheduledTickAccess;
import com.synsenetwork.vanadium.tick.EntityTickRules;
import com.synsenetwork.vanadium.tracking.NavigationAccess;
import com.synsenetwork.vanadium.tracking.NavigationIndex;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.ticks.LevelTicks;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
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

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/chunk/ChunkGenerator;IIZLnet/minecraft/world/level/entity/ChunkStatusUpdateListener;Ljava/util/function/Supplier;)Lnet/minecraft/server/level/ServerChunkCache;"))
    private ServerChunkCache createChunkManager(ServerLevel world, LevelStorageSource.LevelStorageAccess session, DataFixer dataFixer, StructureTemplateManager structureTemplateManager, Executor workerExecutor, ChunkGenerator chunkGenerator, int viewDistance, int simulationDistance, boolean dsync, ChunkStatusUpdateListener chunkStatusChangeListener, Supplier<SavedDataStorage> savedDataStorageFactory) {
        return new ParallelChunkManager(world, session, dataFixer, structureTemplateManager, workerExecutor, chunkGenerator, viewDistance, simulationDistance, dsync, chunkStatusChangeListener, savedDataStorageFactory);
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

    @Redirect(method = "lambda$tick$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;guardEntityTick(Ljava/util/function/Consumer;Lnet/minecraft/world/entity/Entity;)V"))
    private void dispatchEntityTick(ServerLevel level, Consumer<Entity> ticker, Entity entity) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelEntities
                || EntityTickRules.requiresSerial(entity)) {
            ticker.accept(entity);
            return;
        }
        Vanadium.scheduler.enqueue(Stage.ENTITY, entity.chunkPosition().x(), entity.chunkPosition().z(),
                () -> ticker.accept(entity), Vanadium.scheduler.detailedDiagnostics()
                        ? BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString() : null);
    }

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
}
