package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import com.synsenetwork.vanadium.concurrent.ConcurrentLongLinkedOpenHashSet;
import com.synsenetwork.vanadium.concurrent.Int2ObjectConcurrentHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.world.ChunkLoadingManager;
import net.minecraft.world.chunk.ChunkLoader;
import net.minecraft.world.storage.StorageKey;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerMixin extends VersionedChunkStorage implements ChunkHolder.PlayersWatchingChunkProvider, ChunkLoadingManager {

    public ServerChunkLoadingManagerMixin(StorageKey storageKey, Path directory, DataFixer dataFixer, boolean dsync) {
        super(storageKey, directory, dataFixer, dsync);
    }

    @Shadow
    @Final
    @Mutable
    private Int2ObjectMap<ServerChunkLoadingManager.EntityTracker> entityTrackers = new Int2ObjectConcurrentHashMap<>();


    @Shadow
    @Final
    @Mutable
    private List<ChunkLoader> loaders = new CopyOnWriteArrayList<>();

    @Shadow
    @Final
    @Mutable
    final LongSet unloadedChunks = new ConcurrentLongLinkedOpenHashSet();

    @WrapMethod(method = "loadEntity")
    private synchronized void loadEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

    @WrapMethod(method = "unloadEntity")
    private synchronized void unloadEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

}