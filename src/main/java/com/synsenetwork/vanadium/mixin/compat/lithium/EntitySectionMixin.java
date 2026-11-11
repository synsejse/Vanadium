package com.synsenetwork.vanadium.mixin.compat.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import net.caffeinemc.mods.lithium.common.entity.pushable.EntityPushablePredicate;
import net.minecraft.util.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Lithium's cramming shortcut lets a reader of a crowded section start filtering it, and pushability checks
 * update that filter. Writers are the section's own region, but at isolation 1 the regions on both sides of a
 * section can read it at once, and two starts would add each entity twice (Lithium then throws). Readers of one
 * section serialize; the lock is per section and uncontended except on such boundaries.
 */
@Mixin(value = EntitySection.class, priority = 1100) // After Lithium: wraps its merged method.
public abstract class EntitySectionMixin {
    @WrapMethod(method = "lithium$collectPushableEntities", require = 0)
    private Continuation vanadium$collectPushableEntities(Level level, Entity except, AABB box,
            EntityPushablePredicate<? super Entity> predicate, ArrayList<Entity> entities, Operation<Continuation> original) {
        synchronized (this) {
            return original.call(level, except, box, predicate, entities);
        }
    }
}
