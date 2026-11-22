package com.synsenetwork.vanadium.entity

import com.synsenetwork.vanadium.tick.TickThread
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import java.util.Arrays
import net.minecraft.CrashReport
import net.minecraft.CrashReportCategory
import net.minecraft.ReportedException
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs

/**
 * Allocation-free / low-allocation exact implementation of `Entity.checkInsideBlocks`.
 *
 * Preserves vanilla's exact axis step order, `maxSteps = 16` budget, `StepBasedCollector.advanceStep` calls,
 * `visitedBlocks` deduplication across sub-steps, fluid/shape collision checks, crash-report wrapping, and
 * `Entity.makeBoundingBox(Vec3)` subclass overrides.
 *
 * Most mob ticks either stay within the same integer block bounds (zero allocations, zero heap writes) or cross
 * one block face (reuses a padded per-thread open-addressed visited table on [TickThread]). Direct chunk/section
 * reads skip air blocks without `BlockPos` or `Level.getBlockState` overhead and invalidate the local section cache
 * whenever a non-air block callback executes.
 */
object InsideBlockFastPath {
    /** Exact `(double) 1.0E-5F` deflation used by vanilla `Entity.checkInsideBlocks` and `BlockGetter.addCollisionsAlongTravel`. */
    internal const val DEFLATE_EPS: Double = 1.0E-5f.toDouble()

    /** Exact `(double) Mth.square(1.0E-5F)` stationary threshold in `BlockGetter.forEachBlockIntersectedBetween`. */
    private val STATIONARY_SQR: Double = Mth.square(1.0E-5f).toDouble()

    /** Exact `Mth.square(1.0 - 1.0E-5F)` far-movement threshold in `Entity.checkInsideBlocks`. */
    private val MOVED_FAR_SQR: Double = Mth.square(0.9999900000002526)

    private const val MAX_STEPS = 16

    private val threadScratch = ThreadLocal.withInitial(::Scratch)

    @JvmStatic
    fun checkInsideBlocks(
        entity: Entity,
        level: Level,
        movements: List<Entity.Movement>,
        collector: InsideBlockEffectApplier.StepBasedCollector,
        visitedBlocks: LongSet,
        defaultMakeBB: Boolean,
    ) {
        val count = movements.size
        for (i in 0 until count) {
            val movement = movements[i]
            val from = movement.from()
            val to = movement.to()
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            val deltaLenSqr = dx * dx + dy * dy + dz * dz
            var maxSteps = MAX_STEPS
            val origOpt = movement.axisDependentOriginalMovement()
            if (origOpt.isPresent && deltaLenSqr > 0.0) {
                val orig = origOpt.get()
                var curX = from.x
                var curY = from.y
                var curZ = from.z
                var curVec: Vec3? = from

                // Direction.axisStepOrder(orig) always visits Y first, then (Z, X) if |orig.x| < |orig.z| else (X, Z).
                if (dy != 0.0) {
                    val nextY = curY + dy
                    val nextVec = if (dx == 0.0 && dz == 0.0 && nextY == to.y) to else null
                    maxSteps -= checkSegment(
                        entity, level, defaultMakeBB,
                        curX, curY, curZ, curX, nextY, curZ,
                        curVec, nextVec, collector, visitedBlocks, maxSteps,
                    )
                    curY = nextY
                    curVec = nextVec
                }
                if (abs(orig.x) < abs(orig.z)) {
                    if (dz != 0.0) {
                        val nextZ = curZ + dz
                        val nextVec = if (dx == 0.0 && curY == to.y && nextZ == to.z) to else null
                        maxSteps -= checkSegment(
                            entity, level, defaultMakeBB,
                            curX, curY, curZ, curX, curY, nextZ,
                            curVec, nextVec, collector, visitedBlocks, maxSteps,
                        )
                        curZ = nextZ
                        curVec = nextVec
                    }
                    if (dx != 0.0) {
                        val nextX = curX + dx
                        val nextVec = if (nextX == to.x && curY == to.y && curZ == to.z) to else null
                        maxSteps -= checkSegment(
                            entity, level, defaultMakeBB,
                            curX, curY, curZ, nextX, curY, curZ,
                            curVec, nextVec, collector, visitedBlocks, maxSteps,
                        )
                    }
                } else {
                    if (dx != 0.0) {
                        val nextX = curX + dx
                        val nextVec = if (dz == 0.0 && nextX == to.x && curY == to.y) to else null
                        maxSteps -= checkSegment(
                            entity, level, defaultMakeBB,
                            curX, curY, curZ, nextX, curY, curZ,
                            curVec, nextVec, collector, visitedBlocks, maxSteps,
                        )
                        curX = nextX
                        curVec = nextVec
                    }
                    if (dz != 0.0) {
                        val nextZ = curZ + dz
                        val nextVec = if (curX == to.x && curY == to.y && nextZ == to.z) to else null
                        maxSteps -= checkSegment(
                            entity, level, defaultMakeBB,
                            curX, curY, curZ, curX, curY, nextZ,
                            curVec, nextVec, collector, visitedBlocks, maxSteps,
                        )
                    }
                }
            } else {
                maxSteps -= checkSegment(
                    entity, level, defaultMakeBB,
                    from.x, from.y, from.z, to.x, to.y, to.z,
                    from, to, collector, visitedBlocks, MAX_STEPS,
                )
            }
            if (maxSteps <= 0) {
                checkSegment(
                    entity, level, defaultMakeBB,
                    to.x, to.y, to.z, to.x, to.y, to.z,
                    to, to, collector, visitedBlocks, 1,
                )
            }
        }
        if (!visitedBlocks.isEmpty()) {
            visitedBlocks.clear()
        }
    }

