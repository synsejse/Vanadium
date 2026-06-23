package com.synsenetwork.vanadium.concurrent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which threads belong to named Minecraft worker pools, so chunk-load dispatch can
 * tell whether it is currently running on one of them. Extracted from the old region model;
 * behaviour is unchanged.
 */
public final class McThreadTracker {
    private static final Map<String, Set<Thread>> POOLS = new ConcurrentHashMap<>();

    private McThreadTracker() {
    }

    public static void register(String poolName, Thread thread) {
        POOLS.computeIfAbsent(poolName, key -> ConcurrentHashMap.newKeySet()).add(thread);
    }

    public static boolean isPooled(String poolName, Thread thread) {
        Set<Thread> threads = POOLS.get(poolName);
        return threads != null && threads.contains(thread);
    }
}
