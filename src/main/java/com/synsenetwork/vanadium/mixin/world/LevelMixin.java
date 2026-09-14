package com.synsenetwork.vanadium.mixin.world;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.Stage;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Shadow
    @Final
    private Thread thread;

    @Shadow
    @Final
    protected List<TickingBlockEntity> blockEntityTickers;

    @Unique
    private final ConcurrentLinkedQueue<TickingBlockEntity> vanadium$pendingTickers = new ConcurrentLinkedQueue<>();

    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"), cancellable = true)
    private void queueBlockEntityTicker(TickingBlockEntity ticker, CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel) {
            vanadium$pendingTickers.add(ticker);
            ci.cancel();
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void beginBlockEntityTicks(CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel) {
            Vanadium.scheduler.run(Stage.ENTITY);
            // Only the server thread mutates the active list. Registrations during a wave
            // stay queued until the next block-entity phase, as with vanilla pending tickers.
            TickingBlockEntity ticker;
            while ((ticker = vanadium$pendingTickers.poll()) != null) {
                blockEntityTickers.add(ticker);
            }
            Vanadium.scheduler.begin(Stage.BLOCK_ENTITY);
        }
    }

    @Inject(method = "tickBlockEntities", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;tickingBlockEntities:Z", opcode = Opcodes.PUTFIELD, ordinal = 1))
    private void finishBlockEntityTicks(CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel) {
            Vanadium.scheduler.run(Stage.BLOCK_ENTITY);
        }
    }

    @Redirect(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void dispatchBlockEntityTick(TickingBlockEntity ticker) {
        if (!((Object) this instanceof ServerLevel)
                || !Vanadium.config.enabled || !Vanadium.config.parallelBlockEntities
                || !(ticker instanceof LevelChunk.RebindableTickingBlockEntityWrapper wrapped)
                || !(wrapped.ticker instanceof LevelChunk.BoundTickingBlockEntity<?> direct)) {
            ticker.tick();
            return;
        }
        BlockEntity blockEntity = direct.blockEntity;
        if (Vanadium.config.isSerialBlockEntity(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()))) {
            ticker.tick();
            return;
        }
        int chunkX = blockEntity.getBlockPos().getX() >> 4;
        int chunkZ = blockEntity.getBlockPos().getZ() >> 4;
        Vanadium.scheduler.enqueue(Stage.BLOCK_ENTITY, chunkX, chunkZ, ticker::tick,
                Vanadium.scheduler.detailedDiagnostics()
                        ? BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString() : null);
    }

    @Redirect(method = "getBlockEntity", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
    private Thread overwriteCurrentThread() {
        return this.thread;
    }

}
