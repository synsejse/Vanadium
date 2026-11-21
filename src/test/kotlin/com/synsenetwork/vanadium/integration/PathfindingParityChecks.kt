package com.synsenetwork.vanadium.integration

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap
import java.util.Comparator
import java.util.EnumSet
import java.util.HashMap
import java.util.HashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.pathfinder.BinaryHeap
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.NodeEvaluator
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.level.pathfinder.Target
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Differential parity checks for Vanadium's exact pathfinding fast paths.
 *
 * Builds an obstacle course straddling negative/positive chunk boundaries (`x = -1..0`, `z = -1..0`) and section Y
 * seams (`y = 79..80`) with doors, fences, rails, slabs, stairs, trapdoors, honey, water, lava, cactus, fire, gaps
 * and sealed barriers, then compares Vanadium+Lithium's live `PathFinder` + `WalkNodeEvaluator` against an
 * unmodified vanilla reference implementation across adult villagers (1×2×1), baby villagers (1×1×1) and iron golems
 * (2×3×2) for reached, partial, unreachable, single-target and multi-target queries.
 */
internal object PathfindingParityChecks {
    fun run(level: ServerLevel) {
        // Place the arena straddling chunk seams (x = 4095|4096, z = -1|0) and a vertical section seam (y = 79|80).
        val ox = 4096
        val oy = 80
        val oz = 0
        buildArena(level, ox, oy, oz)

        val adultVillager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND)!!
        adultVillager.setPos(ox - 10.5, oy.toDouble(), oz - 10.5)
        adultVillager.setOnGround(true)
        adultVillager.navigation.setCanOpenDoors(true)
        adultVillager.navigation.setCanFloat(true)

