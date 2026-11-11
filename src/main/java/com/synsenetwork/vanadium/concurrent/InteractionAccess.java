package com.synsenetwork.vanadium.concurrent;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.phys.Vec3;

/** Per-entity state for {@link Interactions}, implemented on {@code Entity} by {@code EntityMixin}. */
public interface InteractionAccess {
    /** Held by whoever may change this entity's vehicle tree during a flat entity wave. */
    OwnerLocks.Lock vanadium$tickLock();

    /** Pushes from other entities' ticks that this entity has not applied yet; null when empty. */
    AtomicReference<Vec3> vanadium$impulse();

    /** Consecutive flat-wave ticks of this entity that ended in an error. */
    int vanadium$failedTicks();

    void vanadium$setFailedTicks(int ticks);
}
