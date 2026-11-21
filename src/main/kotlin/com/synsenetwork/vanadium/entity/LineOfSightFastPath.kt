package com.synsenetwork.vanadium.entity

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes

/**
 * Allocation-free exact DDA raycast for `LivingEntity.hasLineOfSight`.
 *
 * Vanilla allocates two `Vec3` endpoints, `ClipContext`, `EntityCollisionContext`, a raycast hit factory,
 * `MutableBlockPos`, and on every unobstructed check (the common case) `BlockPos.containing` plus
 * `BlockHitResult.miss`. Because `hasLineOfSight` only asks whether any block or fluid clips the ray, this
 * helper executes the exact `BlockGetter.traverseBlocks` DDA in primitive registers, caches the active
 * `LevelChunk` and `LevelChunkSection` across consecutive steps, skips air and empty sections directly, tests
 * full `Shapes.block()` cubes without `Vec3`/`VoxelShape.findIndex` lambdas, and constructs `CollisionContext`
 * or `Vec3` lazily only when a non-air or partial block is actually encountered.
 */
object LineOfSightFastPath {
    private const val MAX_DISTANCE = 128.0
    private const val MAX_DISTANCE_SQR = MAX_DISTANCE * MAX_DISTANCE
    private const val MIN_CLIP_LENGTH_SQR = 1.0E-7
    private const val TRAVERSE_EPS = -1.0E-7

    private val defaultClipClass = object : ClassValue<Boolean>() {
        override fun computeValue(type: Class<*>): Boolean {
            return try {
                type.getMethod("clip", ClipContext::class.java).declaringClass == BlockGetter::class.java
            } catch (_: NoSuchMethodException) {
                false
            }
        }
    }

    @JvmStatic
    fun usesDefaultClip(level: Level): Boolean = defaultClipClass.get(level.javaClass)

