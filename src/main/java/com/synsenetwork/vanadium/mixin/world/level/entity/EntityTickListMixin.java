package com.synsenetwork.vanadium.mixin.world.level.entity;

import com.synsenetwork.vanadium.collections.ConcurrentIntObjectMap;
import com.synsenetwork.vanadium.stage.EntityStage;
import com.synsenetwork.vanadium.stage.EntityTickAccess;
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
    private EntityStage vanadium$entityStage;

    /** Installed explicitly: Mixin may drop shadow-field initializers once the class adds fields of its own. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$install(CallbackInfo ci) {
        active = new ConcurrentIntObjectMap<>();
        passive = new ConcurrentIntObjectMap<>();
        vanadium$entityStage = new EntityStage((EntityTickList) (Object) this, active);
    }

    @Override
    public EntityStage vanadium$entityStage() {
        return vanadium$entityStage;
    }

    /** Never swaps the maps: EntityStage reads {@code active} directly. */
    @Inject(method = "ensureActiveIsNotIterated", at = @At(value = "HEAD"), cancellable = true)
    private void notSafeAnyWay(CallbackInfo ci) {
        ci.cancel();
    }

    /** Adds and removes (from any thread, also during waves) reach the persistent tasks at the next dispatch. */
    @Inject(method = {"add", "remove"}, at = @At("RETURN"))
    private void vanadium$changed(Entity entity, CallbackInfo ci) {
        vanadium$entityStage.getTasks().changed(entity);
    }
}
