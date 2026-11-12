package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.Vanadium
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.AreaEffectCloud
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.animal.golem.CopperGolem
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.monster.warden.Warden
import net.minecraft.world.entity.projectile.EvokerFangs
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT

/**
 * A vehicle and its passengers execute together, so the strictest member decides their thread.
 * [fixedSerial] depends only on the entity's type and the configured rules; [changingSerial] on portal
 * contact and passengers, which [EntityTickTasks] learns of through portal-entry and mount hooks.
 */
object EntityTickRules {
    @JvmStatic
    fun requiresSerial(entity: Entity): Boolean = fixedSerial(entity) || changingSerial(entity)

    @JvmStatic
    fun fixedSerial(entity: Entity): Boolean {
        val config = Vanadium.config
        return entity is Projectile || changesManyOthers(entity) ||
                config.hasSerialEntityRules() && config.isSerialEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.type))
    }

    @JvmStatic
    fun changingSerial(entity: Entity): Boolean =
        entity.portalProcess?.isInsidePortalThisTick == true || entity.passengers.any(::requiresSerial)

    /**
     * Entities whose ticks mostly change other things: blocks and entities in bulk (explosives, bosses,
     * lightning, effect clouds, fangs), containers (hopper and chest minecarts, copper golems) or listeners
     * that other ticks write to (wardens). In the entity wave each of their writes would queue for a lock,
     * so they run on the server thread before it, as projectiles do.
     */
    private fun changesManyOthers(entity: Entity): Boolean =
        entity is PrimedTnt || entity is MinecartTNT || entity is AbstractMinecartContainer || entity is EnderDragon ||
                entity is WitherBoss || entity is EndCrystal || entity is LightningBolt || entity is AreaEffectCloud ||
                entity is EvokerFangs || entity is CopperGolem || entity is Warden
}
