package com.synsenetwork.vanadium.tracking;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;

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
        centers.put(object, ChunkPos.pack(chunkX, chunkZ));
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
        int chunkX = ChunkPos.getX(center);
        int chunkZ = ChunkPos.getZ(center);
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                erase(x, z, object);
            }
        }
    }

    /** Moves/resizes an object's square, touching only cells in the symmetric difference. */
    public void update(T object, int chunkX, int chunkZ, int radius) {
        int oldRadius = radii.replace(object, radius);
        long center = ChunkPos.pack(chunkX, chunkZ);
        long oldCenter = centers.put(object, center);
        int oldX = ChunkPos.getX(oldCenter);
        int oldZ = ChunkPos.getZ(oldCenter);
        if (oldX == chunkX && oldZ == chunkZ && oldRadius == radius) {
            return;
        }

        updateOutside(object, center, radius, oldCenter, oldRadius, true);
        updateOutside(object, oldCenter, oldRadius, center, radius, false);
    }

    /** Updates the four non-overlapping strips outside the excluded square. Centers are packed chunks. */
    private void updateOutside(T object, long center, int radius, long excludedCenter, int excludedRadius,
                               boolean add) {
        int x = ChunkPos.getX(center);
        int z = ChunkPos.getZ(center);
        int excludedX = ChunkPos.getX(excludedCenter);
        int excludedZ = ChunkPos.getZ(excludedCenter);
        int minX = x - radius, maxX = x + radius;
        int minZ = z - radius, maxZ = z + radius;
        int overlapMinX = Math.max(minX, excludedX - excludedRadius);
        int overlapMaxX = Math.min(maxX, excludedX + excludedRadius);
        int overlapMinZ = Math.max(minZ, excludedZ - excludedRadius);
        int overlapMaxZ = Math.min(maxZ, excludedZ + excludedRadius);
        if (overlapMinX > overlapMaxX || overlapMinZ > overlapMaxZ) {
            updateRectangle(object, minX, maxX, minZ, maxZ, add);
            return;
        }
        // Full-height left/right strips, then bottom/top restricted to the overlapping X range.
        updateRectangle(object, minX, overlapMinX - 1, minZ, maxZ, add);
        updateRectangle(object, overlapMaxX + 1, maxX, minZ, maxZ, add);
        updateRectangle(object, overlapMinX, overlapMaxX, minZ, overlapMinZ - 1, add);
        updateRectangle(object, overlapMinX, overlapMaxX, overlapMaxZ + 1, maxZ, add);
    }

    private void updateRectangle(T object, int minX, int maxX, int minZ, int maxZ, boolean add) {
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (add) {
                    paint(x, z, object);
                } else {
                    erase(x, z, object);
                }
            }
        }
    }

    private void paint(int x, int z, T object) {
        map.computeIfAbsent(ChunkPos.pack(x, z), k -> new ReferenceOpenHashSet<>()).add(object);
    }

    private void erase(int x, int z, T object) {
        long key = ChunkPos.pack(x, z);
        ReferenceOpenHashSet<T> set = map.get(key);
        if (set == null || !set.remove(object)) {
            throw new IllegalStateException("not painted at " + x + "," + z + ": " + object);
        }
        if (set.isEmpty()) {
            map.remove(key);
        }
    }
}
