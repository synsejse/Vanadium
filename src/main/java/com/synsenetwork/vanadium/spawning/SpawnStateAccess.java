package com.synsenetwork.vanadium.spawning;

/** Duck interface on {@code NaturalSpawner.SpawnState} to refresh lock-free counters after merging partial states. */
public interface SpawnStateAccess {
    void vanadium$syncCategoryCounts();
}
