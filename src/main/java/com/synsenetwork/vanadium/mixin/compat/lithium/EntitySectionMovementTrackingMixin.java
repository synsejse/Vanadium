package com.synsenetwork.vanadium.mixin.compat.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.compat.LithiumCompat;
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Lithium's entity movement tracking (option {@code util.entity_movement_tracking}, used by hoppers) keeps
 * per-section lists of trackers waiting for a move, and per-tracker lists and listener sets. Item and container
 * entities notify their section on every position write and on section changes: the section empties its
 * waiting list into each tracker, which wakes its hoppers. Entities tick in one flat wave, so two of them in
 * one section, or in two sections one tracker watches, notify at once; the plain lists race (a tracker lost from
 * a list stops its hoppers seeing items). Everything that touches that state holds
 * {@link LithiumCompat#MOVEMENT_TRACKING}.
 * <p>
 * A section no tracker ever registered with has empty lists, and only its move timestamps change (to the same
 * game time from every writer in a wave), so its entities notify without the lock: that is nearly every section.
 * Trackers register from hopper ticks, never during the entity wave, and set the flag before their first list
 * entry. Absent when the option is disabled ({@code require = 0}).
 */
@Mixin(value = EntitySection.class, priority = 1100) // After Lithium: wraps the methods it merges.
public abstract class EntitySectionMovementTrackingMixin {
    @Unique
    private volatile boolean vanadium$tracked;

    @WrapMethod(method = "lithium$trackEntityMovement", require = 0)
    private void vanadium$trackEntityMovement(int notificationMask, long time, Operation<Void> original) {
        if (!this.vanadium$tracked) {
            original.call(notificationMask, time);
            return;
        }
        synchronized (LithiumCompat.MOVEMENT_TRACKING) {
            original.call(notificationMask, time);
        }
    }

    @WrapMethod(method = "lithium$addListener", require = 0)
    private void vanadium$addListener(SectionedEntityMovementTracker<?> listener, Operation<Void> original) {
        synchronized (LithiumCompat.MOVEMENT_TRACKING) {
            this.vanadium$tracked = true;
            original.call(listener);
        }
    }

    @WrapMethod(method = "lithium$removeListener", require = 0)
    private void vanadium$removeListener(EntitySectionStorage<?> storage, SectionedEntityMovementTracker<?> listener, Operation<Void> original) {
        synchronized (LithiumCompat.MOVEMENT_TRACKING) {
            original.call(storage, listener);
        }
    }

    @WrapMethod(method = "lithium$listenToMovementOnce", require = 0)
    private void vanadium$listenToMovementOnce(SectionedEntityMovementTracker<?> listener, int trackedClass, Operation<Void> original) {
        synchronized (LithiumCompat.MOVEMENT_TRACKING) {
            this.vanadium$tracked = true;
            original.call(listener, trackedClass);
        }
    }

    @WrapMethod(method = "lithium$removeListenToMovementOnce", require = 0)
    private void vanadium$removeListenToMovementOnce(SectionedEntityMovementTracker<?> listener, int trackedClass, Operation<Void> original) {
        synchronized (LithiumCompat.MOVEMENT_TRACKING) {
            original.call(listener, trackedClass);
        }
    }

    /** A status change tells this section's trackers it entered or left range (Lithium's HEAD injection). */
    @WrapMethod(method = "updateChunkStatus")
    private Visibility vanadium$updateChunkStatus(Visibility status, Operation<Visibility> original) {
        if (!this.vanadium$tracked) return original.call(status);
        synchronized (LithiumCompat.MOVEMENT_TRACKING) {
            return original.call(status);
        }
    }
}