        val babyVillager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND)!!
        babyVillager.isBaby = true
        babyVillager.setPos(ox - 10.5, oy.toDouble(), oz - 8.5)
        babyVillager.setOnGround(true)
        babyVillager.navigation.setCanOpenDoors(true)
        babyVillager.navigation.setCanFloat(true)

        val golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND)!!
        golem.setPos(ox - 9.5, oy.toDouble(), oz - 6.5)
        golem.setOnGround(true)

        // Ensure chunk-seam and section-seam path types match vanilla at every single block in the arena.
        val region = PathNavigationRegion(level, BlockPos(ox - 32, oy - 16, oz - 32), BlockPos(ox + 32, oy + 16, oz + 32))
        val context = PathfindingContext(region, adultVillager)
        val liveEval = WalkNodeEvaluator()
        liveEval.setCanPassDoors(true)
        liveEval.setCanOpenDoors(true)
        liveEval.setCanFloat(true)
        liveEval.prepare(region, adultVillager)
        var typeChecks = 0
        for (x in (ox - 16)..(ox + 16)) {
            for (y in (oy - 2)..(oy + 3)) {
                for (z in (oz - 16)..(oz + 16)) {
                    val expected = VanillaOracle.getPathTypeStatic(region, x, y, z)
                    val actual = liveEval.getPathType(context, x, y, z)
                    check(actual == expected) {
                        "PathType mismatch at ($x, $y, $z): expected=$expected actual=$actual"
                    }
                    typeChecks++
                }
            }
        }
        liveEval.done()

        // Full A* path parity across mob sizes, single/multi targets, reached/partial/budget-exhausted cases.
        val cases = listOf(
            // Reached across chunk seam + doors + stairs + slabs + hazards.
            Query("adult-cross-seam", adultVillager, BlockPos(ox - 10, oy, oz - 10), setOf(BlockPos(ox + 10, oy, oz + 10)), 48f, 0, 768),
            // Reached across open/closed doors and fence detour.
            Query("adult-door-detour", adultVillager, BlockPos(ox - 6, oy, oz - 1), setOf(BlockPos(ox + 6, oy, oz + 1)), 48f, 0, 768),
            // Near water/lava/cactus/fire hazards right across a chunk boundary (x = ox - 1 | ox).
            Query("adult-hazard-seam", adultVillager, BlockPos(ox - 4, oy, oz + 4), setOf(BlockPos(ox + 4, oy, oz + 4)), 48f, 0, 768),
            // Sealed barrier target (exhausted budget / partial path — same shape as brawl600 tail!).
            Query("adult-sealed-partial", adultVillager, BlockPos(ox - 10, oy, oz - 10), setOf(BlockPos(ox + 10, oy, oz - 10)), 48f, 0, 768),
            // Multi-target POI query with symmetric ties.
            Query(
                "adult-multi-target",
                adultVillager,
                BlockPos(ox - 2, oy, oz - 2),
                setOf(BlockPos(ox + 6, oy, oz - 2), BlockPos(ox - 2, oy, oz + 6), BlockPos(ox + 8, oy, oz + 8)),
                48f,
                1,
                768,
            ),
            // Multi-target all unreachable (partial ranking tie-breaking).
            Query(
                "adult-multi-unreachable",
                adultVillager,
                BlockPos(ox - 10, oy, oz - 10),
                setOf(BlockPos(ox + 10, oy, oz - 10), BlockPos(ox + 10, oy, oz - 8)),
                48f,
                1,
                128,
            ),
            // Baby villager (1x1x1 fast path).
            Query("baby-cross-seam", babyVillager, BlockPos(ox - 10, oy, oz - 8), setOf(BlockPos(ox + 10, oy, oz + 10)), 48f, 0, 768),
            // Iron golem (2x3x2 large-mob bitmask path + BIG_MOBS_CLOSE_TO_DANGER).
            Query("golem-large-mob", golem, BlockPos(ox - 9, oy, oz - 6), setOf(BlockPos(ox + 8, oy, oz + 8)), 32f, 0, 512),
            // Step-up onto slab/stairs and down a drop.
            Query("adult-vertical", adultVillager, BlockPos(ox - 8, oy, oz + 8), setOf(BlockPos(ox + 8, oy + 1, oz + 8)), 48f, 0, 768),
            // Rail and honey obstacle.
            Query("adult-rail-honey", adultVillager, BlockPos(ox - 8, oy, oz - 4), setOf(BlockPos(ox + 8, oy, oz - 4)), 48f, 0, 768),
        )

        val savedPaths = ArrayList<Pair<Query, Path?>>(cases.size)
        val expectedPaths = ArrayList<Path?>(cases.size)
        for (q in cases) {
            q.mob.setPos(q.start.x + 0.5, q.start.y.toDouble(), q.start.z + 0.5)
            q.mob.setOnGround(true)
            val r = PathNavigationRegion(level, q.start.offset(-56, -56, -56), q.start.offset(56, 56, 56))
            val eval = WalkNodeEvaluator()
            eval.setCanPassDoors(true)
            eval.setCanOpenDoors(q.mob !== golem)
            eval.setCanFloat(q.mob !== golem)
            val finder = PathFinder(eval, q.budget)
            val actual = finder.findPath(r, q.mob, q.targets, q.maxPathLength, q.reachRange, 1.0f)
            val expected = VanillaOracle.findPath(r, q.mob, q.targets, q.maxPathLength, q.reachRange, q.budget, true, q.mob !== golem, q.mob !== golem)
            assertPathsEqual(q.name, expected, actual)
            savedPaths.add(q to actual)
            expectedPaths.add(expected)
        }
        // Recheck every earlier returned Path AFTER later searches reused the same thread's node pool.
        for (i in savedPaths.indices) {
            assertPathsEqual("${savedPaths[i].first.name}-post-reuse", expectedPaths[i], savedPaths[i].second)
        }

        // Also check debug capture works cleanly on multi-target and single-target.
        adultVillager.setPos(ox - 10.5, oy.toDouble(), oz - 10.5)
        val debugEval = WalkNodeEvaluator()
        debugEval.setCanPassDoors(true)
        debugEval.setCanOpenDoors(true)
        debugEval.setCanFloat(true)
        val debugFinder = PathFinder(debugEval, 256)
        debugFinder.setCaptureDebug { true }
        val debugPath = debugFinder.findPath(region, adultVillager, setOf(BlockPos(ox + 4, oy, oz + 4)), 48f, 0, 1.0f)
        check(debugPath != null && debugPath.debugData() != null) { "debug path missing debug data" }

        val flatChunk = level.getChunk(0, 0)
        val flatSec = flatChunk.sections[0]
        val flatSafe = net.caffeinemc.mods.lithium.common.ai.pathing.PathNodeCache.isSectionSafeAsNeighbor(flatSec)
        check(flatSafe) { "flat spawn section was falsely marked unsafe as a pathfinding neighbor" }
        println("PATHFINDING_PARITY_CHECKS_PASSED typeChecks=$typeChecks queries=${cases.size} flatSafe=$flatSafe")
    }

    private data class Query(
        val name: String,
        val mob: Mob,
        val start: BlockPos,
        val targets: Set<BlockPos>,
        val maxPathLength: Float,
        val reachRange: Int,
        val budget: Int,
    )

    private fun buildArena(level: ServerLevel, ox: Int, oy: Int, oz: Int) {
        val pos = BlockPos.MutableBlockPos()
        // Clear volume and place solid stone floor at oy - 1 across chunks (ox-16..ox+16, oz-16..oz+16).
        for (x in (ox - 16)..(ox + 16)) {
            for (z in (oz - 16)..(oz + 16)) {
                level.setBlock(pos.set(x, oy - 2, z), Blocks.STONE.defaultBlockState(), 2)
                level.setBlock(pos.set(x, oy - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2)
                for (y in oy..(oy + 5)) {
                    level.setBlock(pos.set(x, y, z), Blocks.AIR.defaultBlockState(), 2)
                }
            }
        }
        // Wall along chunk seam x = ox with a wooden door (closed) at z = 0, open door at z = 2, fence at z = -1..-3.
        for (z in -6..6) {
            level.setBlock(pos.set(ox, oy, z), Blocks.COBBLESTONE.defaultBlockState(), 2)
            level.setBlock(pos.set(ox, oy + 1, z), Blocks.COBBLESTONE.defaultBlockState(), 2)
        }
        level.setBlock(pos.set(ox, oy, 0), Blocks.OAK_DOOR.defaultBlockState(), 2)
        level.setBlock(pos.set(ox, oy + 1, 0), Blocks.AIR.defaultBlockState(), 2)
        level.setBlock(pos.set(ox, oy, 2), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true), 2)
        level.setBlock(pos.set(ox, oy + 1, 2), Blocks.AIR.defaultBlockState(), 2)
        for (z in -3..-1) {
            level.setBlock(pos.set(ox, oy, z), Blocks.OAK_FENCE.defaultBlockState(), 2)
            level.setBlock(pos.set(ox, oy + 1, z), Blocks.AIR.defaultBlockState(), 2)
        }
        // Hazards straddling chunk seam (ox - 1, ox): water, lava, cactus on sand, fire on netherrack.
        level.setBlock(pos.set(ox - 1, oy - 1, 4), Blocks.WATER.defaultBlockState(), 2)
        level.setBlock(pos.set(ox, oy - 1, 5), Blocks.LAVA.defaultBlockState(), 2)
        level.setBlock(pos.set(ox - 1, oy - 1, 6), Blocks.SAND.defaultBlockState(), 2)
        level.setBlock(pos.set(ox - 1, oy, 6), Blocks.CACTUS.defaultBlockState(), 2)
        level.setBlock(pos.set(ox, oy - 1, 3), Blocks.NETHERRACK.defaultBlockState(), 2)
        level.setBlock(pos.set(ox, oy, 3), Blocks.FIRE.defaultBlockState(), 2)
        // Rails and honey along z = -4.
        level.setBlock(pos.set(ox - 2, oy, -4), Blocks.RAIL.defaultBlockState(), 2)
        level.setBlock(pos.set(ox - 1, oy - 1, -4), Blocks.HONEY_BLOCK.defaultBlockState(), 2)
        level.setBlock(pos.set(ox + 1, oy, -4), Blocks.OAK_TRAPDOOR.defaultBlockState(), 2)
        // Elevated platform with slabs and stairs along z = 8 (crosses section Y seam y = 79 -> 80).
        level.setBlock(pos.set(ox + 2, oy, 8), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), 2)
        for (x in (ox + 3)..(ox + 10)) {
            for (z in 7..9) {
                level.setBlock(pos.set(x, oy, z), Blocks.STONE.defaultBlockState(), 2)
            }
        }
        // Sealed 3x3 enclosure around (ox + 10, oy, oz - 10) for partial/budget-exhaustion tests.
        for (dx in -2..2) {
            for (dz in -2..2) {
                if (kotlin.math.abs(dx) == 2 || kotlin.math.abs(dz) == 2) {
                    for (dy in 0..2) {
                        level.setBlock(pos.set(ox + 10 + dx, oy + dy, oz - 10 + dz), Blocks.BARRIER.defaultBlockState(), 2)
                    }
                }
            }
        }
    }

    private fun assertPathsEqual(name: String, expected: Path?, actual: Path?) {
        if (expected == null || actual == null) {
            check(expected === actual) { "$name: null mismatch expected=$expected actual=$actual" }
            return
        }
        check(actual.canReach() == expected.canReach()) { "$name: canReach expected=${expected.canReach()} actual=${actual.canReach()}" }
        check(actual.target == expected.target) { "$name: target expected=${expected.target} actual=${actual.target}" }
        check(actual.nodeCount == expected.nodeCount) {
            "$name: nodeCount expected=${expected.nodeCount} actual=${actual.nodeCount}\nexpected=${formatPath(expected)}\nactual=${formatPath(actual)}"
        }
        check(actual.distToTarget == expected.distToTarget) {
            "$name: distToTarget expected=${expected.distToTarget} actual=${actual.distToTarget}"
        }
        for (i in 0 until expected.nodeCount) {
            val e = expected.getNode(i)
            val a = actual.getNode(i)
            check(e.x == a.x && e.y == a.y && e.z == a.z && e.type == a.type && e.costMalus == a.costMalus) {
                "$name: node[$i] mismatch expected=(${e.x},${e.y},${e.z},${e.type},${e.costMalus}) actual=(${a.x},${a.y},${a.z},${a.type},${a.costMalus})"
            }
        }
    }

    private fun formatPath(path: Path): String =
        (0 until path.nodeCount).joinToString(" -> ") { i ->
            val n = path.getNode(i)
            "(${n.x},${n.y},${n.z}:${n.type})"
        }

    /**
     * Unmodified vanilla 26.3 `WalkNodeEvaluator` + `PathFinder` reference oracle, free of Vanadium and Lithium
     * evaluator mixins, reading directly through `BlockGetter.getBlockState`.
     */
    private object VanillaOracle {
        fun getPathTypeFromState(level: BlockGetter, pos: BlockPos): PathType {
            val blockState = level.getBlockState(pos)
            val block = blockState.block
            if (blockState.isAir) return PathType.OPEN
            if (blockState.`is`(BlockTags.TRAPDOORS) || blockState.`is`(Blocks.LILY_PAD) || blockState.`is`(Blocks.BIG_DRIPLEAF)) return PathType.TRAPDOOR
            if (blockState.`is`(Blocks.POWDER_SNOW)) return PathType.POWDER_SNOW
            if (blockState.`is`(Blocks.CACTUS) || blockState.`is`(Blocks.SWEET_BERRY_BUSH)) return PathType.DAMAGING
            if (blockState.`is`(Blocks.HONEY_BLOCK)) return PathType.STICKY_HONEY
            if (blockState.`is`(Blocks.COCOA)) return PathType.COCOA
            if (!blockState.`is`(Blocks.WITHER_ROSE) && !blockState.`is`(BlockTags.SPELEOTHEMS)) {
                val fluidState = blockState.fluidState
                if (fluidState.`is`(FluidTags.LAVA)) return PathType.LAVA
                if (NodeEvaluator.isBurningBlock(blockState)) return PathType.FIRE
                if (block is DoorBlock) {
                    return if (blockState.getValue(DoorBlock.OPEN)) PathType.DOOR_OPEN
                    else if (block.type().canOpenByHand()) PathType.DOOR_WOOD_CLOSED else PathType.DOOR_IRON_CLOSED
                }
                if (block is BaseRailBlock) return PathType.RAIL
                if (block is LeavesBlock) return PathType.LEAVES
                if (!blockState.`is`(BlockTags.FENCES) && !blockState.`is`(BlockTags.WALLS) &&
                    (block !is FenceGateBlock || blockState.getValue(FenceGateBlock.OPEN))) {
                    return if (!blockState.isPathfindable(PathComputationType.LAND)) PathType.BLOCKED
                    else if (fluidState.`is`(FluidTags.WATER)) PathType.WATER else PathType.OPEN
                }
                return PathType.FENCE
            }
            return PathType.DAMAGE_CAUTIOUS
        }

        fun getPathTypeStatic(level: BlockGetter, x: Int, y: Int, z: Int): PathType {
            val pos = BlockPos.MutableBlockPos()
            val blockPathType = getPathTypeFromState(level, pos.set(x, y, z))
            if (blockPathType == PathType.OPEN && y >= level.minY + 1) {
                return when (getPathTypeFromState(level, pos.set(x, y - 1, z))) {
                    PathType.OPEN, PathType.WATER, PathType.LAVA, PathType.WALKABLE -> PathType.OPEN
                    PathType.FIRE -> PathType.FIRE
                    PathType.DAMAGING -> PathType.DAMAGING
                    PathType.STICKY_HONEY -> PathType.STICKY_HONEY
                    PathType.POWDER_SNOW -> PathType.ON_TOP_OF_POWDER_SNOW
                    PathType.DAMAGE_CAUTIOUS -> PathType.DAMAGE_CAUTIOUS
                    PathType.TRAPDOOR -> PathType.ON_TOP_OF_TRAPDOOR
                    else -> checkNeighbourBlocks(level, x, y, z, PathType.WALKABLE)
                }
            }
            return blockPathType
        }

        private fun checkNeighbourBlocks(level: BlockGetter, x: Int, y: Int, z: Int, defaultType: PathType): PathType {
            val pos = BlockPos.MutableBlockPos()
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx != 0 || dz != 0) {
                            val t = getPathTypeFromState(level, pos.set(x + dx, y + dy, z + dz))
                            if (t == PathType.DAMAGING) return PathType.DAMAGING_IN_NEIGHBOR
                            if (t == PathType.FIRE || t == PathType.LAVA) return PathType.FIRE_IN_NEIGHBOR
                            if (t == PathType.WATER) return PathType.WATER_BORDER
                            if (t == PathType.DAMAGE_CAUTIOUS) return PathType.DAMAGE_CAUTIOUS
                        }
                    }
                }
            }
            return defaultType
        }

        fun findPath(
            level: PathNavigationRegion,
            mob: Mob,
            targets: Set<BlockPos>,
            maxPathLength: Float,
            reachRange: Int,
            maxVisitedNodes: Int,
            canPassDoors: Boolean,
            canOpenDoors: Boolean,
            canFloat: Boolean,
        ): Path? {
            val eval = RefEvaluator(level, mob, canPassDoors, canOpenDoors, canFloat)
            val from = eval.getStart()
            val targetMap = HashMap<Target, BlockPos>()
            for (pos in targets) {
                targetMap[Target(eval.getNode(pos.x, pos.y, pos.z))] = pos
            }
            val openSet = BinaryHeap()
            val targetSet = targetMap.keys
            from.g = 0.0f
            from.h = getBestH(from, targetSet)
            from.f = from.h
            openSet.insert(from)
            var count = 0
            val reached = HashSet<Target>()
            val neighbors = arrayOfNulls<Node>(32)
            while (!openSet.isEmpty) {
                if (++count >= maxVisitedNodes) break
                val current = openSet.pop()
                current.closed = true
                for (t in targetSet) {
                    if (current.distanceManhattan(t) <= reachRange) {
                        t.setReached()
                        reached.add(t)
                    }
                }
                if (reached.isNotEmpty()) break
                if (!(current.distanceTo(from) >= maxPathLength)) {
                    val n = eval.getNeighbors(neighbors, current)
                    for (i in 0 until n) {
                        val neighbor = neighbors[i]!!
                        val dist = current.distanceTo(neighbor)
                        neighbor.walkedDistance = current.walkedDistance + dist
                        val tentativeG = current.g + dist + neighbor.costMalus
                        if (neighbor.walkedDistance < maxPathLength && (!neighbor.inOpenSet() || tentativeG < neighbor.g)) {
                            neighbor.cameFrom = current
                            neighbor.g = tentativeG
                            neighbor.h = getBestH(neighbor, targetSet) * 1.5f
                            if (neighbor.inOpenSet()) {
                                openSet.changeCost(neighbor, neighbor.g + neighbor.h)
                            } else {
                                neighbor.f = neighbor.g + neighbor.h
                                openSet.insert(neighbor)
                            }
                        }
                    }
                }
            }
            val opt = if (reached.isNotEmpty()) {
                reached.stream()
                    .map { t -> reconstruct(t.bestNode, targetMap[t]!!, true) }
                    .min(Comparator.comparingInt(Path::getNodeCount))
            } else {
                targetSet.stream()
                    .map { t -> reconstruct(t.bestNode, targetMap[t]!!, false) }
                    .min(Comparator.comparingDouble<Path> { it.distToTarget.toDouble() }.thenComparingInt(Path::getNodeCount))
            }
            return opt.orElse(null)
        }

        private fun getBestH(from: Node, targets: Set<Target>): Float {
            var best = Float.MAX_VALUE
            for (t in targets) {
                val h = from.distanceTo(t)
                t.updateBest(h, from)
                best = minOf(h, best)
            }
            return best
        }

        private fun reconstruct(closest: Node, target: BlockPos, reached: Boolean): Path {
            val list = ArrayList<Node>()
            var n: Node? = closest
            while (n != null) {
                list.add(0, n)
                n = n.cameFrom
            }
            return Path(list, target, reached)
        }

        private class RefEvaluator(
            private val level: PathNavigationRegion,
            private val mob: Mob,
            private val canPassDoors: Boolean,
            private val canOpenDoors: Boolean,
            private val canFloat: Boolean,
        ) {
            private val nodes = Int2ObjectOpenHashMap<Node>()
            private val typeCache = Long2ObjectOpenHashMap<PathType>()
            private val collisionCache = Object2BooleanOpenHashMap<AABB>()
            private val reusable = arrayOfNulls<Node>(4)
            private val entityWidth = Mth.floor(mob.bbWidth + 1.0f)
            private val entityHeight = Mth.floor(mob.bbHeight + 1.0f)
            private val entityDepth = Mth.floor(mob.bbWidth + 1.0f)

            fun getNode(x: Int, y: Int, z: Int): Node = nodes.computeIfAbsent(Node.createHash(x, y, z)) { Node(x, y, z) }

            fun getStart(): Node {
                val startY = Mth.floor(mob.y + 0.5)
                val startPos = mob.blockPosition()
                val reusablePos = BlockPos.MutableBlockPos()
                if (!canStartAt(reusablePos.set(startPos.x, startY, startPos.z))) {
                    val bb = mob.boundingBox
                    if (canStartAt(reusablePos.set(bb.minX, startY.toDouble(), bb.minZ)) ||
                        canStartAt(reusablePos.set(bb.minX, startY.toDouble(), bb.maxZ)) ||
                        canStartAt(reusablePos.set(bb.maxX, startY.toDouble(), bb.minZ)) ||
                        canStartAt(reusablePos.set(bb.maxX, startY.toDouble(), bb.maxZ))) {
                        return getStartNode(reusablePos)
                    }
                }
                return getStartNode(BlockPos(startPos.x, startY, startPos.z))
            }

            private fun getStartNode(pos: BlockPos): Node {
                val n = getNode(pos.x, pos.y, pos.z)
                n.type = getCachedPathType(n.x, n.y, n.z)
                n.costMalus = mob.getPathfindingMalus(n.type)
                return n
            }

            private fun canStartAt(pos: BlockPos): Boolean {
                val t = getCachedPathType(pos.x, pos.y, pos.z)
                return t != PathType.OPEN && mob.getPathfindingMalus(t) >= 0.0f
            }

            fun getNeighbors(neighbors: Array<Node?>, pos: Node): Int {
                var p = 0
                var jumpSize = 0
                val above = getCachedPathType(pos.x, pos.y + 1, pos.z)
                val cur = getCachedPathType(pos.x, pos.y, pos.z)
                if (mob.getPathfindingMalus(above) >= 0.0f && cur != PathType.STICKY_HONEY) {
                    jumpSize = Mth.floor(maxOf(1.0f, mob.maxUpStep()))
                }
                val posHeight = getFloorLevel(BlockPos(pos.x, pos.y, pos.z))
                for (dir in Direction.Plane.HORIZONTAL) {
                    val node = findAcceptedNode(pos.x + dir.stepX, pos.y, pos.z + dir.stepZ, jumpSize, posHeight, dir, cur)
                    reusable[dir.get2DDataValue()] = node
                    if (node != null && !node.closed && (node.costMalus >= 0.0f || cur.malus < 0.0f || pos.costMalus < 0.0f)) {
                        // Exact vanilla isNeighborValid:
                        if (node.costMalus >= 0.0f || pos.costMalus < 0.0f) neighbors[p++] = node
                    }
                }
                for (dir in Direction.Plane.HORIZONTAL) {
                    val second = dir.clockWise
                    if (isDiagValid(pos, reusable[dir.get2DDataValue()], reusable[second.get2DDataValue()])) {
                        val diag = findAcceptedNode(pos.x + dir.stepX + second.stepX, pos.y, pos.z + dir.stepZ + second.stepZ, jumpSize, posHeight, dir, cur)
                        if (diag != null && !diag.closed && diag.type != PathType.WALKABLE_DOOR && diag.costMalus >= 0.0f) {
                            neighbors[p++] = diag
                        }
                    }
                }
                return p
            }

            private fun isDiagValid(pos: Node, ew: Node?, ns: Node?): Boolean {
                if (ns == null || ew == null || ns.y > pos.y || ew.y > pos.y) return false
                if (ew.type == PathType.WALKABLE_DOOR || ns.type == PathType.WALKABLE_DOOR) return false
                if (mob.bbWidth > 1.0f && (ew.costMalus > 0.0f || ns.costMalus > 0.0f)) return false
                val posts = ns.type == PathType.FENCE && ew.type == PathType.FENCE && mob.bbWidth < 0.5
                return (ns.y < pos.y || ns.costMalus >= 0.0f || posts) && (ew.y < pos.y || ew.costMalus >= 0.0f || posts)
            }

            private fun getFloorLevel(pos: BlockPos): Double {
                if (canFloat && level.getFluidState(pos).`is`(FluidTags.ENTITY_FLOATABLE)) return pos.y + 0.5
                val target = pos.below()
                val shape = level.getBlockState(target).getCollisionShape(level, target)
                return target.y + if (shape.isEmpty) 0.0 else shape.max(Direction.Axis.Y)
            }

            private fun findAcceptedNode(
                x: Int,
                y: Int,
                z: Int,
                jumpSize: Int,
                nodeHeight: Double,
                dir: Direction,
                curType: PathType,
            ): Node? {
                var best: Node? = null
                val reusablePos = BlockPos.MutableBlockPos()
                val maxY = getFloorLevel(reusablePos.set(x, y, z))
                if (maxY - nodeHeight > maxOf(1.125, mob.maxUpStep().toDouble())) return null
                val pathType = getCachedPathType(x, y, z)
                val cost = mob.getPathfindingMalus(pathType)
                if (cost >= 0.0f) {
                    best = getNodeAndUpdate(x, y, z, pathType, cost)
                }
                if (hasPartialCollision(curType) && best != null && best.costMalus >= 0.0f && !canReachWithoutCollision(best)) {
                    best = null
                }
                if (pathType != PathType.WALKABLE) {
                    if ((best == null || best.costMalus < 0.0f) && jumpSize > 0 &&
                        pathType != PathType.FENCE && pathType != PathType.UNPASSABLE_RAIL &&
                        pathType != PathType.TRAPDOOR && pathType != PathType.POWDER_SNOW) {
                        best = tryJumpOn(x, y, z, jumpSize, nodeHeight, dir, curType, reusablePos)
                    } else if (pathType == PathType.WATER && !canFloat) {
                        var cy = y - 1
                        while (cy > mob.level().minY) {
                            val t = getCachedPathType(x, cy, z)
                            if (t != PathType.WATER) break
                            best = getNodeAndUpdate(x, cy, z, t, mob.getPathfindingMalus(t))
                            cy--
                        }
                    } else if (pathType == PathType.OPEN) {
                        best = tryFall(x, y, z)
                    } else if (hasPartialCollision(pathType) && best == null) {
                        val n = getNode(x, y, z)
                        n.closed = true
                        n.type = pathType
                        n.costMalus = pathType.malus
                        best = n
                    }
                }
                return best
            }

            private fun tryJumpOn(
                x: Int, y: Int, z: Int, jumpSize: Int, nodeHeight: Double, dir: Direction, curType: PathType, reusablePos: BlockPos.MutableBlockPos,
            ): Node? {
                val above = findAcceptedNode(x, y + 1, z, jumpSize - 1, nodeHeight, dir, curType) ?: return null
                if (mob.bbWidth >= 1.0f) return above
                if (above.type != PathType.OPEN && above.type != PathType.WALKABLE) return above
                val cx = x - dir.stepX + 0.5
                val cz = z - dir.stepZ + 0.5
                val hw = mob.bbWidth / 2.0
                val box = AABB(
                    cx - hw, getFloorLevel(reusablePos.set(cx, (y + 1).toDouble(), cz)) + 0.001, cz - hw,
                    cx + hw, mob.bbHeight + getFloorLevel(reusablePos.set(above.x.toDouble(), above.y.toDouble(), above.z.toDouble())) - 0.002, cz + hw,
                )
                return if (hasCollisions(box)) null else above
            }

            private fun tryFall(x: Int, y: Int, z: Int): Node {
                for (cy in (y - 1) downTo mob.level().minY) {
                    if (y - cy > mob.maxFallDistance) return blocked(x, cy, z)
                    val t = getCachedPathType(x, cy, z)
                    val c = mob.getPathfindingMalus(t)
                    if (t != PathType.OPEN) {
                        return if (c >= 0.0f) getNodeAndUpdate(x, cy, z, t, c) else blocked(x, cy, z)
                    }
                }
                return blocked(x, y, z)
            }

            private fun getNodeAndUpdate(x: Int, y: Int, z: Int, type: PathType, cost: Float): Node {
                val n = getNode(x, y, z)
                n.type = type
                n.costMalus = maxOf(n.costMalus, cost)
                return n
            }

            private fun blocked(x: Int, y: Int, z: Int): Node {
                val n = getNode(x, y, z)
                n.type = PathType.BLOCKED
                n.costMalus = -1.0f
                return n
            }

            private fun hasPartialCollision(type: PathType) =
                type == PathType.FENCE || type == PathType.DOOR_WOOD_CLOSED || type == PathType.DOOR_IRON_CLOSED

            private fun canReachWithoutCollision(to: Node): Boolean {
                var bb = mob.boundingBox
                var delta = Vec3(to.x - mob.x + bb.xsize / 2.0, to.y - mob.y + bb.ysize / 2.0, to.z - mob.z + bb.zsize / 2.0)
                val steps = Mth.ceil(delta.length() / bb.size)
                delta = delta.scale((1.0f / steps).toDouble())
                for (i in 1..steps) {
                    bb = bb.move(delta)
                    if (hasCollisions(bb)) return false
                }
                return true
            }

            private fun hasCollisions(box: AABB): Boolean =
                collisionCache.computeIfAbsent(box) { !level.noCollision(mob, box) }

            private fun getCachedPathType(x: Int, y: Int, z: Int): PathType =
                typeCache.computeIfAbsent(BlockPos.asLong(x, y, z)) { getPathTypeOfMob(x, y, z) }

            private fun getPathTypeOfMob(x: Int, y: Int, z: Int): PathType {
                val types = EnumSet.noneOf(PathType::class.java)
                val mobPos = mob.blockPosition()
                for (dx in 0 until entityWidth) {
                    for (dy in 0 until entityHeight) {
                        for (dz in 0 until entityDepth) {
                            var t = getPathTypeStatic(level, x + dx, y + dy, z + dz)
                            if (t == PathType.DOOR_WOOD_CLOSED && canOpenDoors && canPassDoors) t = PathType.WALKABLE_DOOR
                            if (t == PathType.DOOR_OPEN && !canPassDoors) t = PathType.BLOCKED
                            if (t == PathType.RAIL &&
                                getPathTypeStatic(level, mobPos.x, mobPos.y, mobPos.z) != PathType.RAIL &&
                                getPathTypeStatic(level, mobPos.x, mobPos.y - 1, mobPos.z) != PathType.RAIL) {
                                t = PathType.UNPASSABLE_RAIL
                            }
                            types.add(t)
                        }
                    }
                }
                if (types.size == 1) return types.iterator().next()
                if (PathType.FENCE in types) return PathType.FENCE
                if (PathType.UNPASSABLE_RAIL in types) return PathType.UNPASSABLE_RAIL
                var highestType = PathType.BLOCKED
                var highestMalus = mob.getPathfindingMalus(highestType)
                for (t in types) {
                    val m = mob.getPathfindingMalus(t)
                    if (m < 0.0f) return t
                    if (m >= highestMalus) {
                        highestMalus = m
                        highestType = t
                    }
                }
                val origin = getPathTypeStatic(level, x, y, z)
                return if (entityWidth > 1) {
                    val cheaper = mob.getPathfindingMalus(origin) < highestMalus
                    val cap = cheaper && mob.getPathfindingMalus(PathType.BIG_MOBS_CLOSE_TO_DANGER) < highestMalus
                    if (cap) PathType.BIG_MOBS_CLOSE_TO_DANGER else highestType
                } else {
                    if (origin == PathType.OPEN && highestType != PathType.OPEN && highestMalus == 0.0f) PathType.OPEN else highestType
                }
            }
        }
    }
}
