package com.synsenetwork.vanadium.tick

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap

/**
 * One stage's tasks for one wave, planned into regions that adapt to where the work is.
 *
 * Tasks are recorded per chunk in enqueue order. Chunks live in dense [TILE]×[TILE] tiles. Before the wave,
 * each active tile is split like a quadtree: a square splits into quadrants while it holds more than the
 * target number of tasks, so crowded areas become single-chunk regions and quiet ones stay merged, sparing
 * cheap stages per-region overhead. Square weights come from per-tile prefix sums, so planning a tile is
 * linear in its size. Each region is the bounding box of its chunks, and its tasks keep enqueue order.
 *
 * Safety: two regions conflict, and never run at the same time, when their boxes are within the isolation
 * distance of each other (Chebyshev, in chunks). Every chunk lies in its region's box, so any two chunks
 * within the isolation distance belong to conflicting regions. Boxes are disjoint, so each region stamps
 * its box into its tile, and scanning the ring around a box finds exactly the conflicting boxes; the
 * relation is symmetric, as [ClaimWave] and [DataflowWave] require.
 *
 * Server-thread only outside a wave; workers only run the planned regions.
 */
class RegionGrid {
    private class Tile(@JvmField val tileX: Int, @JvmField val tileZ: Int) {
        @JvmField val buckets = arrayOfNulls<Bucket>(TILE * TILE)

        /** 2D prefix sums of task counts over (TILE + 1)² entries; row and column 0 stay zero. */
        @JvmField val sums = IntArray((TILE + 1) * (TILE + 1))

        /** Region index + 1 owning each chunk through its box this wave, 0 for none. */
        @JvmField val owners = IntArray(TILE * TILE)

        /** Neighbouring tiles by (dx + 1) + 3 * (dz + 1), refreshed when building conflicts; null when absent. */
        @JvmField val around = arrayOfNulls<Tile>(9)
        @JvmField var activeWave = -1L
        @JvmField var lastActiveTick = 0L
        @JvmField var bucketCount = 0
    }

    private class Bucket {
        @JvmField var count = 0
        @JvmField var lastActiveTick = 0L

        /** The region of plan [planGeneration]; meaningless for any other plan. */
        @JvmField var region: Region? = null
        @JvmField var planGeneration = 0
    }

    private val tiles = Long2ObjectOpenHashMap<Tile>()
    private val activeTiles = ArrayList<Tile>()
    private val activeBuckets = ArrayList<Bucket>()
    private var logTasks = arrayOfNulls<Runnable>(INITIAL_CAPACITY)
    private var logOwners = arrayOfNulls<Bucket>(INITIAL_CAPACITY)
    private var logSize = 0
    private var tick = 0L
    private var wave = 0L

    private val pool = ArrayList<Region>()
    private val planned = ArrayList<Region>()
    private var targetTasks = 1

    // The current plan is reused while it still covers every active chunk; see plan().
    private var planGeneration = 0
    private var planParticipants = 0
    private var planIsolation = 0
    private var planTasks = 0
    private var planTarget = 0
    private var reuses = 0

    // Conflicts of region i are neighbors[neighborStart[i] until neighborStart[i + 1]].
    internal var neighborStart = IntArray(64)
        private set
    internal var neighbors = IntArray(256)
        private set

    /** Greedy colouring of the conflict graph in largest-first order; conflicting regions differ. */
    internal var colours = IntArray(64)
        private set
    private var stamps = IntArray(64)
    private var colourStamps = IntArray(64)

    /** Estimated cost of one task, from previous waves; sets the smallest region worth claiming. */
    private var nanosPerTask = 0.0

    val isEmpty: Boolean get() = logSize == 0

    val taskCount: Int get() = logSize

    internal val bucketCount: Int get() = tiles.values.sumOf { it.bucketCount }

