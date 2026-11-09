package com.synsenetwork.vanadium.mixin.server.network;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerEntity.class)
public interface ServerEntityAccessor {
    @Accessor("entity")
    Entity vanadium$entity();

    @Accessor("teleportDelay")
    void vanadium$setUpdatesWithoutVehicle(int updatesWithoutVehicle);
}
