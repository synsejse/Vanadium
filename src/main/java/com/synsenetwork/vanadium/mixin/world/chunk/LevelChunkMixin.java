package com.synsenetwork.vanadium.mixin.world.chunk;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.chunk.ParallelChunkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    protected abstract void removeGameEventListenerRegistry(int ySectionCoord);

    @Shadow
    public abstract Level getLevel();

    @Shadow
    @Final
    Level level;

    @WrapMethod(method = "getListenerRegistry")
    private synchronized GameEventListenerRegistry getGameEventDispatcher(int ySectionCoord, Operation<GameEventListenerRegistry> original) {
        GameEventListenerRegistry dispatcher = original.call(ySectionCoord);
        if (dispatcher == null && this.level instanceof ServerLevel serverWorld) {
            return new EuclideanGameEventListenerRegistry(serverWorld, ySectionCoord, this::removeGameEventListenerRegistry);
        }
        return dispatcher;
    }

    /** Publishes full chunks to the lock-free lookup used by worker threads. */
    @Inject(method = "setLoaded", at = @At("TAIL"))
    private void publishLoaded(boolean loaded, CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel
                && serverLevel.getChunkSource() instanceof ParallelChunkManager manager) {
            manager.onChunkLoaded((LevelChunk) (Object) this, loaded);
        }
    }
}
