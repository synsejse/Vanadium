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
        Vanadium.scheduler.enqueue(Stage.BLOCK_ENTITY, chunkX, chunkZ, blockEntityTickInvoker::tick);
    }

    @Redirect(method = "getBlockEntity", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
    private Thread overwriteCurrentThread() {
        return this.thread;
    }

//    @Redirect(method = "getChunk(II)Lnet/minecraft/world/chunk/WorldChunk;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getChunk(IILnet/minecraft/world/chunk/ChunkStatus;)Lnet/minecraft/world/chunk/Chunk;"))
//    private Chunk getChunk(World world, int x, int z, net.minecraft.world.chunk.ChunkStatus leastStatus, int i, int j) {
//        Chunk chunk;
//        long startTime, counter = -1;
//        startTime = System.currentTimeMillis();
//
//        do {
//            chunk = world.getChunk(x, z, leastStatus);
//            counter++;
//            if (counter>0)
//                System.out.println("getChunk() retry: " + counter);
//        } while (chunk instanceof ReadOnlyChunk);
//
//        if (counter > 0) {
//            Vanadium.LOGGER.warn("Chunk at " + x + ", " + z + " was ReadOnlyChunk for " + counter + " times before completely loaded. Took " + (System.currentTimeMillis() - startTime) + "ms");
//        }
//        return chunk;
//    }

}