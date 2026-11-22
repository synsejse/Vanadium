package com.synsenetwork.vanadium.navigation

import com.synsenetwork.vanadium.tick.TickThread
import java.util.Arrays
import net.caffeinemc.mods.lithium.common.ai.pathing.BlockStatePathingCache
import net.caffeinemc.mods.lithium.common.ai.pathing.PathNodeCache
import net.caffeinemc.mods.lithium.common.block.BlockStateFlagHolder
import net.fabricmc.fabric.api.registry.LandPathTypeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.shapes.Shapes

/**
 * Exact hot-path helpers for ground pathfinding.
 *
 * When Lithium's `ai.pathing` block-state cache is active, every block state already carries its precomputed raw and
 * neighbor path types. Reading them directly from [PathNavigationRegionAccess] avoids Fabric API's empty-registry
 * block re-read, cross-socket contention on the level-wide `PathTypeCache`, duplicate chunk/section lookups between
 * `(x, y, z)` and `(x, y - 1, z)`, and Lithium's 24-block neighbor fallback across chunk/section seams whenever all
 * overlapping sections are certified safe.
 */
object PathfindingFastPath {
    @JvmField
    val PATH_TYPES: Array<PathType> = PathType.entries.toTypedArray()

    @JvmField
    val HORIZONTAL_DIRECTIONS: Array<Direction> = arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

    @JvmField
    val FENCE_BIT: Long = 1L shl PathType.FENCE.ordinal

    @JvmField
    val UNPASSABLE_RAIL_BIT: Long = 1L shl PathType.UNPASSABLE_RAIL.ordinal

    private val lithiumPathingActive: Boolean =
        BlockStatePathingCache::class.java.isAssignableFrom(BlockBehaviour.BlockStateBase::class.java)

    private val lithiumBlockFlagsActive: Boolean =
        BlockStateFlagHolder::class.java.isAssignableFrom(BlockBehaviour.BlockStateBase::class.java)

    @JvmStatic
    fun ensureLithiumBlockFlagsInitialized() {
        if (lithiumBlockFlagsActive) {
            (Blocks.AIR.defaultBlockState() as BlockStateFlagHolder).`lithium$getAllFlags`()
        }
    }

    /**
     * Live reference to Fabric API's `LandPathTypeRegistry.PATH_TYPES` (`IdentityHashMap`). Checking `isEmpty()` avoids
     * an `IdentityHashMap.get` and redundant `getBlockState` when no mod has registered custom path-type providers.
     * Null if the field is unavailable, in which case providers are assumed present.
     */
    private val landPathTypesMap: Map<*, *>? = try {
        val field = LandPathTypeRegistry::class.java.getDeclaredField("PATH_TYPES")
        field.isAccessible = true
        field.get(null) as? Map<*, *>
    } catch (_: Throwable) {
        null
    }

    @JvmStatic
    fun hasCustomLandPathProviders(): Boolean {
        val map = landPathTypesMap ?: return true
        return map.isNotEmpty()
    }

    /**
     * Returns the precomputed raw [PathType] for `(x, y, z)` directly from the region when Lithium's block-state cache
     * covers the block and no Fabric `LandPathTypeRegistry` provider overrides it; otherwise returns `null` so the
     * caller falls back to Fabric/vanilla.
     */
    @JvmStatic
    fun fastPathTypeFromState(level: CollisionGetter, x: Int, y: Int, z: Int): PathType? {
        if (!lithiumPathingActive || level !is PathNavigationRegionAccess) return null
        val state = level.`vanadium$getBlockState`(x, y, z)
        if (hasCustomLandPathProviders() && LandPathTypeRegistry.getPathTypeProvider(state.block) != null) {
            return null
        }
        return (state as BlockStatePathingCache).`lithium$getPathNodeType`()
    }