    private fun checkSegment(
        entity: Entity,
        level: Level,
        defaultMakeBB: Boolean,
        fromX: Double,
        fromY: Double,
        fromZ: Double,
        toX: Double,
        toY: Double,
        toZ: Double,
        initialFromVec: Vec3?,
        initialToVec: Vec3?,
        collector: InsideBlockEffectApplier.StepBasedCollector,
        visitedBlocks: LongSet,
        maxSteps: Int,
    ): Int {
        if (!entity.isAlive || maxSteps <= 0) return 1

        val minX: Double
        val minY: Double
        val minZ: Double
        val maxX: Double
        val maxY: Double
        val maxZ: Double
        var toVec = initialToVec
        if (defaultMakeBB) {
            val dims = entity.dimensions
            val hw = (dims.width() / 2.0f).toDouble()
            val h = dims.height().toDouble()
            val x0 = toX - hw + DEFLATE_EPS
            val x1 = toX + hw - DEFLATE_EPS
            val y0 = toY + DEFLATE_EPS
            val y1 = toY + h - DEFLATE_EPS
            val z0 = toZ - hw + DEFLATE_EPS
            val z1 = toZ + hw - DEFLATE_EPS
            minX = if (x0 <= x1) x0 else x1
            maxX = if (x0 <= x1) x1 else x0
            minY = if (y0 <= y1) y0 else y1
            maxY = if (y0 <= y1) y1 else y0
            minZ = if (z0 <= z1) z0 else z1
            maxZ = if (z0 <= z1) z1 else z0
        } else {
            val tv = toVec ?: Vec3(toX, toY, toZ).also { toVec = it }
            val bb = entity.makeBoundingBox(tv).deflate(DEFLATE_EPS)
            minX = bb.minX
            minY = bb.minY
            minZ = bb.minZ
            maxX = bb.maxX
            maxY = bb.maxY
            maxZ = bb.maxZ
        }

        val dx = toX - fromX
        val dy = toY - fromY
        val dz = toZ - fromZ
        val deltaLenSqr = dx * dx + dy * dy + dz * dz
        val movedFar = deltaLenSqr > MOVED_FAR_SQR

        val eMinX = Mth.floor(minX)
        val eMinY = Mth.floor(minY)
        val eMinZ = Mth.floor(minZ)
        val eMaxX = Mth.floor(maxX)
        val eMaxY = Mth.floor(maxY)
        val eMaxZ = Mth.floor(maxZ)

        // Case A: stationary or sub-epsilon move -> BlockPos.betweenClosed(box) at step 0 (X fastest, then Y, then Z).
        if (deltaLenSqr < STATIONARY_SQR) {
            return scanBoxStationary(
                entity, level,
                fromX, fromY, fromZ, toX, toY, toZ, initialFromVec, toVec,
                minX, minY, minZ, maxX, maxY, maxZ, movedFar,
                eMinX, eMinY, eMinZ, eMaxX, eMaxY, eMaxZ,
                collector, visitedBlocks,
            )
        }

        // Case B: moving, but startBox and endBox cover the exact same integer block range and no negative-moving face
        // sits on an integer plane (so addCollisionsAlongTravel cannot clip an adjacent lower block).
        val sMinX = Mth.floor(minX - dx)
        val sMinY = Mth.floor(minY - dy)
        val sMinZ = Mth.floor(minZ - dz)
        val sMaxX = Mth.floor(maxX - dx)
        val sMaxY = Mth.floor(maxY - dy)
        val sMaxZ = Mth.floor(maxZ - dz)
        if (sMinX == eMinX && sMaxX == eMaxX &&
            sMinY == eMinY && sMaxY == eMaxY &&
            sMinZ == eMinZ && sMaxZ == eMaxZ &&
            (dx >= 0.0 || (minX > eMinX.toDouble() && maxX > eMaxX.toDouble())) &&
            (dy >= 0.0 || (minY > eMinY.toDouble() && maxY > eMaxY.toDouble())) &&
            (dz >= 0.0 || (minZ > eMinZ.toDouble() && maxZ > eMaxZ.toDouble()))
        ) {
            return scanBoxDirected(
                entity, level,
                fromX, fromY, fromZ, toX, toY, toZ, initialFromVec, toVec,
                minX, minY, minZ, maxX, maxY, maxZ, movedFar,
                eMinX, eMinY, eMinZ, eMaxX, eMaxY, eMaxZ,
                dx, dy, dz, collector, visitedBlocks,
            )
        }

        // Case C: general swept travel crossing at least one block boundary.
        return scanSwept(
            entity, level,
            fromX, fromY, fromZ, toX, toY, toZ, initialFromVec, toVec,
            minX, minY, minZ, maxX, maxY, maxZ, movedFar,
            sMinX, sMinY, sMinZ, sMaxX, sMaxY, sMaxZ,
            eMinX, eMinY, eMinZ, eMaxX, eMaxY, eMaxZ,
            dx, dy, dz, collector, visitedBlocks, maxSteps,
        )
    }

