package com.synsenetwork.vanadium.tracking;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.math.ChunkPos;

import java.util.Set;

/**
 * Reverse range index: each object is painted onto every chunk of a square of its own radius
 * around its center chunk, so "which objects' ranges cover chunk X" is a single map lookup.
 * Derived from the AreaMap in VMP-fabric (MIT, Copyright (c) ishland), itself modelled on
 * Paper's player area map; reimplemented without listeners, ordering, or pooling.
 *
 * <p>Not thread-safe: mutate under external serialization (Vanadium mutates on the server
 * thread or under the chunk-loading-manager monitor, never during a wave).
 */
public final class AreaMap<T> {
    private static final Set<?> EMPTY = new ReferenceOpenHashSet<>();

    private final Long2ObjectOpenHashMap<ReferenceOpenHashSet<T>> map = new Long2ObjectOpenHashMap<>();
    private final Reference2IntOpenHashMap<T> radii = new Reference2IntOpenHashMap<>();
    private final Reference2LongOpenHashMap<T> centers = new Reference2LongOpenHashMap<>();

    /** The objects whose painted square covers the given chunk. Never null; do not mutate. */
    @SuppressWarnings("unchecked")
    public Set<T> objectsAt(long chunkKey) {
        Set<T> set = map.get(chunkKey);
        return set != null ? set : (Set<T>) EMPTY;
    }

    public boolean contains(T object) {
        return radii.containsKey(object);
    }

    public void add(T object, int chunkX, int chunkZ, int radius) {
        if (radii.containsKey(object)) {
            throw new IllegalStateException("already added: " + object);
        }
        radii.put(object, radius);
        centers.put(object, ChunkPos.toLong(chunkX, chunkZ));
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                paint(x, z, object);
            }
        }
    }

    public void remove(T object) {
        if (!radii.containsKey(object)) {
            return;
        }
        int radius = radii.removeInt(object);
        long center = centers.removeLong(object);
        int chunkX = ChunkPos.getPackedX(center);
        int chunkZ = ChunkPos.getPackedZ(center);
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                erase(x, z, object);
            }
        }
    }

    /** Moves/resizes an object's square, touching only cells in the symmetric difference. */
    public void update(T object, int chunkX, int chunkZ, int radius) {
        int oldRadius = radii.replace(object, radius);
        long oldCenter = centers.put(object, ChunkPos.toLong(chunkX, chunkZ));
        int oldX = ChunkPos.getPackedX(oldCenter);
        int oldZ = ChunkPos.getPackedZ(oldCenter);
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                if (!within(x, z, oldX, oldZ, oldRadius)) {
                    paint(x, z, object);
                }
            }
        }
        for (int x = oldX - oldRadius; x <= oldX + oldRadius; x++) {
            for (int z = oldZ - oldRadius; z <= oldZ + oldRadius; z++) {
                if (!within(x, z, chunkX, chunkZ, radius)) {
                    erase(x, z, object);
                }
            }
        }
    }

    private static boolean within(int x, int z, int centerX, int centerZ, int radius) {
        return Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
    }

    private void paint(int x, int z, T object) {
        map.computeIfAbsent(ChunkPos.toLong(x, z), k -> new ReferenceOpenHashSet<>()).add(object);
    }

    private void erase(int x, int z, T object) {
        long key = ChunkPos.toLong(x, z);
        ReferenceOpenHashSet<T> set = map.get(key);
        if (set == null || !set.remove(object)) {
            throw new IllegalStateException("not painted at " + x + "," + z + ": " + object);
        }
        if (set.isEmpty()) {
            map.remove(key);
        }
    }
}