    /**
     * Allocation-free equivalent of `WalkNodeEvaluator.getPathTypeStatic(context, mutablePos)`.
     * Looks up the chunk and section once when `(x, y, z)` and `(x, y - 1, z)` share a section (15 of 16 Y levels)
     * and passes that section straight into the neighbor hazard check.
     */
    @JvmStatic
    fun getPathTypeStatic(context: PathfindingContext, x: Int, y: Int, z: Int): PathType {
        val level = context.level()
        if (lithiumPathingActive && !hasCustomLandPathProviders() && level is PathNavigationRegionAccess) {
            val minY = level.`vanadium$minY`()
            val maxY = level.`vanadium$maxY`()
            val chunk = level.`vanadium$getChunkOrNull`(x shr 4, z shr 4)
            if (chunk == null) {
                return PathType.OPEN
            }
            val sections = chunk.sections
            val minSectionY = level.`vanadium$minSectionY`()
            val lx = x and 15
            val ly = y and 15
            val lz = z and 15
            val sy = (y shr 4) - minSectionY
            val section: LevelChunkSection? = if (y in minY..maxY && sy >= 0 && sy < sections.size) sections[sy] else null

            val type = if (section == null || section.hasOnlyAir()) {
                PathType.OPEN
            } else {
                val state = section.getBlockState(lx, ly, lz)
                (state as BlockStatePathingCache).`lithium$getPathNodeType`() ?: context.getPathTypeFromState(x, y, z)
            }
            if (type != PathType.OPEN || y < minY + 1) {
                return type
            }

            val belowY = y - 1
            val belowSection: LevelChunkSection? = if (ly > 0) {
                section
            } else {
                val belowSy = sy - 1
                if (belowY in minY..maxY && belowSy >= 0 && belowSy < sections.size) sections[belowSy] else null
            }
            val below = if (belowSection == null || belowSection.hasOnlyAir()) {
                PathType.OPEN
            } else {
                val belowState = belowSection.getBlockState(lx, belowY and 15, lz)
                (belowState as BlockStatePathingCache).`lithium$getPathNodeType`() ?: context.getPathTypeFromState(x, belowY, z)
            }
            val mapped = mapBelowPathType(below)
            if (mapped != null) return mapped
            return checkNeighbourBlocksRegion(context, level, section, minSectionY, x, y, z, lx, ly, lz, PathType.WALKABLE)
        }

        val type = fastPathTypeFromState(level, x, y, z) ?: context.getPathTypeFromState(x, y, z)
        if (type == PathType.OPEN && y >= level.minY + 1) {
            val below = fastPathTypeFromState(level, x, y - 1, z) ?: context.getPathTypeFromState(x, y - 1, z)
            val mapped = mapBelowPathType(below)
            if (mapped != null) return mapped
            return WalkNodeEvaluator.checkNeighbourBlocks(context, x, y, z, PathType.WALKABLE)
        }
        return type
    }

    private fun mapBelowPathType(below: PathType): PathType? = when (below) {
        PathType.OPEN, PathType.WATER, PathType.LAVA, PathType.WALKABLE -> PathType.OPEN
        PathType.FIRE -> PathType.FIRE
        PathType.DAMAGING -> PathType.DAMAGING
        PathType.STICKY_HONEY -> PathType.STICKY_HONEY
        PathType.POWDER_SNOW -> PathType.ON_TOP_OF_POWDER_SNOW
        PathType.DAMAGE_CAUTIOUS -> PathType.DAMAGE_CAUTIOUS
        PathType.TRAPDOOR -> PathType.ON_TOP_OF_TRAPDOOR
        else -> null
    }