    /** Case A: `BlockPos.betweenClosed(eMinX..eMaxX, eMinY..eMaxY, eMinZ..eMaxZ)` at `step = 0` with zero allocations on air. */
    private fun scanBoxStationary(
        entity: Entity,
        level: Level,
        fromX: Double,
        fromY: Double,
        fromZ: Double,
        toX: Double,
        toY: Double,
        toZ: Double,
        initialFromVec: Vec3?,
        initialToVec: Vec3?,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        movedFar: Boolean,
        minBx: Int,
        minBy: Int,
        minBz: Int,
        maxBx: Int,
        maxBy: Int,
        maxBz: Int,
        collector: InsideBlockEffectApplier.StepBasedCollector,
        visitedBlocks: LongSet,
    ): Int {
        val fastRead = !level.isDebug
        val minYWorld = level.minY
        val maxYWorld = level.maxY
        val minSectionY = level.minSectionY
        val chunkSource = level.chunkSource

        var cachedCx = Int.MIN_VALUE
        var cachedCz = Int.MIN_VALUE
        var cachedChunk: LevelChunk? = null
        var cachedSy = Int.MIN_VALUE
        var cachedSection: LevelChunkSection? = null
        var pos: BlockPos.MutableBlockPos? = null
        var fromVec = initialFromVec
        var toVec = initialToVec

        for (bz in minBz..maxBz) {
            val cz = bz shr 4
            for (by in minBy..maxBy) {
                val outsideY = by < minYWorld || by > maxYWorld
                val sy = (by shr 4) - minSectionY
                for (bx in minBx..maxBx) {
                    if (!entity.isAlive) return 1
                    if (outsideY) continue
                    val state: BlockState
                    if (fastRead) {
                        val cx = bx shr 4
                        if (cx != cachedCx || cz != cachedCz) {
                            cachedCx = cx
                            cachedCz = cz
                            cachedChunk = chunkSource.getChunkNow(cx, cz)
                            cachedSy = Int.MIN_VALUE
                            cachedSection = null
                        }
                        val chunk = cachedChunk
                        if (chunk != null) {
                            if (sy != cachedSy) {
                                cachedSy = sy
                                val sections = chunk.sections
                                cachedSection = if (sy in sections.indices) sections[sy] else null
                            }
                            val section = cachedSection
                            if (section == null || section.hasOnlyAir()) continue
                            state = section.getBlockState(bx and 15, by and 15, bz and 15)
                            if (state.isAir) continue
                        } else {
                            val mp = pos ?: BlockPos.MutableBlockPos().also { pos = it }
                            state = level.getBlockState(mp.set(bx, by, bz))
                            if (state.isAir) continue
                        }
                    } else {
                        val mp = pos ?: BlockPos.MutableBlockPos().also { pos = it }
                        state = level.getBlockState(mp.set(bx, by, bz))
                        if (state.isAir) continue
                    }

                    val mp = pos ?: BlockPos.MutableBlockPos().also { pos = it }
                    mp.set(bx, by, bz)
                    val shape = state.getEntityInsideCollisionShape(level, mp, entity)
                    val fluidState = state.fluidState
                    if ((shape !== Shapes.block() && !shape.isEmpty) || !fluidState.isEmpty) {
                        if (fromVec == null) fromVec = Vec3(fromX, fromY, fromZ)
                        if (toVec == null) toVec = Vec3(toX, toY, toZ)
                    }
                    if (collideAndApply(
                            entity, level, mp, state, shape, fluidState, fromVec, toVec,
                            movedFar, minX, minY, minZ, maxX, maxY, maxZ, bx, by, bz, 0, collector, visitedBlocks,
                        )
                    ) {
                        // entityInside callbacks can modify blocks or chunks; reload the local cache on the next cell.
                        cachedCx = Int.MIN_VALUE
                        cachedSy = Int.MIN_VALUE
                    }
                }
            }
        }
        return 1
    }

