package com.synsenetwork.vanadium.entity;

import net.minecraft.world.level.entity.EntityAccess;
import org.jspecify.annotations.Nullable;

/** An entity section's {@link CrowdSlot}; set when its storage creates it, before it is published. */
public interface CrowdSlotAccess<T extends EntityAccess> {
    @Nullable CrowdSlot<T> vanadium$crowdSlot();

    void vanadium$setCrowdSlot(CrowdSlot<T> slot);
}
