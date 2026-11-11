package com.synsenetwork.vanadium.mixin.compat.lithium;

import net.caffeinemc.mods.lithium.common.util.collections.ListeningLong2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The callbacks of Lithium's POI section map, which {@code LithiumCompat} moves to a concurrent map. */
@Mixin(ListeningLong2ObjectOpenHashMap.class)
public interface ListeningLong2ObjectOpenHashMapAccessor<V> {
    @Accessor("addCallback")
    ListeningLong2ObjectOpenHashMap.Callback<V> vanadium$addCallback();

    @Accessor("removeCallback")
    ListeningLong2ObjectOpenHashMap.Callback<V> vanadium$removeCallback();
}