    /** Case B: `BlockPos.betweenCornersInDirection(eMin..eMax, delta)` at `step = 0` with zero allocations on air. */
    private fun scanBoxDirected(
        entity: Entity,
        level: Level,
        fromX: Double,
        fromY: Double,
        fromZ: Double,
        toX: Double,
        toY: Double,
        toZ: Double,
        initialFromVec: Vec3?,
        initialToVec: Vec3?,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        movedFar: Boolean,
        minBx: Int,
        minBy: Int,
        minBz: Int,
        maxBx: Int,
        maxBy: Int,
        maxBz: Int,
        dx: Double,
        dy: Double,
        dz: Double,
        collector: InsideBlockEffectApplier.StepBasedCollector,
        visitedBlocks: LongSet,
    ): Int {
        val fastRead = !level.isDebug
        val minYWorld = level.minY
        val maxYWorld = level.maxY
        val minSectionY = level.minSectionY
        val chunkSource = level.chunkSource

        val startX = if (dx >= 0.0) minBx else maxBx
        val startY = if (dy >= 0.0) minBy else maxBy
        val startZ = if (dz >= 0.0) minBz else maxBz
        val stepX = if (dx >= 0.0) 1 else -1
        val stepY = if (dy >= 0.0) 1 else -1
        val stepZ = if (dz >= 0.0) 1 else -1
        val countX = maxBx - minBx
        val countY = maxBy - minBy
        val countZ = maxBz - minBz
        val zBeforeX = abs(dx) < abs(dz)

        var cachedCx = Int.MIN_VALUE
        var cachedCz = Int.MIN_VALUE
        var cachedChunk: LevelChunk? = null
        var cachedSy = Int.MIN_VALUE
        var cachedSection: LevelChunkSection? = null
        var pos: BlockPos.MutableBlockPos? = null
        var fromVec = initialFromVec
        var toVec = initialToVec

        val secondCount = if (zBeforeX) countZ else countX
        val thirdCount = if (zBeforeX) countX else countZ

        var yi = 0
        while (yi <= countY) {
            val by = startY + yi * stepY
            val outsideY = by < minYWorld || by > maxYWorld
            val sy = (by shr 4) - minSectionY
            var si = 0
            while (si <= secondCount) {
                var ti = 0
                while (ti <= thirdCount) {
                    if (!entity.isAlive) return 1
                    if (!outsideY) {
                        val bx: Int
                        val bz: Int
                        if (zBeforeX) {
                            bz = startZ + si * stepZ
                            bx = startX + ti * stepX
                        } else {
                            bx = startX + si * stepX
                            bz = startZ + ti * stepZ
                        }
                        val state: BlockState
                        if (fastRead) {
                            val cx = bx shr 4
                            val cz = bz shr 4
                            if (cx != cachedCx || cz != cachedCz) {
                                cachedCx = cx
                                cachedCz = cz
                                cachedChunk = chunkSource.getChunkNow(cx, cz)
                                cachedSy = Int.MIN_VALUE
                                cachedSection = null
                            }
                            val chunk = cachedChunk
                            if (chunk != null) {
                                if (sy != cachedSy) {
                                    cachedSy = sy
                                    val sections = chunk.sections
                                    cachedSection = if (sy in sections.indices) sections[sy] else null
                                }
                                val section = cachedSection
                                if (section == null || section.hasOnlyAir()) {
                                    ti++
                                    continue
                                }
                                state = section.getBlockState(bx and 15, by and 15, bz and 15)
                                if (state.isAir) {
                                    ti++
                                    continue
                                }
                            } else {
                                val mp = pos ?: BlockPos.MutableBlockPos().also { pos = it }
                                state = level.getBlockState(mp.set(bx, by, bz))
                                if (state.isAir) {
                                    ti++
                                    continue
                                }
                            }
                        } else {
                            val mp = pos ?: BlockPos.MutableBlockPos().also { pos = it }
                            state = level.getBlockState(mp.set(bx, by, bz))
                            if (state.isAir) {
                                ti++
                                continue
                            }
                        }

                        val mp = pos ?: BlockPos.MutableBlockPos().also { pos = it }
                        mp.set(bx, by, bz)
                        val shape = state.getEntityInsideCollisionShape(level, mp, entity)
                        val fluidState = state.fluidState
                        if ((shape !== Shapes.block() && !shape.isEmpty) || !fluidState.isEmpty) {
                            if (fromVec == null) fromVec = Vec3(fromX, fromY, fromZ)
                            if (toVec == null) toVec = Vec3(toX, toY, toZ)
                        }
                        if (collideAndApply(
                                entity, level, mp, state, shape, fluidState, fromVec, toVec,
                                movedFar, minX, minY, minZ, maxX, maxY, maxZ, bx, by, bz, 0, collector, visitedBlocks,
                            )
                        ) {
                            cachedCx = Int.MIN_VALUE
                            cachedSy = Int.MIN_VALUE
                        }
                    }
                    ti++
                }
                si++
            }
            yi++
        }
        return 1
    }