    fun enqueue(chunkX: Int, chunkZ: Int, task: Runnable) {
        val tileX = chunkX shr TILE_SHIFT
        val tileZ = chunkZ shr TILE_SHIFT
        val key = pack(tileX, tileZ)
        // Plain get/put: a capturing computeIfAbsent lambda would allocate on every enqueue.
        val tile = tiles.get(key) ?: Tile(tileX, tileZ).also { tiles.put(key, it) }
        val slot = (chunkX and TILE_MASK) * TILE + (chunkZ and TILE_MASK)
        val bucket = tile.buckets[slot] ?: Bucket().also {
            tile.buckets[slot] = it
            tile.bucketCount++
        }
        if (bucket.count++ == 0) {
            activeBuckets.add(bucket)
            bucket.lastActiveTick = tick
            tile.lastActiveTick = tick
            if (tile.activeWave != wave) {
                tile.activeWave = wave
                activeTiles.add(tile)
            }
        }
        if (logSize == logTasks.size) {
            logTasks = logTasks.copyOf(logSize * 2)
            logOwners = logOwners.copyOf(logSize * 2)
        }
        logTasks[logSize] = task
        logOwners[logSize] = bucket
        logSize++
    }

    /**
     * Plans this wave's regions, largest first, with conflict lists for [isolation] and a colouring that
     * orders conflicting regions. The returned list and the arrays stay valid until the next [plan].
     *
     * Planning a crowded stage costs hundreds of microseconds on the server thread, mostly cache misses, so
     * the previous plan is kept while every active chunk still belongs to one of its regions, for up to
     * [MAX_REUSES] waves, while the task count stays within a quarter of the planned one and while the
     * region size target (which follows measured task cost) stays within a factor of two. Conflicts are
     * defined on region boxes, so a reused plan is exactly as safe as a fresh one; regions whose chunks went
     * quiet simply run empty.
     */
    fun plan(participants: Int, isolation: Int): List<Region> {
        if (logSize == 0) return emptyList()
        val clamped = isolation.coerceIn(1, MAX_ISOLATION)
        val target = targetTasks(participants)
        if (canReuse(participants, clamped, target)) {
            reuses++
        } else {
            replan(participants, clamped, target)
        }
        for (i in 0 until logSize) logOwners[i]!!.region!!.tasks.add(logTasks[i]!!)
        return planned
    }

    /** Tasks a region may hold before it splits: a share of the work per participant, at least the smallest worthwhile region. */
    private fun targetTasks(participants: Int): Int {
        val minimum = if (nanosPerTask > 0) Math.ceil(MIN_REGION_NANOS / nanosPerTask).toInt() else 1
        return maxOf(1, logSize / (participants * SPLIT_FACTOR), minimum)
    }

    private fun canReuse(participants: Int, isolation: Int, target: Int): Boolean {
        if (planned.isEmpty() || reuses >= MAX_REUSES || participants != planParticipants || isolation != planIsolation) return false
        if (Math.abs(logSize - planTasks) * 4 > planTasks) return false
        if (target > planTarget * 2 || target * 2 < planTarget) return false
        for (bucket in activeBuckets) if (bucket.planGeneration != planGeneration) return false
        return true
    }

    private fun replan(participants: Int, isolation: Int, target: Int) {
        planned.clear()
        planGeneration++
        reuses = 0
        planParticipants = participants
        planIsolation = isolation
        planTasks = logSize
        planTarget = target
        targetTasks = target
        for (tile in activeTiles) {
            prefixSums(tile)
            split(tile, 0, 0, TILE)
        }
        // Weights for ordering come from this wave's tasks: count them per region before sorting.
        for (region in planned) region.weight = 0
        for (bucket in activeBuckets) bucket.region!!.weight += bucket.count
        if (planned.size > 1) planned.sortWith(HEAVIEST_FIRST)
        buildConflicts(isolation)
        colour()
    }

    /** Estimated work of the queued tasks, or 0 before any wave was measured. */
    val estimatedNanos: Double get() = nanosPerTask * logSize

    /** Estimated work per region of the current plan, or 0 before any wave was measured. */
    val nanosPerRegion: Double get() = if (planned.isEmpty()) 0.0 else nanosPerTask * logSize / planned.size

