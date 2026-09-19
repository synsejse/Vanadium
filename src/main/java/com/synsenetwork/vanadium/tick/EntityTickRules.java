package com.synsenetwork.vanadium.tick;

import com.synsenetwork.vanadium.Vanadium;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

/** A vehicle and its passengers execute together, so the strictest member decides their thread. */
public final class EntityTickRules {
    private EntityTickRules() {}

    public static boolean requiresSerial(Entity entity) {
        if (entity instanceof Projectile
                || entity.portalProcess != null && entity.portalProcess.isInsidePortalThisTick()
                || Vanadium.config.isSerialEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))) {
            return true;
        }
        for (Entity passenger : entity.getPassengers()) {
            if (requiresSerial(passenger)) return true;
        }
        return false;
    }
}