    /** Case C: exact `BlockGetter.forEachBlockIntersectedBetween` + `addCollisionsAlongTravel` using reusable thread scratch. */
    private fun scanSwept(
        entity: Entity,
        level: Level,
        fromX: Double,
        fromY: Double,
        fromZ: Double,
        toX: Double,
        toY: Double,
        toZ: Double,
        initialFromVec: Vec3?,
        initialToVec: Vec3?,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        movedFar: Boolean,
        sMinX: Int,
        sMinY: Int,
        sMinZ: Int,
        sMaxX: Int,
        sMaxY: Int,
        sMaxZ: Int,
        eMinX: Int,
        eMinY: Int,
        eMinZ: Int,
        eMaxX: Int,
        eMaxY: Int,
        eMaxZ: Int,
        dx: Double,
        dy: Double,
        dz: Double,
        collector: InsideBlockEffectApplier.StepBasedCollector,
        visitedBlocks: LongSet,
        maxSteps: Int,
    ): Int {
        val scratch = borrowScratch()
        try {
            scratch.beginSweep(
                entity, level,
                fromX, fromY, fromZ, toX, toY, toZ, initialFromVec, initialToVec,
                minX, minY, minZ, maxX, maxY, maxZ, movedFar, collector, visitedBlocks, maxSteps,
            )

            // Phase 1: startBox at step 0 (every block visited unconditionally, then added to sweep-visited set).
            if (!scratch.visitBoxDirected(sMinX, sMinY, sMinZ, sMaxX, sMaxY, sMaxZ, dx, dy, dz, 0, dedupFirst = false)) {
                return scratch.lastStep + 1
            }

            // Phase 2: exact BlockGetter.addCollisionsAlongTravel DDA along the furthest box corner.
            val sizeX = maxX - minX
            val sizeY = maxY - minY
            val sizeZ = maxZ - minZ
            val absX = abs(dx)
            val absY = abs(dy)
            val absZ = abs(dz)
            val signX = if (dx >= 0.0) 1 else -1
            val signY = if (dy >= 0.0) 1 else -1
            val signZ = if (dz >= 0.0) 1 else -1
            val fcX: Int
            val fcY: Int
            val fcZ: Int
            if (absX <= absY && absX <= absZ) {
                fcX = -signX
                fcY = -signZ
                fcZ = signY
            } else if (absY <= absZ) {
                fcX = signZ
                fcY = -signY
                fcZ = -signX
            } else {
                fcX = -signY
                fcY = signX
                fcZ = -signZ
            }

            val centerX = Mth.lerp(0.5, minX, maxX)
            val centerY = Mth.lerp(0.5, minY, maxY)
            val centerZ = Mth.lerp(0.5, minZ, maxZ)
            val endCornerX = centerX + sizeX * 0.5 * fcX.toDouble()
            val endCornerY = centerY + sizeY * 0.5 * fcY.toDouble()
            val endCornerZ = centerZ + sizeZ * 0.5 * fcZ.toDouble()
            val startCornerX = endCornerX - dx
            val startCornerY = endCornerY - dy
            val startCornerZ = endCornerZ - dz
            val rayDx = endCornerX - startCornerX
            val rayDy = endCornerY - startCornerY
            val rayDz = endCornerZ - startCornerZ

            var curX = Mth.floor(startCornerX)
            var curY = Mth.floor(startCornerY)
            var curZ = Mth.floor(startCornerZ)
            val ddaStepX = Mth.sign(dx)
            val ddaStepY = Mth.sign(dy)
            val ddaStepZ = Mth.sign(dz)
            val tDeltaX = if (ddaStepX == 0) Double.MAX_VALUE else ddaStepX.toDouble() / dx
            val tDeltaY = if (ddaStepY == 0) Double.MAX_VALUE else ddaStepY.toDouble() / dy
            val tDeltaZ = if (ddaStepZ == 0) Double.MAX_VALUE else ddaStepZ.toDouble() / dz
            var tMaxX = tDeltaX * if (ddaStepX > 0) 1.0 - Mth.frac(startCornerX) else Mth.frac(startCornerX)
            var tMaxY = tDeltaY * if (ddaStepY > 0) 1.0 - Mth.frac(startCornerY) else Mth.frac(startCornerY)
            var tMaxZ = tDeltaZ * if (ddaStepZ > 0) 1.0 - Mth.frac(startCornerZ) else Mth.frac(startCornerZ)

            var stepCount = 0
            while (tMaxX <= 1.0 || tMaxY <= 1.0 || tMaxZ <= 1.0) {
                if (tMaxX < tMaxY) {
                    if (tMaxX < tMaxZ) {
                        curX += ddaStepX
                        tMaxX += tDeltaX
                    } else {
                        curZ += ddaStepZ
                        tMaxZ += tDeltaZ
                    }
                } else if (tMaxY < tMaxZ) {
                    curY += ddaStepY
                    tMaxY += tDeltaY
                } else {
                    curZ += ddaStepZ
                    tMaxZ += tDeltaZ
                }

                val hitT = clipUnitBox(curX, curY, curZ, startCornerX, startCornerY, startCornerZ, rayDx, rayDy, rayDz)
                if (hitT.isNaN()) continue

                stepCount++
                val hitX = startCornerX + hitT * rayDx
                val hitY = startCornerY + hitT * rayDy
                val hitZ = startCornerZ + hitT * rayDz
                val clampedX = Mth.clamp(hitX, curX.toDouble() + DEFLATE_EPS, curX.toDouble() + 1.0 - DEFLATE_EPS)
                val clampedY = Mth.clamp(hitY, curY.toDouble() + DEFLATE_EPS, curY.toDouble() + 1.0 - DEFLATE_EPS)
                val clampedZ = Mth.clamp(hitZ, curZ.toDouble() + DEFLATE_EPS, curZ.toDouble() + 1.0 - DEFLATE_EPS)
                val otherX = Mth.floor(clampedX - sizeX * fcX.toDouble())
                val otherY = Mth.floor(clampedY - sizeY * fcY.toDouble())
                val otherZ = Mth.floor(clampedZ - sizeZ * fcZ.toDouble())
                if (!scratch.visitBoxDirected(curX, curY, curZ, otherX, otherY, otherZ, dx, dy, dz, stepCount, dedupFirst = true)) {
                    return scratch.lastStep + 1
                }
            }

            // Phase 3: endBox at stepCount + 1 (deduplicated).
            scratch.visitBoxDirected(eMinX, eMinY, eMinZ, eMaxX, eMaxY, eMaxZ, dx, dy, dz, stepCount + 1, dedupFirst = true)
            return scratch.lastStep + 1
        } finally {
            scratch.endSweep()
            releaseScratch(scratch)
        }
    }

