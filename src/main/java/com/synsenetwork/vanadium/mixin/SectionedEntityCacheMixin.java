package com.synsenetwork.vanadium.mixin;

import it.unimi.dsi.fastutil.longs.LongSortedSet;
import com.synsenetwork.vanadium.concurrent.fastutil.ConcurrentLongSortedSet;
import net.minecraft.world.entity.EntityLike;
import net.minecraft.world.entity.SectionedEntityCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;


@Mixin(SectionedEntityCache.class)
public abstract class SectionedEntityCacheMixin<T extends EntityLike> {

//    @Shadow
//    @Final
//    private final Long2ObjectMap<EntityTrackingSection<T>> trackingSections = new Long2ObjectConcurrentHashMap<>();

    @Shadow
    @Final
    @Mutable
    private LongSortedSet trackedPositions = new ConcurrentLongSortedSet();

}
