package com.synsenetwork.vanadium.mixin.world.storage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Cached reads are lock-free: {@code ServerLevel.getWorldBorder()} resolves saved data on every
 * entity move. Misses, writes and save snapshots keep one monitor, which is never held while
 * waiting for disk IO.
 */
@Mixin(SavedDataStorage.class)
public abstract class SavedDataStorageMixin {
    @Shadow
    @Final
    @Mutable
    private Map<SavedDataType<?>, Optional<SavedData>> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @WrapMethod(method = {"computeIfAbsent", "get"})
    private <T extends SavedData> T read(SavedDataType<T> type, Operation<T> original) {
        Optional<SavedData> cached = cache.get(type);
        if (cached != null && cached.isPresent()) {
            return (T) cached.get();
        }
        synchronized (this) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "set")
    private synchronized <T extends SavedData> void set(SavedDataType<T> type, T data, Operation<Void> original) {
        original.call(type, data);
    }

    @WrapMethod(method = "scheduleSave")
    private synchronized CompletableFuture<?> scheduleSave(Operation<CompletableFuture<?>> original) {
        return original.call();
    }
}