    /**
     * Primitive equivalent of `AABB.clip(bx, by, bz, bx + 1, by + 1, bz + 1, start, end)`.
     * Returns the hit fraction `t` in `(0.0, 1.0)`, or `Double.NaN` if the segment does not intersect the box.
     */
    internal fun clipUnitBox(
        bx: Int,
        by: Int,
        bz: Int,
        startX: Double,
        startY: Double,
        startZ: Double,
        dx: Double,
        dy: Double,
        dz: Double,
    ): Double {
        val minX = bx.toDouble()
        val minY = by.toDouble()
        val minZ = bz.toDouble()
        val maxX = (bx + 1).toDouble()
        val maxY = (by + 1).toDouble()
        val maxZ = (bz + 1).toDouble()
        var bestT = 1.0
        var hit = false

        if (dx > 1.0E-7) {
            val t = (minX - startX) / dx
            if (0.0 < t && t < bestT) {
                val y = startY + t * dy
                val z = startZ + t * dz
                if (minY - 1.0E-7 < y && y < maxY + 1.0E-7 && minZ - 1.0E-7 < z && z < maxZ + 1.0E-7) {
                    bestT = t
                    hit = true
                }
            }
        } else if (dx < -1.0E-7) {
            val t = (maxX - startX) / dx
            if (0.0 < t && t < bestT) {
                val y = startY + t * dy
                val z = startZ + t * dz
                if (minY - 1.0E-7 < y && y < maxY + 1.0E-7 && minZ - 1.0E-7 < z && z < maxZ + 1.0E-7) {
                    bestT = t
                    hit = true
                }
            }
        }

        if (dy > 1.0E-7) {
            val t = (minY - startY) / dy
            if (0.0 < t && t < bestT) {
                val z = startZ + t * dz
                val x = startX + t * dx
                if (minZ - 1.0E-7 < z && z < maxZ + 1.0E-7 && minX - 1.0E-7 < x && x < maxX + 1.0E-7) {
                    bestT = t
                    hit = true
                }
            }
        } else if (dy < -1.0E-7) {
            val t = (maxY - startY) / dy
            if (0.0 < t && t < bestT) {
                val z = startZ + t * dz
                val x = startX + t * dx
                if (minZ - 1.0E-7 < z && z < maxZ + 1.0E-7 && minX - 1.0E-7 < x && x < maxX + 1.0E-7) {
                    bestT = t
                    hit = true
                }
            }
        }

        if (dz > 1.0E-7) {
            val t = (minZ - startZ) / dz
            if (0.0 < t && t < bestT) {
                val x = startX + t * dx
                val y = startY + t * dy
                if (minX - 1.0E-7 < x && x < maxX + 1.0E-7 && minY - 1.0E-7 < y && y < maxY + 1.0E-7) {
                    bestT = t
                    hit = true
                }
            }
        } else if (dz < -1.0E-7) {
            val t = (maxZ - startZ) / dz
            if (0.0 < t && t < bestT) {
                val x = startX + t * dx
                val y = startY + t * dy
                if (minX - 1.0E-7 < x && x < maxX + 1.0E-7 && minY - 1.0E-7 < y && y < maxY + 1.0E-7) {
                    bestT = t
                    hit = true
                }
            }
        }

        return if (hit) bestT else Double.NaN
    }

    private fun collideAndApply(
        entity: Entity,
        level: Level,
        pos: BlockPos.MutableBlockPos,
        state: BlockState,
        shape: VoxelShape,
        fluidState: FluidState,
        fromVec: Vec3?,
        toVec: Vec3?,
        movedFar: Boolean,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        bx: Int,
        by: Int,
        bz: Int,
        step: Int,
        collector: InsideBlockEffectApplier.StepBasedCollector,
        visitedBlocks: LongSet,
    ): Boolean {
        val hitBlock = if (shape === Shapes.block()) {
            true
        } else if (shape.isEmpty) {
            false
        } else {
            val fv = checkNotNull(fromVec) { "fromVec missing for partial shape collision" }
            val tv = checkNotNull(toVec) { "toVec missing for partial shape collision" }
            entity.collidedWithShapeMovingFrom(fv, tv, shape.move(Vec3(pos)).toAabbs())
        }
        val hitFluid = if (fluidState.isEmpty) {
            false
        } else {
            val fv = checkNotNull(fromVec) { "fromVec missing for fluid collision" }
            val tv = checkNotNull(toVec) { "toVec missing for fluid collision" }
            entity.collidedWithFluid(fluidState, pos, fv, tv)
        }
        if ((!hitBlock && !hitFluid) || !visitedBlocks.add(pos.asLong())) {
            return false
        }
        if (hitBlock) {
            try {
                val checkMovedFar = movedFar || (
                    minX < (bx + 1).toDouble() && maxX > bx.toDouble() &&
                    minY < (by + 1).toDouble() && maxY > by.toDouble() &&
                    minZ < (bz + 1).toDouble() && maxZ > bz.toDouble()
                )
                collector.advanceStep(step)
                state.entityInside(level, pos, entity, collector, checkMovedFar)
                entity.onInsideBlock(state)
            } catch (t: Throwable) {
                val report = CrashReport.forThrowable(t, "Colliding entity with block")
                val blockCategory = report.addCategory("Block being collided with")
                CrashReportCategory.populateBlockDetails(blockCategory, level, pos, state)
                val entityCategory = report.addCategory("Entity being checked for collision")
                entity.fillCrashReportCategory(entityCategory)
                throw ReportedException(report)
            }
        }
        if (hitFluid) {
            collector.advanceStep(step)
            fluidState.entityInside(level, pos, entity, collector)
        }
        return true
    }

    internal fun borrowScratch(): Scratch {
        val thread = Thread.currentThread()
        val local: Scratch = if (thread is TickThread) {
            var existing = thread.insideBlockScratch as Scratch?
            if (existing == null) {
                existing = Scratch()
                thread.insideBlockScratch = existing
            }
            existing
        } else {
            threadScratch.get()
        }
        if (!local.inUse) {
            local.inUse = true
            local.resetVisited()
            return local
        }
        // Reentrant call (e.g. teleport/portal inside entityInside): isolate with a fresh scratch.
        return Scratch().also { it.resetVisited() }
    }

    internal fun releaseScratch(scratch: Scratch) {
        if (scratch.inUse) {
            scratch.inUse = false
        }
    }

