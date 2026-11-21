package com.synsenetwork.vanadium.mixin.world.level.chunk;

import com.synsenetwork.vanadium.stage.BlockEntityTaskAccess;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@code BlockEntityStage} enqueue the wrapper directly as a {@link Runnable} without allocating a
 * per-tick {@code ticker::tick} closure, and caches its serial-rule classification across ticks until the
 * rules change or the wrapper is rebound.
 */
@Mixin(LevelChunk.RebindableTickingBlockEntityWrapper.class)
public abstract class RebindableTickingBlockEntityWrapperMixin implements BlockEntityTaskAccess {
    @Shadow
    public abstract void tick();

    @Unique
    private Object vanadium$serialRulesKey;

    @Unique
    private boolean vanadium$serial;

    @Override
    public void run() {
        this.tick();
    }

    @Override
    public Object vanadium$serialRulesKey() {
        return vanadium$serialRulesKey;
    }

    @Override
    public boolean vanadium$isSerial() {
        return vanadium$serial;
    }

    @Override
    public void vanadium$setSerial(Object rulesKey, boolean serial) {
        vanadium$serialRulesKey = rulesKey;
        vanadium$serial = serial;
    }

    @Inject(method = "rebind", at = @At("RETURN"))
    private void vanadium$clearSerialCache(TickingBlockEntity ticker, CallbackInfo ci) {
        vanadium$serialRulesKey = null;
    }
}
