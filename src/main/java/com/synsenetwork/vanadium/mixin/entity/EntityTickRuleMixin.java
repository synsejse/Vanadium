package com.synsenetwork.vanadium.mixin.entity;

import com.synsenetwork.vanadium.tick.EntityTickAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports the events that can make an entity tree serial ({@code EntityTickRules.changingSerial}): portal
 * contact and a new passenger. Its persistent tick task is reclassified, with its vehicles, at the next
 * dispatch; leaving a portal or losing a passenger is noticed there without a report.
 */
@Mixin(Entity.class)
public abstract class EntityTickRuleMixin {
    @Shadow
    public abstract Level level();

    @Inject(method = {"setAsInsidePortal", "addPassenger"}, at = @At("RETURN"))
    private void vanadium$reclassify(CallbackInfo ci) {
        if (level() instanceof ServerLevel level) {
            ((EntityTickAccess) level).vanadium$entityTicks().getTasks().reclassify((Entity) (Object) this);
        }
    }
}
