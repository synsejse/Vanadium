package com.synsenetwork.vanadium.mixin.world.storage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.collections.ConcurrentLongSet;
import com.synsenetwork.vanadium.compat.LithiumCompat;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Optional;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Priority above Lithium's default so its constructor hook runs after Lithium's `ai.poi` replaces storage. */
@Mixin(value = SectionStorage.class, priority = 1100)
public abstract class SectionStorageMixin<R> {
    @Shadow
    @Final
    @Mutable
    private Long2ObjectMap<Optional<R>> storage;

    @Shadow
    @Final
    @Mutable
    private LongLinkedOpenHashSet dirtyChunks;

    @Unique
    private final Object[] vanadium$unpackLocks = vanadium$locks();

    @Unique
    private static Object[] vanadium$locks() {
        Object[] locks = new Object[32];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    /**
     * Installed explicitly rather than through field initializers, whose merge order is not guaranteed once
     * other mixins add fields. Runs after Lithium's `ai.poi` constructor hook, whose index it keeps.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$installConcurrentStorage(CallbackInfo ci) {
        this.storage = LithiumCompat.concurrentSectionStorage((SectionStorage<?, ?>) (Object) this, this.storage);
        ConcurrentLongSet dirty = new ConcurrentLongSet();
        dirty.addAll(this.dirtyChunks);
        this.dirtyChunks = dirty;
    }

    /**
     * Vanilla marks a column loaded before storing its sections, so a second caller could return early and
     * read a missing section (`getOrLoad` then throws). Callers for one column wait for the first to finish.
     * The wait covers only that column's disk read and unpacking, never the server thread.
     */
    @WrapMethod(method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;)V")
    private void vanadium$unpackOnce(ChunkPos pos, Operation<Void> original) {
        synchronized (vanadium$unpackLocks[(int) HashCommon.mix(pos.pack()) & (vanadium$unpackLocks.length - 1)]) {
            original.call(pos);
        }
    }
}