    private fun checkNeighbourBlocksRegion(
        context: PathfindingContext,
        level: PathNavigationRegionAccess,
        centerSection: LevelChunkSection?,
        minSectionY: Int,
        x: Int,
        y: Int,
        z: Int,
        lx: Int,
        ly: Int,
        lz: Int,
        defaultType: PathType,
    ): PathType {
        if (lx in 1..14 && ly in 1..14 && lz in 1..14) {
            if (centerSection == null || PathNodeCache.isSectionSafeAsNeighbor(centerSection)) return defaultType
            for (dx in -1..1) {
                val nlx = lx + dx
                val xx = x + dx
                for (dy in -1..1) {
                    val nly = ly + dy
                    val yy = y + dy
                    for (dz in -1..1) {
                        if (dx == 0 && dz == 0) continue
                        val state = centerSection.getBlockState(nlx, nly, lz + dz)
                        if (state.isAir) continue
                        var neighborType = PathNodeCache.getNeighborPathNodeType(state)
                        if (neighborType == null) {
                            neighborType = evalSingleNeighborHazard(context, xx, yy, z + dz)
                        }
                        if (neighborType != PathType.OPEN) return neighborType
                    }
                }
            }
            return defaultType
        }

        val minCx = (x - 1) shr 4
        val maxCx = (x + 1) shr 4
        val minCz = (z - 1) shr 4
        val maxCz = (z + 1) shr 4
        val minSy = ((y - 1) shr 4) - minSectionY
        val maxSy = ((y + 1) shr 4) - minSectionY
        var allSafe = true
        outer@ for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                val chunk = level.`vanadium$getChunkOrNull`(cx, cz) ?: continue
                val sections = chunk.sections
                for (sy in minSy..maxSy) {
                    if (sy < 0 || sy >= sections.size) continue
                    val section = sections[sy]
                    if (!PathNodeCache.isSectionSafeAsNeighbor(section)) {
                        allSafe = false
                        break@outer
                    }
                }
            }
        }
        if (allSafe) return defaultType

        for (dx in -1..1) {
            val xx = x + dx
            for (dy in -1..1) {
                val yy = y + dy
                for (dz in -1..1) {
                    if (dx == 0 && dz == 0) continue
                    val zz = z + dz
                    val state = level.`vanadium$getBlockState`(xx, yy, zz)
                    if (state.isAir) continue
                    var neighborType = PathNodeCache.getNeighborPathNodeType(state)
                    if (neighborType == null) {
                        neighborType = evalSingleNeighborHazard(context, xx, yy, zz)
                    }
                    if (neighborType != PathType.OPEN) return neighborType
                }
            }
        }
        return defaultType
    }

    private fun evalSingleNeighborHazard(context: PathfindingContext, x: Int, y: Int, z: Int): PathType {
        return when (context.getPathTypeFromState(x, y, z)) {
            PathType.DAMAGING -> PathType.DAMAGING_IN_NEIGHBOR
            PathType.FIRE, PathType.LAVA -> PathType.FIRE_IN_NEIGHBOR
            PathType.WATER -> PathType.WATER_BORDER
            PathType.DAMAGE_CAUTIOUS -> PathType.DAMAGE_CAUTIOUS
            else -> PathType.OPEN
        }
    }

    /** Allocation-free equivalent of `WalkNodeEvaluator.getFloorLevel(BlockPos)`. */
    @JvmStatic
    fun computeFloorLevel(
        level: BlockGetter,
        x: Int,
        y: Int,
        z: Int,
        canFloatOrAmphibious: Boolean,
        mutablePos: BlockPos.MutableBlockPos,
    ): Double {
        val region = level as? PathNavigationRegionAccess
        val belowY = y - 1
        val belowState: BlockState
        if (region != null) {
            val minY = region.`vanadium$minY`()
            val maxY = region.`vanadium$maxY`()
            val chunk = region.`vanadium$getChunkOrNull`(x shr 4, z shr 4)
            if (chunk == null) {
                return belowY.toDouble()
            }
            val sections = chunk.sections
            val minSectionY = region.`vanadium$minSectionY`()
            val lx = x and 15
            val ly = y and 15
            val lz = z and 15
            val sy = (y shr 4) - minSectionY
            val section: LevelChunkSection? = if (y in minY..maxY && sy >= 0 && sy < sections.size) sections[sy] else null
            if (canFloatOrAmphibious && section != null && !section.hasOnlyAir()) {
                val fluid = section.getBlockState(lx, ly, lz).fluidState
                if (!fluid.isEmpty && fluid.`is`(FluidTags.ENTITY_FLOATABLE)) {
                    return y + 0.5
                }
            }
            val belowSection: LevelChunkSection? = if (ly > 0) {
                section
            } else {
                val belowSy = sy - 1
                if (belowY in minY..maxY && belowSy >= 0 && belowSy < sections.size) sections[belowSy] else null
            }
            belowState = if (belowSection == null || belowSection.hasOnlyAir()) {
                Blocks.AIR.defaultBlockState()
            } else {
                belowSection.getBlockState(lx, belowY and 15, lz)
            }
        } else {
            if (canFloatOrAmphibious) {
                val fluid = level.getFluidState(mutablePos.set(x, y, z))
                if (!fluid.isEmpty && fluid.`is`(FluidTags.ENTITY_FLOATABLE)) {
                    return y + 0.5
                }
            }
            belowState = level.getBlockState(mutablePos.set(x, belowY, z))
        }
        if (belowState.isAir) return belowY.toDouble()
        val shape = belowState.getCollisionShape(level, mutablePos.set(x, belowY, z))
        if (shape === Shapes.block()) return y.toDouble()
        return belowY + if (shape.isEmpty) 0.0 else shape.max(Direction.Axis.Y)
    }

    private val threadScratch = ThreadLocal.withInitial(::WalkScratch)

    /**
     * Borrows this thread's reusable L1-resident scratch tables (spatial caches + node table/pool) for one search,
     * bumping their generation counter in O(1). Pool workers read directly from [TickThread.walkScratch] without a
     * `ThreadLocal` lookup. Reentrant searches on the same thread get a fresh unpooled instance so nested calls never
     * corrupt an outer search.
     */
    @JvmStatic
    fun borrowWalkScratch(): WalkScratch {
        val thread = Thread.currentThread()
        val local: WalkScratch = if (thread is TickThread) {
            var existing = thread.walkScratch as WalkScratch?
            if (existing == null) {
                existing = WalkScratch()
                thread.walkScratch = existing
            }
            existing
        } else {
            threadScratch.get()
        }
        if (!local.inUse) {
            local.inUse = true
            local.bumpGen()
            return local
        }
        return WalkScratch().also { it.gen = 1 }
    }

    @JvmStatic
    fun releaseWalkScratch(scratch: WalkScratch?) {
        if (scratch != null && scratch.inUse) {
            scratch.inUse = false
        }
    }

    /**
     * Per-thread reusable generation-stamped spatial caches, primitive open-addressed `Node` table, and `Node` pool.
     * Padded around hot mutable scalars so G1 evacuation cannot place two workers' counters on the same 64-byte line.
     */
    class WalkScratch {
        @Suppress("unused") private var pad0 = 0L; @Suppress("unused") private var pad1 = 0L
        @Suppress("unused") private var pad2 = 0L; @Suppress("unused") private var pad3 = 0L
        @Suppress("unused") private var pad4 = 0L; @Suppress("unused") private var pad5 = 0L
        @Suppress("unused") private var pad6 = 0L; @Suppress("unused") private var pad7 = 0L

        @JvmField var inUse: Boolean = false
        @JvmField var gen: Int = 0
        @JvmField var nodeCount: Int = 0
        @JvmField var poolUsed: Int = 0

        @JvmField val floorGen: IntArray = IntArray(FLOOR_CACHE_SIZE)
        @JvmField val floorKey: LongArray = LongArray(FLOOR_CACHE_SIZE)
        @JvmField val floorVal: DoubleArray = DoubleArray(FLOOR_CACHE_SIZE)
        @JvmField val typeGen: IntArray = IntArray(TYPE_CACHE_SIZE)
        @JvmField val typeKey: LongArray = LongArray(TYPE_CACHE_SIZE)
        @JvmField val typeVal: Array<PathType?> = arrayOfNulls(TYPE_CACHE_SIZE)

        @JvmField var nodeGen: IntArray = IntArray(NODE_TABLE_INITIAL)
        @JvmField var nodeKey: IntArray = IntArray(NODE_TABLE_INITIAL)
        @JvmField var nodeVal: Array<Node?> = arrayOfNulls(NODE_TABLE_INITIAL)
        @JvmField var nodeMask: Int = NODE_TABLE_INITIAL - 1
        @JvmField var nodeMaxLoad: Int = NODE_TABLE_INITIAL ushr 1
        @JvmField var nodePool: Array<Node?> = arrayOfNulls(NODE_POOL_INITIAL)

        @Suppress("unused") private var pad8 = 0L; @Suppress("unused") private var pad9 = 0L
        @Suppress("unused") private var pad10 = 0L; @Suppress("unused") private var pad11 = 0L
        @Suppress("unused") private var pad12 = 0L; @Suppress("unused") private var pad13 = 0L
        @Suppress("unused") private var pad14 = 0L; @Suppress("unused") private var pad15 = 0L

        fun bumpGen() {
            var next = gen + 1
            if (next == 0) {
                Arrays.fill(floorGen, 0)
                Arrays.fill(typeGen, 0)
                Arrays.fill(nodeGen, 0)
                next = 1
            }
            gen = next
            nodeCount = 0
            poolUsed = 0
        }

        /**
         * Allocation-free equivalent of `NodeEvaluator.getNode(x, y, z)`: looks up `Node.createHash(x, y, z)` in the
         * generation-stamped open-addressed table and, on a miss, reuses a reset `Node` from [nodePool].
         */
        fun getOrCreateNode(x: Int, y: Int, z: Int): Node {
            val hash = Node.createHash(x, y, z)
            val g = gen
            var mask = nodeMask
            var gens = nodeGen
            var keys = nodeKey
            var vals = nodeVal
            var slot = mixInt(hash) and mask
            while (true) {
                if (gens[slot] != g) {
                    if (nodeCount >= nodeMaxLoad) {
                        growNodeTable()
                        mask = nodeMask
                        gens = nodeGen
                        keys = nodeKey
                        vals = nodeVal
                        slot = mixInt(hash) and mask
                        while (gens[slot] == g) {
                            slot = (slot + 1) and mask
                        }
                    }
                    val created = acquireNode(x, y, z, hash)
                    gens[slot] = g
                    keys[slot] = hash
                    vals[slot] = created
                    nodeCount++
                    return created
                }
                if (keys[slot] == hash) {
                    return vals[slot]!!
                }
                slot = (slot + 1) and mask
            }
        }

        private fun acquireNode(x: Int, y: Int, z: Int, hash: Int): Node {
            val idx = poolUsed++
            var pool = nodePool
            if (idx < pool.size) {
                val existing = pool[idx]
                if (existing is NodeAccess) {
                    existing.`vanadium$reset`(x, y, z, hash)
                    return existing
                }
                val created = Node(x, y, z)
                pool[idx] = created
                return created
            }
            if (idx < NODE_POOL_MAX) {
                val newSize = minOf(pool.size shl 1, NODE_POOL_MAX)
                pool = pool.copyOf(newSize)
                nodePool = pool
                val created = Node(x, y, z)
                pool[idx] = created
                return created
            }
            return Node(x, y, z)
        }

        private fun growNodeTable() {
            val oldGens = nodeGen
            val oldKeys = nodeKey
            val oldVals = nodeVal
            val oldCap = oldGens.size
            val newCap = oldCap shl 1
            val newMask = newCap - 1
            val newGens = IntArray(newCap)
            val newKeys = IntArray(newCap)
            val newVals = arrayOfNulls<Node>(newCap)
            val g = gen
            for (i in 0 until oldCap) {
                if (oldGens[i] != g) continue
                val key = oldKeys[i]
                var slot = mixInt(key) and newMask
                while (newGens[slot] == g) {
                    slot = (slot + 1) and newMask
                }
                newGens[slot] = g
                newKeys[slot] = key
                newVals[slot] = oldVals[i]
            }
            nodeGen = newGens
            nodeKey = newKeys
            nodeVal = newVals
            nodeMask = newMask
            nodeMaxLoad = newCap ushr 1
        }

        companion object {
            const val FLOOR_CACHE_SIZE = 1024
            const val FLOOR_CACHE_MASK = FLOOR_CACHE_SIZE - 1
            const val TYPE_CACHE_SIZE = 2048
            const val TYPE_CACHE_MASK = TYPE_CACHE_SIZE - 1
            const val NODE_TABLE_INITIAL = 2048
            const val NODE_POOL_INITIAL = 1024
            const val NODE_POOL_MAX = 4096

            private fun mixInt(x: Int): Int {
                val h = x * -1640531527
                return h xor (h ushr 16)
            }
        }
    }
}
