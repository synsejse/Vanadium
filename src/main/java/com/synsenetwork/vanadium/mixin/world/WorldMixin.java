package com.synsenetwork.vanadium.mixin.world;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.Stage;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public abstract class WorldMixin implements WorldAccess, AutoCloseable {
    @Shadow
    @Final
    @Mutable
    private Thread thread;

    @Inject(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private void postEntityPreBlockEntityTick(CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld) {
            Vanadium.scheduler.run(Stage.ENTITY);
            Vanadium.scheduler.begin(Stage.BLOCK_ENTITY);
        }
    }

    @Inject(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiler/Profiler;pop()V"))
    private void postBlockEntityTick(CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld) {
            Vanadium.scheduler.run(Stage.BLOCK_ENTITY);
        }
    }

    @Redirect(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/BlockEntityTickInvoker;tick()V"))
    private void overwriteBlockEntityTick(BlockEntityTickInvoker blockEntityTickInvoker) {
        if (!((Object) this instanceof ServerWorld)
                || Vanadium.config.disabled || Vanadium.config.disableBlockEntity
                || !(blockEntityTickInvoker instanceof WorldChunk.WrappedBlockEntityTickInvoker wrapped)
                || !(wrapped.wrapped instanceof WorldChunk.DirectBlockEntityTickInvoker<?> direct)) {
            blockEntityTickInvoker.tick();
            return;
        }
        BlockEntity blockEntity = direct.blockEntity;
        int chunkX = blockEntity.getPos().getX() >> 4;
        int chunkZ = blockEntity.getPos().getZ() >> 4;
        if (Vanadium.debug.isSampling()) {
            long pos = blockEntity.getPos().asLong();
            Vanadium.scheduler.enqueue(Stage.BLOCK_ENTITY, chunkX, chunkZ, () -> {
                long start = System.nanoTime();
                blockEntityTickInvoker.tick();
                Vanadium.debug.recordBlockEntity((ServerWorld) (Object) this, pos, System.nanoTime() - start);
            });
        } else {
            Vanadium.scheduler.enqueue(Stage.BLOCK_ENTITY, chunkX, chunkZ, blockEntityTickInvoker::tick);
        }
    }

    @Redirect(method = "getBlockEntity", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
    private Thread overwriteCurrentThread() {
        return this.thread;
    }

}