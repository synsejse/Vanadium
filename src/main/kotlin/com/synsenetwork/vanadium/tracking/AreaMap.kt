package com.synsenetwork.vanadium.tracking

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import net.minecraft.world.level.ChunkPos

/**
 * Reverse range index: each object is painted onto every chunk of a square of its own radius around its
 * center chunk, so "which objects' ranges cover chunk X" is a single map lookup. Derived from the AreaMap
 * in VMP-fabric (MIT, Copyright (c) ishland), itself modelled on Paper's player area map.
 *
 * Not thread-safe: callers serialize mutation (the server thread, or the owning index's monitor).
 */
class AreaMap<T : Any> {
    private class Square(val x: Int, val z: Int, val radius: Int)

    private val map = Long2ObjectOpenHashMap<ReferenceOpenHashSet<T>>()
    private val squares = Reference2ObjectOpenHashMap<T, Square>()

    /** The objects whose painted square covers the given chunk. Never null; do not mutate. */
    fun objectsAt(chunkKey: Long): Set<T> = map.get(chunkKey) ?: emptySet()

    operator fun contains(obj: T): Boolean = squares.containsKey(obj)

    fun add(obj: T, chunkX: Int, chunkZ: Int, radius: Int) {
        check(squares.putIfAbsent(obj, Square(chunkX, chunkZ, radius)) == null) { "already added: $obj" }
        paintRectangle(obj, chunkX - radius, chunkX + radius, chunkZ - radius, chunkZ + radius, add = true)
    }

    fun remove(obj: T) {
        val square = squares.remove(obj) ?: return
        paintRectangle(obj, square.x - square.radius, square.x + square.radius,
            square.z - square.radius, square.z + square.radius, add = false)
    }

    /** Moves/resizes an object's square, touching only cells in the symmetric difference. */
    fun update(obj: T, chunkX: Int, chunkZ: Int, radius: Int) {
        val old = squares.put(obj, Square(chunkX, chunkZ, radius)) ?: error("not added: $obj")
        if (old.x == chunkX && old.z == chunkZ && old.radius == radius) return
        paintOutside(obj, chunkX, chunkZ, radius, old, add = true)
        paintOutside(obj, old.x, old.z, old.radius, Square(chunkX, chunkZ, radius), add = false)
    }

    /** Paints or erases the up-to-four strips of a square that lie outside [excluded]. */
    private fun paintOutside(obj: T, x: Int, z: Int, radius: Int, excluded: Square, add: Boolean) {
        val minX = x - radius
        val maxX = x + radius
        val minZ = z - radius
        val maxZ = z + radius
        val overlapMinX = maxOf(minX, excluded.x - excluded.radius)
        val overlapMaxX = minOf(maxX, excluded.x + excluded.radius)
        val overlapMinZ = maxOf(minZ, excluded.z - excluded.radius)
        val overlapMaxZ = minOf(maxZ, excluded.z + excluded.radius)
        if (overlapMinX > overlapMaxX || overlapMinZ > overlapMaxZ) {
            paintRectangle(obj, minX, maxX, minZ, maxZ, add)
            return
        }
        // Full-height left/right strips, then bottom/top restricted to the overlapping X range.
        paintRectangle(obj, minX, overlapMinX - 1, minZ, maxZ, add)
        paintRectangle(obj, overlapMaxX + 1, maxX, minZ, maxZ, add)
        paintRectangle(obj, overlapMinX, overlapMaxX, minZ, overlapMinZ - 1, add)
        paintRectangle(obj, overlapMinX, overlapMaxX, overlapMaxZ + 1, maxZ, add)
    }

    private fun paintRectangle(obj: T, minX: Int, maxX: Int, minZ: Int, maxZ: Int, add: Boolean) {
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val key = ChunkPos.pack(x, z)
                if (add) {
                    map.computeIfAbsent(key) { ReferenceOpenHashSet() }.add(obj)
                } else {
                    val set = map.get(key)
                    check(set != null && set.remove(obj)) { "not painted at $x,$z: $obj" }
                    if (set.isEmpty()) map.remove(key)
                }
            }
        }
    }
}