    @JvmStatic
    fun hasLineOfSight(
        viewer: LivingEntity,
        level: Level,
        target: Entity,
        block: ClipContext.Block,
        fluid: ClipContext.Fluid,
        targetY: Double,
    ): Boolean {
        val fromX = viewer.x
        val fromY = viewer.eyeY
        val fromZ = viewer.z
        val toX = target.x
        val toY = targetY
        val toZ = target.z

        val rawDx = toX - fromX
        val rawDy = toY - fromY
        val rawDz = toZ - fromZ
        val distSqr = rawDx * rawDx + rawDy * rawDy + rawDz * rawDz
        if (distSqr > MAX_DISTANCE_SQR && Math.sqrt(distSqr) > MAX_DISTANCE) {
            return false
        }
        // BlockGetter.traverseBlocks misses when from.equals(to); VoxelShape.clip returns null when lengthSqr < 1e-7.
        if ((java.lang.Double.compare(fromX, toX) == 0 &&
                java.lang.Double.compare(fromY, toY) == 0 &&
                java.lang.Double.compare(fromZ, toZ) == 0) ||
            distSqr < MIN_CLIP_LENGTH_SQR
        ) {
            return true
        }

        val endX = Mth.lerp(TRAVERSE_EPS, toX, fromX)
        val endY = Mth.lerp(TRAVERSE_EPS, toY, fromY)
        val endZ = Mth.lerp(TRAVERSE_EPS, toZ, fromZ)
        val startX = Mth.lerp(TRAVERSE_EPS, fromX, toX)
        val startY = Mth.lerp(TRAVERSE_EPS, fromY, toY)
        val startZ = Mth.lerp(TRAVERSE_EPS, fromZ, toZ)

        var bx = Mth.floor(startX)
        var by = Mth.floor(startY)
        var bz = Mth.floor(startZ)

        val fastRead = !level.isDebug
        val minYWorld = level.minY
        val maxYWorld = level.maxY
        val minSectionY = level.minSectionY
        val chunkSource = level.chunkSource
        val checkFluids = fluid != ClipContext.Fluid.NONE

        var cachedCx = Int.MIN_VALUE
        var cachedCz = Int.MIN_VALUE
        var cachedChunk: LevelChunk? = null
        var cachedSy = Int.MIN_VALUE
        var cachedSection: LevelChunkSection? = null
        var pos: BlockPos.MutableBlockPos? = null
        var collisionContext: CollisionContext? = null
        var fromVec: Vec3? = null
        var toVec: Vec3? = null

        // Helper inline check for the initial voxel and each DDA step.
        val stepDx = endX - startX
        val stepDy = endY - startY
        val stepDz = endZ - startZ
        val signX = Mth.sign(stepDx)
        val signY = Mth.sign(stepDy)
        val signZ = Mth.sign(stepDz)
        val tDeltaX = if (signX == 0) Double.MAX_VALUE else signX.toDouble() / stepDx
        val tDeltaY = if (signY == 0) Double.MAX_VALUE else signY.toDouble() / stepDy
        val tDeltaZ = if (signZ == 0) Double.MAX_VALUE else signZ.toDouble() / stepDz
        var tMaxX = tDeltaX * if (signX > 0) 1.0 - Mth.frac(startX) else Mth.frac(startX)
        var tMaxY = tDeltaY * if (signY > 0) 1.0 - Mth.frac(startY) else Mth.frac(startY)
        var tMaxZ = tDeltaZ * if (signZ > 0) 1.0 - Mth.frac(startZ) else Mth.frac(startZ)

        var first = true
        while (true) {
            if (!first) {
                if (tMaxX > 1.0 && tMaxY > 1.0 && tMaxZ > 1.0) {
                    return true
                }
                if (tMaxX < tMaxY) {
                    if (tMaxX < tMaxZ) {
                        bx += signX
                        tMaxX += tDeltaX
                    } else {
                        bz += signZ
                        tMaxZ += tDeltaZ
                    }
                } else if (tMaxY < tMaxZ) {
                    by += signY
                    tMaxY += tDeltaY
                } else {
                    bz += signZ
                    tMaxZ += tDeltaZ
                }
            }
            first = false

            if (by < minYWorld || by > maxYWorld) continue
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
                    val sy = (by shr 4) - minSectionY
                    if (sy != cachedSy) {
                        cachedSy = sy
                        val sections = chunk.sections
                        cachedSection = if (sy >= 0 && sy < sections.size) sections[sy] else null
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
            val ctx = collisionContext ?: CollisionContext.of(viewer).also { collisionContext = it }
            val blockShape = block.get(state, level, mp, ctx)
            if (!blockShape.isEmpty) {
                if (blockShape === Shapes.block()) {
                    if (clipsFullCube(bx, by, bz, fromX, fromY, fromZ, rawDx, rawDy, rawDz)) {
                        return false
                    }
                } else {
                    val fv = fromVec ?: Vec3(fromX, fromY, fromZ).also { fromVec = it }
                    val tv = toVec ?: Vec3(toX, toY, toZ).also { toVec = it }
                    if (blockShape.clip(fv, tv, mp) != null) {
                        return false
                    }
                }
            }
            if (checkFluids) {
                val fluidState = state.fluidState
                if (!fluidState.isEmpty && fluid.canPick(fluidState)) {
                    val fluidShape = fluidState.getShape(level, mp)
                    if (!fluidShape.isEmpty) {
                        val fv = fromVec ?: Vec3(fromX, fromY, fromZ).also { fromVec = it }
                        val tv = toVec ?: Vec3(toX, toY, toZ).also { toVec = it }
                        if (fluidShape.clip(fv, tv, mp) != null) {
                            return false
                        }
                    }
                }
            }
        }
    }

    /**
     * Primitive equivalent of `Shapes.block().clip(from, to, pos) != null` (for rays with `lengthSqr >= 1e-7`).
     * Matches `VoxelShape.clip`'s `0.001` inset `isFullWide` check and `AABB.clip` slab test bit-for-bit.
     */
    internal fun clipsFullCube(
        bx: Int,
        by: Int,
        bz: Int,
        fromX: Double,
        fromY: Double,
        fromZ: Double,
        dx: Double,
        dy: Double,
        dz: Double,
    ): Boolean {
        val insetX = (fromX + dx * 0.001) - bx.toDouble()
        val insetY = (fromY + dy * 0.001) - by.toDouble()
        val insetZ = (fromZ + dz * 0.001) - bz.toDouble()
        if (insetX >= 0.0 && insetX < 1.0 &&
            insetY >= 0.0 && insetY < 1.0 &&
            insetZ >= 0.0 && insetZ < 1.0
        ) {
            return true
        }
        return !InsideBlockFastPath.clipUnitBox(bx, by, bz, fromX, fromY, fromZ, dx, dy, dz).isNaN()
    }
}