    /**
     * Reusable per-thread state for Case C (block-boundary-crossing sweeps).
     * Padded with unused slots around hot mutable fields so adjacent workers' instances never share a 64-byte line.
     */
    internal class Scratch {
        @Suppress("unused") private var pad0 = 0L; @Suppress("unused") private var pad1 = 0L
        @Suppress("unused") private var pad2 = 0L; @Suppress("unused") private var pad3 = 0L
        @Suppress("unused") private var pad4 = 0L; @Suppress("unused") private var pad5 = 0L
        @Suppress("unused") private var pad6 = 0L; @Suppress("unused") private var pad7 = 0L

        var inUse: Boolean = false
        var gen: Int = 0
        var lastStep: Int = 0

        private var visitedCount: Int = 0
        private var overflowSet: LongOpenHashSet? = null
        val visitedGen: IntArray = IntArray(VISITED_CAPACITY)
        val visitedKey: LongArray = LongArray(VISITED_CAPACITY)
        val pos: BlockPos.MutableBlockPos = BlockPos.MutableBlockPos()

        // Active sweep parameters (cleared in endSweep so no world/entity reference is retained on worker threads).
        private var entity: Entity? = null
        private var level: Level? = null
        private var fromX: Double = 0.0
        private var fromY: Double = 0.0
        private var fromZ: Double = 0.0
        private var toX: Double = 0.0
        private var toY: Double = 0.0
        private var toZ: Double = 0.0
        private var fromVec: Vec3? = null
        private var toVec: Vec3? = null
        private var minX: Double = 0.0
        private var minY: Double = 0.0
        private var minZ: Double = 0.0
        private var maxX: Double = 0.0
        private var maxY: Double = 0.0
        private var maxZ: Double = 0.0
        private var movedFar: Boolean = false
        private var collector: InsideBlockEffectApplier.StepBasedCollector? = null
        private var visitedBlocks: LongSet? = null
        private var maxSteps: Int = 0
        private var fastRead: Boolean = false
        private var minYWorld: Int = 0
        private var maxYWorld: Int = 0
        private var minSectionY: Int = 0
        private var cachedCx: Int = Int.MIN_VALUE
        private var cachedCz: Int = Int.MIN_VALUE
        private var cachedChunk: LevelChunk? = null
        private var cachedSy: Int = Int.MIN_VALUE
        private var cachedSection: LevelChunkSection? = null

        @Suppress("unused") private var pad8 = 0L; @Suppress("unused") private var pad9 = 0L
        @Suppress("unused") private var pad10 = 0L; @Suppress("unused") private var pad11 = 0L
        @Suppress("unused") private var pad12 = 0L; @Suppress("unused") private var pad13 = 0L
        @Suppress("unused") private var pad14 = 0L; @Suppress("unused") private var pad15 = 0L

        fun resetVisited() {
            var next = gen + 1
            if (next == 0) {
                Arrays.fill(visitedGen, 0)
                next = 1
            }
            gen = next
            visitedCount = 0
            overflowSet?.clear()
        }

        /** Adds [packedPos] to the sweep's deduplication set; returns `true` iff it was not already present. */
        fun addVisited(packedPos: Long): Boolean {
            val overflow = overflowSet
            if (overflow != null && !overflow.isEmpty()) {
                return overflow.add(packedPos)
            }
            val g = gen
            var slot = mixLong(packedPos) and VISITED_MASK
            while (true) {
                if (visitedGen[slot] != g) {
                    if (visitedCount >= VISITED_MAX_LOAD) {
                        return spillAndAdd(packedPos)
                    }
                    visitedGen[slot] = g
                    visitedKey[slot] = packedPos
                    visitedCount++
                    return true
                }
                if (visitedKey[slot] == packedPos) return false
                slot = (slot + 1) and VISITED_MASK
            }
        }

        private fun spillAndAdd(packedPos: Long): Boolean {
            val set = overflowSet ?: LongOpenHashSet(VISITED_CAPACITY * 2).also { overflowSet = it }
            val g = gen
            for (i in 0 until VISITED_CAPACITY) {
                if (visitedGen[i] == g) {
                    set.add(visitedKey[i])
                }
            }
            return set.add(packedPos)
        }

        fun beginSweep(
            entity: Entity,
            level: Level,
            fromX: Double,
            fromY: Double,
            fromZ: Double,
            toX: Double,
            toY: Double,
            toZ: Double,
            fromVec: Vec3?,
            toVec: Vec3?,
            minX: Double,
            minY: Double,
            minZ: Double,
            maxX: Double,
            maxY: Double,
            maxZ: Double,
            movedFar: Boolean,
            collector: InsideBlockEffectApplier.StepBasedCollector,
            visitedBlocks: LongSet,
            maxSteps: Int,
        ) {
            this.entity = entity
            this.level = level
            this.fromX = fromX
            this.fromY = fromY
            this.fromZ = fromZ
            this.toX = toX
            this.toY = toY
            this.toZ = toZ
            this.fromVec = fromVec
            this.toVec = toVec
            this.minX = minX
            this.minY = minY
            this.minZ = minZ
            this.maxX = maxX
            this.maxY = maxY
            this.maxZ = maxZ
            this.movedFar = movedFar
            this.collector = collector
            this.visitedBlocks = visitedBlocks
            this.maxSteps = maxSteps
            this.lastStep = 0
            this.fastRead = !level.isDebug
            this.minYWorld = level.minY
            this.maxYWorld = level.maxY
            this.minSectionY = level.minSectionY
            this.cachedCx = Int.MIN_VALUE
            this.cachedCz = Int.MIN_VALUE
            this.cachedChunk = null
            this.cachedSy = Int.MIN_VALUE
            this.cachedSection = null
        }

