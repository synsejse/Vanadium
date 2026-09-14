package com.synsenetwork.vanadium.mixin.world.storage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

/** Protect the cache and save snapshots without holding a monitor while waiting for disk IO. */
@Mixin(SavedDataStorage.class)
public abstract class SavedDataStorageMixin {
    @WrapMethod(method = {"computeIfAbsent", "get"})
    private synchronized <T extends SavedData> T read(SavedDataType<T> type, Operation<T> original) {
        return original.call(type);
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