    /** Feeds back the time spent running this wave's tasks so the next plan can size regions by cost. */
    fun recordWave(busyNanos: Long) {
        if (logSize == 0) return
        val sample = busyNanos.toDouble() / logSize
        nanosPerTask = if (nanosPerTask == 0.0) sample else nanosPerTask + (sample - nanosPerTask) * COST_SMOOTHING
    }

    /** Starts a new tick: evicts chunks and tiles idle for [IDLE_EVICT_TICKS] stage begins. */
    fun beginTick() {
        tick++
        endTick()
        val iterator = tiles.values.iterator()
        while (iterator.hasNext()) {
            val tile = iterator.next()
            if (tick - tile.lastActiveTick > IDLE_EVICT_TICKS) {
                iterator.remove()
                continue
            }
            for (slot in tile.buckets.indices) {
                val bucket = tile.buckets[slot] ?: continue
                if (tick - bucket.lastActiveTick > IDLE_EVICT_TICKS) {
                    tile.buckets[slot] = null
                    tile.bucketCount--
                }
            }
        }
    }

    /** Clears this wave's tasks; keeps the plan, tiles, buckets and scratch for reuse. */
    fun endTick() {
        for (bucket in activeBuckets) bucket.count = 0
        activeBuckets.clear()
        for (region in planned) region.tasks.clear()
        activeTiles.clear()
        wave++
        logTasks.fill(null, 0, logSize)
        logOwners.fill(null, 0, logSize)
        logSize = 0
    }

    private fun prefixSums(tile: Tile) {
        val sums = tile.sums
        val row = TILE + 1
        for (x in 0 until TILE) {
            var line = 0
            for (z in 0 until TILE) {
                val bucket = tile.buckets[x * TILE + z]
                if (bucket != null) line += bucket.count
                sums[(x + 1) * row + z + 1] = sums[x * row + z + 1] + line
            }
        }
    }

    /** Tasks in the tile-local square [x0, x0 + size) × [z0, z0 + size). */
    private fun weight(tile: Tile, x0: Int, z0: Int, size: Int): Int {
        val s = tile.sums
        val row = TILE + 1
        return s[(x0 + size) * row + z0 + size] - s[x0 * row + z0 + size] - s[(x0 + size) * row + z0] + s[x0 * row + z0]
    }

    private fun split(tile: Tile, x0: Int, z0: Int, size: Int) {
        val tasks = weight(tile, x0, z0, size)
        if (tasks == 0) return
        if (tasks <= targetTasks || size == 1) {
            makeRegion(tile, x0, z0, size)
            return
        }
        val half = size / 2
        split(tile, x0, z0, half)
        split(tile, x0 + half, z0, half)
        split(tile, x0, z0 + half, half)
        split(tile, x0 + half, z0 + half, half)
    }

    private fun makeRegion(tile: Tile, x0: Int, z0: Int, size: Int) {
        val region = if (planned.size < pool.size) pool[planned.size] else Region().also { pool.add(it) }
        var minX = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        for (x in x0 until x0 + size) for (z in z0 until z0 + size) {
            val bucket = tile.buckets[x * TILE + z] ?: continue
            if (bucket.count == 0) continue
            bucket.region = region
            bucket.planGeneration = planGeneration
            if (x < minX) minX = x
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (z > maxZ) maxZ = z
        }
        val baseX = tile.tileX shl TILE_SHIFT
        val baseZ = tile.tileZ shl TILE_SHIFT
        region.minX = baseX + minX
        region.minZ = baseZ + minZ
        region.maxX = baseX + maxX
        region.maxZ = baseZ + maxZ
        planned.add(region)
    }

    /** Writes [value] over the region's box in its tile's owner array. */
    private fun stampBox(region: Region, value: Int) {
        val tile = tiles.get(pack(region.minX shr TILE_SHIFT, region.minZ shr TILE_SHIFT)) ?: return
        val owners = tile.owners
        for (x in (region.minX and TILE_MASK)..(region.maxX and TILE_MASK)) {
            for (z in (region.minZ and TILE_MASK)..(region.maxZ and TILE_MASK)) owners[x * TILE + z] = value
        }
    }

