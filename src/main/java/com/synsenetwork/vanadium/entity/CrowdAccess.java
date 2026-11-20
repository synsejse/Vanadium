package com.synsenetwork.vanadium.entity;

import org.jspecify.annotations.Nullable;

/** An entity's registration in its section's {@link CrowdGrid}, if that section is indexed. */
public interface CrowdAccess {
    CrowdGrid.@Nullable Member<?> vanadium$crowd();

    /** Only under the grid's monitor. */
    void vanadium$setCrowd(CrowdGrid.@Nullable Member<?> member);
}
