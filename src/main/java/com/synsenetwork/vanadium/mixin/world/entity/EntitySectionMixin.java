package com.synsenetwork.vanadium.mixin.world.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.entity.CrowdSlot;
import com.synsenetwork.vanadium.entity.CrowdSlotAccess;
import com.synsenetwork.vanadium.entity.CrowdedSection;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Box queries in crowded sections read only the entities near the box ({@link CrowdedSection}); everything
 * else, and any query whose read overlapped a write, runs vanilla's scan. Adds and removes update the
 * section's list and its grid under the grid's monitor, so a validated read sees both at one instant and
 * members keep the list's order.
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin<T extends EntityAccess> implements CrowdSlotAccess<T> {
    @Shadow @Final private ClassInstanceMultiMap<T> storage;

    @Unique
    private @Nullable CrowdSlot<T> vanadium$crowdSlot;

    @Override
    public @Nullable CrowdSlot<T> vanadium$crowdSlot() {
        return vanadium$crowdSlot;
    }

    @Override
    public void vanadium$setCrowdSlot(CrowdSlot<T> slot) {
        this.vanadium$crowdSlot = slot;
    }

    @WrapMethod(method = "add")
    private void vanadium$add(T entity, Operation<Void> original) {
        CrowdSlot<T> slot = vanadium$crowdSlot;
        CrowdedSection<T> grid = slot == null ? null : slot.grid;
        if (grid == null) {
            original.call(entity);
            if (slot != null) slot.offer();
            return;
        }
        synchronized (grid) {
            original.call(entity);
            grid.addLocked(entity);
        }
    }

    @WrapMethod(method = "remove")
    private boolean vanadium$remove(T entity, Operation<Boolean> original) {
        CrowdSlot<T> slot = vanadium$crowdSlot;
        CrowdedSection<T> grid = slot == null ? null : slot.grid;
        if (grid == null) return original.call(entity);
        synchronized (grid) {
            boolean removed = original.call(entity);
            grid.removeLocked(entity);
            slot.removedLocked(grid);
            return removed;
        }
    }

    @WrapMethod(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/Continuation;")
    private Continuation vanadium$getEntities(AABB bb, AbortableIterationConsumer<T> entities, Operation<Continuation> original) {
        CrowdSlot<T> slot = vanadium$crowdSlot;
        CrowdedSection<T> grid = slot == null ? null : slot.grid;
        if (grid != null && Vanadium.config.crowdedSectionIndex) {
            Continuation result = grid.query(bb, entities);
            if (result != null) return result;
        }
        return original.call(bb, entities);
    }

    @WrapMethod(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/Continuation;")
    private <U extends T> Continuation vanadium$getEntitiesOfType(EntityTypeTest<T, U> type, AABB bb, AbortableIterationConsumer<? super U> consumer,
                                                                 Operation<Continuation> original) {
        CrowdSlot<T> slot = vanadium$crowdSlot;
        CrowdedSection<T> grid = slot == null ? null : slot.grid;
        // Vanilla's empty-class shortcut is cheaper than any prefilter; sparse classes are cheap to scan anyway.
        if (grid != null && Vanadium.config.crowdedSectionIndex && storage.find(type.getBaseClass()).size() >= MIN_TYPED) {
            Continuation result = grid.query(type, bb, consumer);
            if (result != null) return result;
        }
        return original.call(type, bb, consumer);
    }

    @Unique
    private static final int MIN_TYPED = 8;
}
