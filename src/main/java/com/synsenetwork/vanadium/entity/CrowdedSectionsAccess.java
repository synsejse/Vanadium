package com.synsenetwork.vanadium.entity;

/** An entity section storage's {@link CrowdedSections}. */
public interface CrowdedSectionsAccess {
    CrowdedSections<?> vanadium$crowds();

    /** Server thread, after the level's ticks; see {@link CrowdedSections#refresh}. */
    void vanadium$refreshCrowds(boolean enabled);
}
