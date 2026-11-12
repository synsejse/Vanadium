package com.synsenetwork.vanadium.mixin.world;

import com.synsenetwork.vanadium.collections.ConcurrentIntObjectMap;
import com.synsenetwork.vanadium.tick.EntityTickAccess;
import com.synsenetwork.vanadium.tick.EntityTickTasks;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityTickList.class)
public abstract class EntityTickListMixin implements EntityTickAccess {
    @Shadow
    private Int2ObjectMap<Entity> active;

    @Shadow
    private Int2ObjectMap<Entity> passive;

    @Unique
    private EntityTickTasks vanadium$entityTicks;

    /** Installed explicitly: Mixin may drop shadow-field initializers once the class adds fields of its own. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$install(CallbackInfo ci) {
        active = new ConcurrentIntObjectMap<>();
        passive = new ConcurrentIntObjectMap<>();
        vanadium$entityTicks = new EntityTickTasks((EntityTickList) (Object) this, active);
    }

    @Override
    public EntityTickTasks vanadium$entityTicks() {
        return vanadium$entityTicks;
    }

    /** Never swaps the maps: EntityTickTasks reads {@code active} directly. */
    @Inject(method = "ensureActiveIsNotIterated", at = @At(value = "HEAD"), cancellable = true)
    private void notSafeAnyWay(CallbackInfo ci) {
        ci.cancel();
    }

    /** Adds and removes (from any thread, also during waves) reach the persistent tasks at the next dispatch. */
    @Inject(method = {"add", "remove"}, at = @At("RETURN"))
    private void vanadium$changed(Entity entity, CallbackInfo ci) {
        vanadium$entityTicks.getTasks().changed(entity);
    }
}
