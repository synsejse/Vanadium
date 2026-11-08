package com.synsenetwork.vanadium.concurrent;

/** Exposes the POI distance tracker's pending-work flag and stored level to {@code PoiManager}. */
public interface PoiDistanceAccess {
    boolean vanadium$hasPendingUpdates();

    int vanadium$storedLevel(long sectionPos);
}
