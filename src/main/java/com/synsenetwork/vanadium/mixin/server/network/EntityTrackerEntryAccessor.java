package com.synsenetwork.vanadium.mixin.server.network;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityTrackerEntry.class)
public interface EntityTrackerEntryAccessor {
    @Accessor("entity")
    Entity vanadium$entity();

    @Accessor("trackingTick")
    int vanadium$trackingTick();

    @Accessor("trackingTick")
    void vanadium$setTrackingTick(int trackingTick);

    @Accessor("tickInterval")
    int vanadium$tickInterval();

    @Accessor("updatesWithoutVehicle")
    void vanadium$setUpdatesWithoutVehicle(int updatesWithoutVehicle);
}
