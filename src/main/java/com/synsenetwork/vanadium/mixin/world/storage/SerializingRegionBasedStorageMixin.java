package com.synsenetwork.vanadium.mixin.world.storage;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import com.synsenetwork.vanadium.concurrent.ConcurrentLongLinkedOpenHashSet;
import com.synsenetwork.vanadium.concurrent.Long2ObjectConcurrentHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;
import net.minecraft.world.level.chunk.storage.SectionStorage;

@Mixin(SectionStorage.class)
public abstract class SerializingRegionBasedStorageMixin<R> implements AutoCloseable {
    @Shadow
    @Final
    @Mutable
    private Long2ObjectMap<Optional<R>> storage = new Long2ObjectConcurrentHashMap<>();

    @Shadow
    @Final
    @Mutable
    private LongLinkedOpenHashSet dirtyChunks = new ConcurrentLongLinkedOpenHashSet();
}
