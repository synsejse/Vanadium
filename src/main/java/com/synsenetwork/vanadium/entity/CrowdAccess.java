package com.synsenetwork.vanadium.entity;

import org.jspecify.annotations.Nullable;

/** An entity's registration in its section's {@link CrowdedSection}, if that section is indexed. */
public interface CrowdAccess {
    CrowdedSection.@Nullable Member<?> vanadium$crowd();

    /** Only under the grid's monitor. */
    void vanadium$setCrowd(CrowdedSection.@Nullable Member<?> member);
}
