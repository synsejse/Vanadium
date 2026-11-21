package com.synsenetwork.vanadium.mixin.world.level.pathfinder;

import com.synsenetwork.vanadium.navigation.PathfindingFastPath;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import java.util.Arrays;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exact per-search optimizations for {@link WalkNodeEvaluator}:
 * - Generation-stamped, spatial direct-mapped L1-sized caches for per-search path types and floor levels
 *   (zero per-search {@code Arrays.fill}, zero capturing lambdas, zero hash-probing loops).
 * - Per-search {@code float[27]} malus snapshot (loaded lazily after {@code prepare} so subclasses like
 *   {@code AmphibiousNodeEvaluator} that adjust maluses in {@code prepare} are reflected) and cached {@code maxUpStep}.
 * - Allocation-free {@code getPathTypeOfMob} (1x1x1, 1x2x1 and ordinal bitmask paths) that reuses the already-evaluated
 *   origin cell instead of calling {@code getPathType(context, x, y, z)} a second time on every walkable cell.
 * - Subclass overrides of {@code getPathType}, {@code getPathTypeWithinMobBB} and {@code getFloorLevel} keep virtual dispatch.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {
    @Unique private static final int VANADIUM$FLOOR_CACHE_SIZE = 1024;
    @Unique private static final int VANADIUM$FLOOR_CACHE_MASK = VANADIUM$FLOOR_CACHE_SIZE - 1;
    @Unique private static final int VANADIUM$TYPE_CACHE_SIZE = 2048;
    @Unique private static final int VANADIUM$TYPE_CACHE_MASK = VANADIUM$TYPE_CACHE_SIZE - 1;

    @Unique
    private static final ClassValue<Boolean> VANADIUM$DEFAULT_MOB_BB = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getPathTypeWithinMobBB", PathfindingContext.class, int.class, int.class, int.class)
                        .getDeclaringClass() == WalkNodeEvaluator.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };

    @Unique
    private static final ClassValue<Boolean> VANADIUM$DEFAULT_FLOOR_LEVEL = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            Class<?> current = type;
            while (current != null && current != WalkNodeEvaluator.class) {
                try {
                    current.getDeclaredMethod("getFloorLevel", BlockPos.class);
                    return false;
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                }
            }
            return true;
        }
    };

    @Shadow @Final private Object2BooleanMap<AABB> collisionCache;
    @Shadow @Final private Node[] reusableNeighbors;

    @Shadow protected abstract boolean isAmphibious();
    @Shadow protected abstract boolean isNeighborValid(@Nullable Node neighbor, Node current);
    @Shadow protected abstract boolean isDiagonalValid(Node pos, @Nullable Node ew, @Nullable Node ns);
    @Shadow protected abstract boolean isDiagonalValid(@Nullable Node diagonal);
    @Shadow public abstract Set<PathType> getPathTypeWithinMobBB(PathfindingContext context, int x, int y, int z);

    @Shadow private static boolean doesBlockHavePartialCollision(PathType type) { throw new AssertionError(); }
    @Shadow private boolean canReachWithoutCollision(Node posTo) { throw new AssertionError(); }
    @Shadow private Node getNodeAndUpdateCostToMax(int x, int y, int z, PathType pathType, float cost) { throw new AssertionError(); }
    @Shadow private Node getClosedNode(int x, int y, int z, PathType pathType) { throw new AssertionError(); }
    @Shadow private @Nullable Node tryJumpOn(int x, int y, int z, int jumpSize, double nodeHeight, Direction travelDirection, PathType blockPathTypeCurrent, BlockPos.MutableBlockPos reusablePos) { throw new AssertionError(); }
    @Shadow private @Nullable Node tryFindFirstNonWaterBelow(int x, int y, int z, @Nullable Node best) { throw new AssertionError(); }
    @Shadow private Node tryFindFirstGroundNodeBelow(int x, int y, int z) { throw new AssertionError(); }

    // Generation-stamped spatial direct-mapped caches: bumping vanadium$gen invalidates all slots in O(1).
    @Unique private final int[] vanadium$floorGen = new int[VANADIUM$FLOOR_CACHE_SIZE];
    @Unique private final long[] vanadium$floorKey = new long[VANADIUM$FLOOR_CACHE_SIZE];
    @Unique private final double[] vanadium$floorVal = new double[VANADIUM$FLOOR_CACHE_SIZE];

    @Unique private final int[] vanadium$typeGen = new int[VANADIUM$TYPE_CACHE_SIZE];
    @Unique private final long[] vanadium$typeKey = new long[VANADIUM$TYPE_CACHE_SIZE];
    @Unique private final PathType[] vanadium$typeVal = new PathType[VANADIUM$TYPE_CACHE_SIZE];

    @Unique private final float[] vanadium$malus = new float[PathfindingFastPath.PATH_TYPES.length];
    @Unique private final BlockPos.MutableBlockPos vanadium$pos = new BlockPos.MutableBlockPos();
    @Unique private int vanadium$gen;
    @Unique private double vanadium$mobJumpHeight = 1.125;
    @Unique private int vanadium$jumpSize = 1;
    @Unique private boolean vanadium$prepared;
    @Unique private boolean vanadium$malusLoaded;
    @Unique private boolean vanadium$exactWalkClass;
    @Unique private boolean vanadium$usesDefaultMobBB;
    @Unique private boolean vanadium$usesDefaultFloorLevel;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initVanadiumState(CallbackInfo ci) {
        Class<?> selfClass = this.getClass();
        this.vanadium$exactWalkClass = selfClass == WalkNodeEvaluator.class;
        this.vanadium$usesDefaultMobBB = this.vanadium$exactWalkClass || VANADIUM$DEFAULT_MOB_BB.get(selfClass);
        this.vanadium$usesDefaultFloorLevel = this.vanadium$exactWalkClass || VANADIUM$DEFAULT_FLOOR_LEVEL.get(selfClass);
    }

    @Inject(method = "prepare", at = @At("TAIL"))
    private void prepareVanadiumState(PathNavigationRegion level, Mob entity, CallbackInfo ci) {
        float step = entity.maxUpStep();
        this.vanadium$mobJumpHeight = Math.max(1.125, step);
        this.vanadium$jumpSize = Mth.floor(Math.max(1.0F, step));
        this.vanadium$bumpGen();
        this.vanadium$malusLoaded = false;
        this.vanadium$prepared = true;
    }

    @Inject(method = "done", at = @At("HEAD"))
    private void clearVanadiumState(CallbackInfo ci) {
        this.vanadium$prepared = false;
        this.vanadium$malusLoaded = false;
    }

    @Unique
    private void vanadium$bumpGen() {
        int next = this.vanadium$gen + 1;
        if (next == 0) {
            Arrays.fill(this.vanadium$floorGen, 0);
            Arrays.fill(this.vanadium$typeGen, 0);
            next = 1;
        }
        this.vanadium$gen = next;
    }

    @Unique
    private float[] vanadium$malusTable(Mob mob) {
        if (!this.vanadium$malusLoaded) {
            PathType[] types = PathfindingFastPath.PATH_TYPES;
            float[] table = this.vanadium$malus;
            for (int i = 0; i < types.length; i++) {
                table[i] = mob.getPathfindingMalus(types[i]);
            }
            this.vanadium$malusLoaded = true;
        }
        return this.vanadium$malus;
    }

    @Unique
    private float vanadium$malusOf(Mob mob, PathType type) {
        return this.vanadium$prepared && mob == this.mob
                ? this.vanadium$malusTable(mob)[type.ordinal()]
                : mob.getPathfindingMalus(type);
    }

    /**
     * @author Vanadium
     * @reason Avoid iterator and {@code BlockPos} allocations and reuse cached {@code maxUpStep}, maluses and floor level.
     */
    @Overwrite
    public int getNeighbors(Node[] neighbors, Node pos) {
        int p = 0;
        int jumpSize = 0;
        PathType blockPathTypeAbove = this.getCachedPathType(pos.x, pos.y + 1, pos.z);
        PathType blockPathTypeCurrent = this.getCachedPathType(pos.x, pos.y, pos.z);
        if (this.vanadium$malusOf(this.mob, blockPathTypeAbove) >= 0.0F && blockPathTypeCurrent != PathType.STICKY_HONEY) {
            jumpSize = this.vanadium$prepared ? this.vanadium$jumpSize : Mth.floor(Math.max(1.0F, this.mob.maxUpStep()));
        }

        double posHeight = this.vanadium$usesDefaultFloorLevel
                ? this.vanadium$floorLevel(pos.x, pos.y, pos.z)
                : this.getFloorLevel(new BlockPos(pos.x, pos.y, pos.z));

        Direction[] horizontals = PathfindingFastPath.HORIZONTAL_DIRECTIONS;
        for (int i = 0; i < 4; i++) {
            Direction direction = horizontals[i];
            Node node = this.findAcceptedNode(
                    pos.x + direction.getStepX(), pos.y, pos.z + direction.getStepZ(),
                    jumpSize, posHeight, direction, blockPathTypeCurrent);
            this.reusableNeighbors[direction.get2DDataValue()] = node;
            if (this.isNeighborValid(node, pos)) {
                neighbors[p++] = node;
            }
        }

        for (int i = 0; i < 4; i++) {
            Direction direction = horizontals[i];
            Direction secondDirection = direction.getClockWise();
            if (this.isDiagonalValid(pos, this.reusableNeighbors[direction.get2DDataValue()], this.reusableNeighbors[secondDirection.get2DDataValue()])) {
                Node diagonalNode = this.findAcceptedNode(
                        pos.x + direction.getStepX() + secondDirection.getStepX(),
                        pos.y,
                        pos.z + direction.getStepZ() + secondDirection.getStepZ(),
                        jumpSize,
                        posHeight,
                        direction,
                        blockPathTypeCurrent);
                if (this.isDiagonalValid(diagonalNode)) {
                    neighbors[p++] = diagonalNode;
                }
            }
        }

        return p;
    }

    /**
     * @author Vanadium
     * @reason Reuse per-search floor-level cache and avoid {@code BlockPos.below()} allocation.
     */
    @Overwrite
    protected double getFloorLevel(BlockPos pos) {
        return this.vanadium$floorLevel(pos.getX(), pos.getY(), pos.getZ());
    }

    @Unique
    private double vanadium$floorLevel(int x, int y, int z) {
        if (this.vanadium$prepared) {
            int idx = (((x & 31) | ((z & 31) << 5)) ^ ((y & 7) << 7)) & VANADIUM$FLOOR_CACHE_MASK;
            long key = BlockPos.asLong(x, y, z);
            int gen = this.vanadium$gen;
            if (this.vanadium$floorGen[idx] == gen && this.vanadium$floorKey[idx] == key) {
                return this.vanadium$floorVal[idx];
            }
            double computed = PathfindingFastPath.computeFloorLevel(
                    this.currentContext.level(), x, y, z, this.canFloat() || this.isAmphibious(), this.vanadium$pos);
            this.vanadium$floorGen[idx] = gen;
            this.vanadium$floorKey[idx] = key;
            this.vanadium$floorVal[idx] = computed;
            return computed;
        }
        return PathfindingFastPath.computeFloorLevel(
                this.currentContext.level(), x, y, z, this.canFloat() || this.isAmphibious(), this.vanadium$pos);
    }

    /**
     * @author Vanadium
     * @reason Avoid per-call {@code MutableBlockPos} allocation and use cached floor level and malus table.
     */
    @Overwrite
    protected @Nullable Node findAcceptedNode(
            int x, int y, int z, int jumpSize, double nodeHeight, Direction travelDirection, PathType blockPathTypeCurrent) {
        Node best = null;
        double maxYTarget = this.vanadium$usesDefaultFloorLevel
                ? this.vanadium$floorLevel(x, y, z)
                : this.getFloorLevel(this.vanadium$pos.set(x, y, z));
        if (maxYTarget - nodeHeight > this.getMobJumpHeight()) {
            return null;
        }

        PathType pathType = this.getCachedPathType(x, y, z);
        float pathCost = this.vanadium$malusOf(this.mob, pathType);
        if (pathCost >= 0.0F) {
            best = this.getNodeAndUpdateCostToMax(x, y, z, pathType, pathCost);
        }

        if (doesBlockHavePartialCollision(blockPathTypeCurrent) && best != null && best.costMalus >= 0.0F && !this.canReachWithoutCollision(best)) {
            best = null;
        }

        if (pathType != PathType.WALKABLE && (!this.isAmphibious() || pathType != PathType.WATER)) {
            if ((best == null || best.costMalus < 0.0F)
                    && jumpSize > 0
                    && (pathType != PathType.FENCE || this.canWalkOverFences())
                    && pathType != PathType.UNPASSABLE_RAIL
                    && pathType != PathType.TRAPDOOR
                    && pathType != PathType.POWDER_SNOW) {
                best = this.tryJumpOn(x, y, z, jumpSize, nodeHeight, travelDirection, blockPathTypeCurrent, this.vanadium$pos);
            } else if (!this.isAmphibious() && pathType == PathType.WATER && !this.canFloat()) {
                best = this.tryFindFirstNonWaterBelow(x, y, z, best);
            } else if (pathType == PathType.OPEN) {
                best = this.tryFindFirstGroundNodeBelow(x, y, z);
            } else if (doesBlockHavePartialCollision(pathType) && best == null) {
                best = this.getClosedNode(x, y, z, pathType);
            }

            return best;
        } else {
            return best;
        }
    }

    /**
     * @author Vanadium
     * @reason Reuse {@code maxUpStep} captured in {@code prepare} instead of querying mob attributes on every neighbor probe.
     */
    @Overwrite
    private double getMobJumpHeight() {
        return this.vanadium$prepared ? this.vanadium$mobJumpHeight : Math.max(1.125, this.mob.maxUpStep());
    }

    /**
     * @author Vanadium
     * @reason Avoid capturing lambda allocation in {@code computeIfAbsent}.
     */
    @Overwrite
    private boolean hasCollisions(AABB aabb) {
        if (this.collisionCache.containsKey(aabb)) {
            return this.collisionCache.getBoolean(aabb);
        }
        boolean collided = !this.currentContext.level().noCollision(this.mob, aabb);
        this.collisionCache.put(aabb, collided);
        return collided;
    }

    /**
     * @author Vanadium
     * @reason Use a generation-stamped spatial direct-mapped cache instead of {@code Long2ObjectOpenHashMap.computeIfAbsent}.
     */
    @Overwrite
    protected PathType getCachedPathType(int x, int y, int z) {
        if (this.vanadium$prepared) {
            int idx = (((x & 31) | ((z & 31) << 5) | ((y & 1) << 10)) ^ (((y >> 1) & 7) << 7)) & VANADIUM$TYPE_CACHE_MASK;
            long key = BlockPos.asLong(x, y, z);
            int gen = this.vanadium$gen;
            if (this.vanadium$typeGen[idx] == gen && this.vanadium$typeKey[idx] == key) {
                return this.vanadium$typeVal[idx];
            }
            PathType computed = this.getPathTypeOfMob(this.currentContext, x, y, z, this.mob);
            this.vanadium$typeGen[idx] = gen;
            this.vanadium$typeKey[idx] = key;
            this.vanadium$typeVal[idx] = computed;
            return computed;
        }
        return this.getPathTypeOfMob(this.currentContext, x, y, z, this.mob);
    }

    /**
     * @author Vanadium
     * @reason Eliminate {@code EnumSet} allocation and the redundant second {@code getPathType(context, x, y, z)} evaluation
     *         on every multi-type cell while preserving exact vanilla precedence, ordinal tie-breaking and subclass dispatch.
     */
    @Overwrite
    public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
        if (!this.vanadium$usesDefaultMobBB || this.entityWidth <= 0 || this.entityHeight <= 0 || this.entityDepth <= 0) {
            return this.vanadium$getPathTypeOfMobFallback(context, x, y, z, mob);
        }

        boolean canPassDoors = this.canPassDoors();
        boolean canOpenDoors = this.canOpenDoors();

        // Fast path for 1x2x1 mobs (adult villagers, zombies, skeletons, creepers, etc.).
        if (this.entityWidth == 1 && this.entityHeight == 2 && this.entityDepth == 1) {
            PathType raw0 = this.vanadium$evalCellPathType(context, x, y, z);
            PathType t0 = this.vanadium$adjustCellType(context, raw0, canPassDoors, canOpenDoors);
            PathType raw1 = this.vanadium$evalCellPathType(context, x, y + 1, z);
            PathType t1 = this.vanadium$adjustCellType(context, raw1, canPassDoors, canOpenDoors);
            if (t0 == t1) {
                return t0;
            }
            if (t0 == PathType.FENCE || t1 == PathType.FENCE) {
                return PathType.FENCE;
            }
            if (t0 == PathType.UNPASSABLE_RAIL || t1 == PathType.UNPASSABLE_RAIL) {
                return PathType.UNPASSABLE_RAIL;
            }
            PathType first;
            PathType second;
            if (t0.ordinal() < t1.ordinal()) {
                first = t0;
                second = t1;
            } else {
                first = t1;
                second = t0;
            }
            PathType highestType = PathType.BLOCKED;
            float highestMalus = this.vanadium$malusOf(mob, PathType.BLOCKED);

            float m1 = this.vanadium$malusOf(mob, first);
            if (m1 < 0.0F) {
                return first;
            }
            if (m1 >= highestMalus) {
                highestMalus = m1;
                highestType = first;
            }

            float m2 = this.vanadium$malusOf(mob, second);
            if (m2 < 0.0F) {
                return second;
            }
            if (m2 >= highestMalus) {
                highestMalus = m2;
                highestType = second;
            }

            return raw0 == PathType.OPEN && highestType != PathType.OPEN && highestMalus == 0.0F
                    ? PathType.OPEN
                    : highestType;
        }

        // Fast path for 1x1x1 mobs (baby villagers, chickens, rabbits, etc.).
        if (this.entityWidth == 1 && this.entityHeight == 1 && this.entityDepth == 1) {
            PathType raw0 = this.vanadium$evalCellPathType(context, x, y, z);
            return this.vanadium$adjustCellType(context, raw0, canPassDoors, canOpenDoors);
        }

        // General footprint path using an ordinal bitmask (identical iteration order to EnumSet<PathType>).
        long mask = 0L;
        PathType rawOrigin = null;
        int w = this.entityWidth;
        int h = this.entityHeight;
        int d = this.entityDepth;
        for (int dx = 0; dx < w; dx++) {
            int xx = x + dx;
            for (int dy = 0; dy < h; dy++) {
                int yy = y + dy;
                for (int dz = 0; dz < d; dz++) {
                    PathType raw = this.vanadium$evalCellPathType(context, xx, yy, z + dz);
                    if (dx == 0 && dy == 0 && dz == 0) {
                        rawOrigin = raw;
                    }
                    PathType adjusted = this.vanadium$adjustCellType(context, raw, canPassDoors, canOpenDoors);
                    mask |= 1L << adjusted.ordinal();
                }
            }
        }

        PathType[] types = PathfindingFastPath.PATH_TYPES;
        if ((mask & (mask - 1L)) == 0L) {
            return types[Long.numberOfTrailingZeros(mask)];
        }
        if ((mask & PathfindingFastPath.FENCE_BIT) != 0L) {
            return PathType.FENCE;
        }
        if ((mask & PathfindingFastPath.UNPASSABLE_RAIL_BIT) != 0L) {
            return PathType.UNPASSABLE_RAIL;
        }

        PathType highestType = PathType.BLOCKED;
        float highestMalus = this.vanadium$malusOf(mob, PathType.BLOCKED);
        long remaining = mask;
        while (remaining != 0L) {
            int ord = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            PathType pathType = types[ord];
            float malus = this.vanadium$malusOf(mob, pathType);
            if (malus < 0.0F) {
                return pathType;
            }
            if (malus >= highestMalus) {
                highestMalus = malus;
                highestType = pathType;
            }
        }

        if (this.entityWidth > 1) {
            boolean isCurrentNodeCheaper = this.vanadium$malusOf(mob, rawOrigin) < highestMalus;
            boolean capMalusDueToCheapNode = isCurrentNodeCheaper && this.vanadium$malusOf(mob, PathType.BIG_MOBS_CLOSE_TO_DANGER) < highestMalus;
            return capMalusDueToCheapNode ? PathType.BIG_MOBS_CLOSE_TO_DANGER : highestType;
        } else {
            return rawOrigin == PathType.OPEN && highestType != PathType.OPEN && highestMalus == 0.0F
                    ? PathType.OPEN
                    : highestType;
        }
    }

    @Unique
    private PathType vanadium$evalCellPathType(PathfindingContext context, int x, int y, int z) {
        return this.vanadium$exactWalkClass
                ? PathfindingFastPath.getPathTypeStatic(context, x, y, z)
                : this.getPathType(context, x, y, z);
    }

    @Unique
    private PathType vanadium$adjustCellType(PathfindingContext context, PathType blockType, boolean canPassDoors, boolean canOpenDoors) {
        if (blockType == PathType.DOOR_WOOD_CLOSED && canOpenDoors && canPassDoors) {
            return PathType.WALKABLE_DOOR;
        }
        if (blockType == PathType.DOOR_OPEN && !canPassDoors) {
            return PathType.BLOCKED;
        }
        if (blockType == PathType.RAIL) {
            BlockPos mobPosition = this.mob.blockPosition();
            if (this.vanadium$evalCellPathType(context, mobPosition.getX(), mobPosition.getY(), mobPosition.getZ()) != PathType.RAIL
                    && this.vanadium$evalCellPathType(context, mobPosition.getX(), mobPosition.getY() - 1, mobPosition.getZ()) != PathType.RAIL) {
                return PathType.UNPASSABLE_RAIL;
            }
        }
        return blockType;
    }

    @Unique
    private PathType vanadium$getPathTypeOfMobFallback(PathfindingContext context, int x, int y, int z, Mob mob) {
        Set<PathType> blockTypes = this.getPathTypeWithinMobBB(context, x, y, z);
        if (blockTypes.size() == 1) {
            return blockTypes.iterator().next();
        }
        if (blockTypes.contains(PathType.FENCE)) {
            return PathType.FENCE;
        }
        if (blockTypes.contains(PathType.UNPASSABLE_RAIL)) {
            return PathType.UNPASSABLE_RAIL;
        }
        PathType highestMalusPathTypeWithinBB = PathType.BLOCKED;
        float highestMalusWithinBB = mob.getPathfindingMalus(highestMalusPathTypeWithinBB);
        for (PathType pathType : blockTypes) {
            float malusForPathType = mob.getPathfindingMalus(pathType);
            if (malusForPathType < 0.0F) {
                return pathType;
            }
            if (malusForPathType >= highestMalusWithinBB) {
                highestMalusWithinBB = malusForPathType;
                highestMalusPathTypeWithinBB = pathType;
            }
        }
        PathType currentNodePathType = this.getPathType(context, x, y, z);
        if (this.entityWidth > 1) {
            boolean isCurrentNodeCheaper = mob.getPathfindingMalus(currentNodePathType) < highestMalusWithinBB;
            boolean capMalusDueToCheapNode = isCurrentNodeCheaper && mob.getPathfindingMalus(PathType.BIG_MOBS_CLOSE_TO_DANGER) < highestMalusWithinBB;
            return capMalusDueToCheapNode ? PathType.BIG_MOBS_CLOSE_TO_DANGER : highestMalusPathTypeWithinBB;
        } else {
            return currentNodePathType == PathType.OPEN && highestMalusPathTypeWithinBB != PathType.OPEN && highestMalusWithinBB == 0.0F
                    ? PathType.OPEN
                    : highestMalusPathTypeWithinBB;
        }
    }

    /**
     * @author Vanadium
     * @reason Avoid per-call {@code MutableBlockPos} allocation and use cross-section safe neighbor checks.
     */
    @Overwrite
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        return PathfindingFastPath.getPathTypeStatic(context, x, y, z);
    }
}