    /**
     * Region i conflicts with every region whose box lies within [isolation] of its own: each box is stamped
     * into its tile, and the ring around box i is read through the tile and its eight neighbours.
     */
    private fun buildConflicts(isolation: Int) {
        val n = planned.size
        if (neighborStart.size < n + 1) neighborStart = IntArray((n + 1) * 2)
        if (stamps.size < n) stamps = IntArray(n * 2)
        stamps.fill(-1, 0, n)
        for (i in 0 until n) stampBox(planned[i], i + 1)
        for (tile in activeTiles) {
            for (dz in -1..1) for (dx in -1..1) {
                tile.around[(dx + 1) + 3 * (dz + 1)] =
                    if (dx == 0 && dz == 0) tile else tiles.get(pack(tile.tileX + dx, tile.tileZ + dz))
            }
        }
        var count = 0
        for (i in 0 until n) {
            neighborStart[i] = count
            val r = planned[i]
            val tile = tiles.get(pack(r.minX shr TILE_SHIFT, r.minZ shr TILE_SHIFT))!!
            // Tile-local box; the ring reaches at most MAX_ISOLATION into neighbouring tiles.
            val bx0 = r.minX and TILE_MASK
            val bz0 = r.minZ and TILE_MASK
            val bx1 = bx0 + (r.maxX - r.minX)
            val bz1 = bz0 + (r.maxZ - r.minZ)
            for (x in bx0 - isolation..bx1 + isolation) {
                val insideX = x in bx0..bx1
                val column = if (x < 0) 0 else if (x < TILE) 1 else 2
                val lx = x - (column - 1) * TILE
                var z = bz0 - isolation
                while (z <= bz1 + isolation) {
                    if (insideX && z == bz0) {
                        z = bz1 + 1 // Inside the box: only this region.
                        continue
                    }
                    val row = if (z < 0) 0 else if (z < TILE) 1 else 2
                    val other = tile.around[column + 3 * row]
                    if (other != null && other.activeWave == wave) {
                        val owner = other.owners[lx * TILE + (z - (row - 1) * TILE)] - 1
                        if (owner >= 0 && stamps[owner] != i) {
                            stamps[owner] = i
                            if (count == neighbors.size) neighbors = neighbors.copyOf(count * 2)
                            neighbors[count++] = owner
                        }
                    }
                    z++
                }
            }
        }
        neighborStart[n] = count
        for (i in 0 until n) stampBox(planned[i], 0)
    }

    /** Smallest colour unused by already-coloured conflicts, visiting regions largest first. */
    private fun colour() {
        val n = planned.size
        if (colours.size < n) colours = IntArray(n * 2)
        colours.fill(-1, 0, n)
        for (i in 0 until n) {
            val degree = neighborStart[i + 1] - neighborStart[i]
            if (colourStamps.size < degree + 1) colourStamps = IntArray((degree + 1) * 2)
            for (k in neighborStart[i] until neighborStart[i + 1]) {
                val c = colours[neighbors[k]]
                if (c in 0..degree) colourStamps[c] = i + 1
            }
            var c = 0
            while (colourStamps[c] == i + 1) c++
            colours[i] = c
        }
        colourStamps.fill(0)
    }

    companion object {
        /** Side of a tile and of the largest region, in chunks. */
        internal const val TILE = 16
        private const val TILE_SHIFT = 4
        private const val TILE_MASK = TILE - 1

        /** Largest supported isolation: a conflict ring then never reaches past adjacent tiles. */
        const val MAX_ISOLATION = TILE

        /** Regions per participant the split aims for when work is heavy enough to pay for them. */
        internal const val SPLIT_FACTOR = 4

        /** Smallest region worth claiming, as estimated work. */
        private const val MIN_REGION_NANOS = 25_000.0
        private const val COST_SMOOTHING = 0.2

        /** Waves a plan may be reused before it is rebuilt from the current load. */
        internal const val MAX_REUSES = 20
        internal const val IDLE_EVICT_TICKS = 100
        private const val INITIAL_CAPACITY = 256

        private val HEAVIEST_FIRST = Comparator<Region> { a, b -> Integer.compare(b.weight, a.weight) }

        private fun pack(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    }
}
