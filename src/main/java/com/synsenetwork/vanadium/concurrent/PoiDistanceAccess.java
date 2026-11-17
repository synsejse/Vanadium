package com.synsenetwork.vanadium.concurrent;

/** Exposes the POI distance tracker's pending-work flag, stored level and source level to {@code PoiManager}. */
public interface PoiDistanceAccess {
    boolean vanadium$hasPendingUpdates();

    int vanadium$storedLevel(long sectionPos);

    /** Whether the section is a village centre now (0) or not (7); takes no lock, see {@code PoiManagerMixin}. */
    int vanadium$levelFromSource(long sectionPos);
}