        fun endSweep() {
            this.entity = null
            this.level = null
            this.fromVec = null
            this.toVec = null
            this.collector = null
            this.visitedBlocks = null
            this.cachedChunk = null
            this.cachedSection = null
        }

        /**
         * Visits all integer positions between `(x0, y0, z0)` and `(x1, y1, z1)` in `BlockPos.betweenCornersInDirection`
         * order. Returns `false` if traversal stopped early (entity died or `step >= maxSteps`).
         */
        fun visitBoxDirected(
            x0: Int,
            y0: Int,
            z0: Int,
            x1: Int,
            y1: Int,
            z1: Int,
            dx: Double,
            dy: Double,
            dz: Double,
            step: Int,
            dedupFirst: Boolean,
        ): Boolean {
            val minBx = if (x0 <= x1) x0 else x1
            val minBy = if (y0 <= y1) y0 else y1
            val minBz = if (z0 <= z1) z0 else z1
            val maxBx = if (x0 <= x1) x1 else x0
            val maxBy = if (y0 <= y1) y1 else y0
            val maxBz = if (z0 <= z1) z1 else z0

            val startX = if (dx >= 0.0) minBx else maxBx
            val startY = if (dy >= 0.0) minBy else maxBy
            val startZ = if (dz >= 0.0) minBz else maxBz
            val stepX = if (dx >= 0.0) 1 else -1
            val stepY = if (dy >= 0.0) 1 else -1
            val stepZ = if (dz >= 0.0) 1 else -1
            val countX = maxBx - minBx
            val countY = maxBy - minBy
            val countZ = maxBz - minBz
            val zBeforeX = abs(dx) < abs(dz)
            val secondCount = if (zBeforeX) countZ else countX
            val thirdCount = if (zBeforeX) countX else countZ

            var yi = 0
            while (yi <= countY) {
                val by = startY + yi * stepY
                var si = 0
                while (si <= secondCount) {
                    var ti = 0
                    while (ti <= thirdCount) {
                        val bx: Int
                        val bz: Int
                        if (zBeforeX) {
                            bz = startZ + si * stepZ
                            bx = startX + ti * stepX
                        } else {
                            bx = startX + si * stepX
                            bz = startZ + ti * stepZ
                        }
                        if (dedupFirst) {
                            if (addVisited(BlockPos.asLong(bx, by, bz))) {
                                if (!visitBlock(bx, by, bz, step)) return false
                            }
                        } else {
                            if (!visitBlock(bx, by, bz, step)) return false
                            addVisited(BlockPos.asLong(bx, by, bz))
                        }
                        ti++
                    }
                    si++
                }
                yi++
            }
            return true
        }

        private fun visitBlock(bx: Int, by: Int, bz: Int, step: Int): Boolean {
            val ent = entity!!
            if (!ent.isAlive || step >= maxSteps) return false
            lastStep = step
            if (by < minYWorld || by > maxYWorld) return true

            val lvl = level!!
            val state: BlockState
            if (fastRead) {
                val cx = bx shr 4
                val cz = bz shr 4
                if (cx != cachedCx || cz != cachedCz) {
                    cachedCx = cx
                    cachedCz = cz
                    cachedChunk = lvl.chunkSource.getChunkNow(cx, cz)
                    cachedSy = Int.MIN_VALUE
                    cachedSection = null
                }
                val chunk = cachedChunk
                if (chunk != null) {
                    val sy = (by shr 4) - minSectionY
                    if (sy != cachedSy) {
                        cachedSy = sy
                        val sections = chunk.sections
                        cachedSection = if (sy in sections.indices) sections[sy] else null
                    }
                    val section = cachedSection
                    if (section == null || section.hasOnlyAir()) return true
                    state = section.getBlockState(bx and 15, by and 15, bz and 15)
                    if (state.isAir) return true
                } else {
                    state = lvl.getBlockState(pos.set(bx, by, bz))
                    if (state.isAir) return true
                }
            } else {
                state = lvl.getBlockState(pos.set(bx, by, bz))
                if (state.isAir) return true
            }

            pos.set(bx, by, bz)
            val shape = state.getEntityInsideCollisionShape(lvl, pos, ent)
            val fluidState = state.fluidState
            if ((shape !== Shapes.block() && !shape.isEmpty) || !fluidState.isEmpty) {
                if (fromVec == null) fromVec = Vec3(fromX, fromY, fromZ)
                if (toVec == null) toVec = Vec3(toX, toY, toZ)
            }
            if (collideAndApply(
                    ent, lvl, pos, state, shape, fluidState, fromVec, toVec,
                    movedFar, minX, minY, minZ, maxX, maxY, maxZ, bx, by, bz, step, collector!!, visitedBlocks!!,
                )
            ) {
                cachedCx = Int.MIN_VALUE
                cachedSy = Int.MIN_VALUE
            }
            return true
        }

        companion object {
            const val VISITED_CAPACITY = 256
            const val VISITED_MASK = VISITED_CAPACITY - 1
            const val VISITED_MAX_LOAD = 160

            private fun mixLong(x: Long): Int {
                var h = x * -7046029254386353131L
                h = h xor (h ushr 32)
                return (h xor (h ushr 16)).toInt()
            }
        }
    }
}
