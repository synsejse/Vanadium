package com.synsenetwork.vanadium.mixin.world.chunk;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.chunk.ParallelChunkCache;
import com.synsenetwork.vanadium.collections.ConcurrentIntObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelChunk.class, priority = 1100) // After Lithium: targets its merged listener-map setter.
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

    /** Removal pairs with {@link #getGameEventDispatcher}'s creation under the chunk's monitor. */
    @WrapMethod(method = "removeGameEventListenerRegistry")
    private synchronized void removeGameEventDispatcher(int ySectionCoord, Operation<Void> original) {
        original.call(ySectionCoord);
    }

    /**
     * Lithium's game-event dispatch reads a chunk's per-section listener map through its world storage without
     * the chunk's monitor, while listeners in other regions add and remove sections. The map it installs (lazily,
     * through this method) is concurrent. Absent when Lithium's {@code world.game_events.dispatch} is disabled;
     * vanilla reads the map only under the monitor.
     */
    @ModifyVariable(method = "setGameEventListenerRegistrySections", at = @At("HEAD"), argsOnly = true, require = 0)
    private Int2ObjectMap<GameEventListenerRegistry> concurrentListenerSections(Int2ObjectMap<GameEventListenerRegistry> sections) {
        if (sections == null || sections instanceof ConcurrentIntObjectMap) return sections;
        ConcurrentIntObjectMap<GameEventListenerRegistry> concurrent = new ConcurrentIntObjectMap<>();
        concurrent.putAll(sections);
        return concurrent;
    }

    /** Publishes full chunks to the lock-free lookup used by worker threads. */
    @Inject(method = "setLoaded", at = @At("TAIL"))
    private void publishLoaded(boolean loaded, CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel
                && serverLevel.getChunkSource() instanceof ParallelChunkCache cache) {
            cache.onChunkLoaded((LevelChunk) (Object) this, loaded);
        }
    }
}
