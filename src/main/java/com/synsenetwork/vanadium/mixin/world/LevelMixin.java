package com.synsenetwork.vanadium.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import com.synsenetwork.vanadium.concurrent.PerThreadRandomSource;
import com.synsenetwork.vanadium.stage.BlockEntityStage;
import com.synsenetwork.vanadium.tick.Stage;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.levelgen.RandomSupport;
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
    /** Every thread draws from its own stream; see {@link PerThreadRandomSource}. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private RandomSource perThreadRandom() {
        return new PerThreadRandomSource(RandomSupport.generateUniqueSeed());
    }

    /** Block changes made by entity ticks are shared writes, one at a time. */
    @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean vanadium$setBlock(BlockPos pos, BlockState state, int flags, int limit, Operation<Boolean> original) {
        boolean scoped = Interactions.active && Interactions.enterShared(Interactions.Write.BLOCK);
        try {
            return original.call(pos, state, flags, limit);
        } finally {
            if (scoped) Interactions.exit();
        }
    }

    @Shadow
    @Final
    private Thread thread;

    @Shadow
    @Final
    protected List<TickingBlockEntity> blockEntityTickers;

    /** Server levels only: the level's BLOCK_ENTITY stage. Client levels keep vanilla's ticking. */
    @Unique
    private BlockEntityStage vanadium$blockEntityStage;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$install(CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel level) {
            vanadium$blockEntityStage = new BlockEntityStage(level, blockEntityTickers);
        }
    }

    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"), cancellable = true)
    private void queueBlockEntityTicker(TickingBlockEntity ticker, CallbackInfo ci) {
        BlockEntityStage stage = vanadium$blockEntityStage;
        if (stage != null) {
            stage.add(ticker);
            ci.cancel();
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void beginBlockEntityTicks(CallbackInfo ci) {
        BlockEntityStage stage = vanadium$blockEntityStage;
        if (stage != null) stage.begin();
    }

    @Inject(method = "tickBlockEntities", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;tickingBlockEntities:Z", opcode = Opcodes.PUTFIELD, ordinal = 1))
    private void finishBlockEntityTicks(CallbackInfo ci) {
        if (vanadium$blockEntityStage != null) Vanadium.scheduler.run(Stage.BLOCK_ENTITY);
    }

    @Redirect(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void dispatchBlockEntityTick(TickingBlockEntity ticker) {
        BlockEntityStage stage = vanadium$blockEntityStage;
        if (stage == null) {
            ticker.tick();
            return;
        }
        stage.tick(ticker);
    }

    @Redirect(method = "getBlockEntity", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
    private Thread overwriteCurrentThread() {
        return this.thread;
    }

}
