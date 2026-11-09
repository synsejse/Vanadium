package com.synsenetwork.vanadium.mixin.world.storage;

import com.synsenetwork.vanadium.collections.ConcurrentLongSet;
import com.synsenetwork.vanadium.collections.ConcurrentLongObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Optional;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin<R> {
    @Shadow
    @Final
    @Mutable
    private Long2ObjectMap<Optional<R>> storage = new ConcurrentLongObjectMap<>();

    @Shadow
    @Final
    @Mutable
    private LongLinkedOpenHashSet dirtyChunks = new ConcurrentLongSet();
}
