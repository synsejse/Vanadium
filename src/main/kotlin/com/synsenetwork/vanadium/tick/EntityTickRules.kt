package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.Vanadium
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile

/** A vehicle and its passengers execute together, so the strictest member decides their thread. */
object EntityTickRules {
    @JvmStatic
    fun requiresSerial(entity: Entity): Boolean {
        val config = Vanadium.config
        return entity is Projectile ||
                entity.portalProcess?.isInsidePortalThisTick == true ||
                config.hasSerialEntityRules() && config.isSerialEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)) ||
                entity.passengers.any(::requiresSerial)
    }
}
