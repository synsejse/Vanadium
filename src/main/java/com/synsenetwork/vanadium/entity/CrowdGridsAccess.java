package com.synsenetwork.vanadium.entity;

/** An entity section storage's {@link CrowdGrids}. */
public interface CrowdGridsAccess {
    CrowdGrids<?> vanadium$crowds();

    /** Server thread, after the level's ticks; see {@link CrowdGrids#refresh}. */
    void vanadium$refreshCrowds(boolean enabled);
}
