package com.synsenetwork.vanadium.mixin.world.level.pathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Target;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Synchronizes {@link PathFinder#findPath} per finder instance and eliminates per-search stream pipelines,
 * per-pop/per-neighbor {@code HashMap$KeyIterator} allocations, and O(N²) path reconstruction while preserving
 * exact target order, tie-breaking, float arithmetic and debug capture.
 */
@Mixin(PathFinder.class)
public abstract class PathFinderMixin {
    @Shadow @Final private Node[] neighbors;
    @Shadow private int maxVisitedNodes;
    @Shadow @Final private NodeEvaluator nodeEvaluator;
    @Shadow @Final private BinaryHeap openSet;
    @Shadow private BooleanSupplier captureDebug;

    @Shadow protected abstract float distance(Node from, Node to);

    /**
     * @author Vanadium
     * @reason Synchronize per-finder searches and remove stream/iterator/O(N²) reconstruction overhead with identical results.
     */
    @Overwrite
    public synchronized @Nullable Path findPath(
            PathNavigationRegion level, Mob entity, Set<BlockPos> targets, float maxPathLength, int reachRange, float maxVisitedNodesMultiplier) {
        this.openSet.clear();
        this.nodeEvaluator.prepare(level, entity);
        Node from = this.nodeEvaluator.getStart();
        if (from == null) {
            return null;
        }
        if (targets.isEmpty()) {
            this.nodeEvaluator.done();
            return null;
        }

        boolean captureDebug = this.captureDebug.getAsBoolean();
        Path path;
        if (targets.size() == 1 && !captureDebug) {
            BlockPos targetPos = targets.iterator().next();
            Target target = this.nodeEvaluator.getTarget(targetPos.getX(), targetPos.getY(), targetPos.getZ());
            path = this.vanadium$findSingleTarget(from, target, targetPos, maxPathLength, reachRange, maxVisitedNodesMultiplier);
        } else {
            Map<Target, BlockPos> targetMap = new HashMap<>();
            for (BlockPos pos : targets) {
                Target target = this.nodeEvaluator.getTarget(pos.getX(), pos.getY(), pos.getZ());
                if (targetMap.put(target, pos) != null) {
                    throw new IllegalStateException("Duplicate target key " + target);
                }
            }
            path = this.vanadium$findMultiTarget(from, targetMap, maxPathLength, reachRange, maxVisitedNodesMultiplier, captureDebug);
        }

        this.nodeEvaluator.done();
        return path;
    }

    @Unique
    private Path vanadium$findSingleTarget(
            Node from, Target target, BlockPos targetPos, float maxPathLength, int reachRange, float maxVisitedNodesMultiplier) {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("find_path");
        profiler.markForCharting(MetricCategory.PATH_FINDING);

        from.g = 0.0F;
        float startH = from.distanceTo(target);
        target.updateBest(startH, from);
        from.h = startH;
        from.f = startH;

        this.openSet.clear();
        this.openSet.insert(from);
        int count = 0;
        int maxVisitedNodesAdjusted = (int) (this.maxVisitedNodes * maxVisitedNodesMultiplier);
        boolean reached = false;

        while (!this.openSet.isEmpty()) {
            if (++count >= maxVisitedNodesAdjusted) {
                break;
            }

            Node current = this.openSet.pop();
            current.closed = true;

            if (current.distanceManhattan(target) <= reachRange) {
                target.setReached();
                reached = true;
                break;
            }

            if (!(current.distanceTo(from) >= maxPathLength)) {
                int neighborCount = this.nodeEvaluator.getNeighbors(this.neighbors, current);
                for (int i = 0; i < neighborCount; i++) {
                    Node neighbor = this.neighbors[i];
                    float distance = this.distance(current, neighbor);
                    neighbor.walkedDistance = current.walkedDistance + distance;
                    float tentativeGScore = current.g + distance + neighbor.costMalus;
                    if (neighbor.walkedDistance < maxPathLength && (!neighbor.inOpenSet() || tentativeGScore < neighbor.g)) {
                        neighbor.cameFrom = current;
                        neighbor.g = tentativeGScore;
                        float h = neighbor.distanceTo(target);
                        target.updateBest(h, neighbor);
                        neighbor.h = h * 1.5F;
                        if (neighbor.inOpenSet()) {
                            this.openSet.changeCost(neighbor, neighbor.g + neighbor.h);
                        } else {
                            neighbor.f = neighbor.g + neighbor.h;
                            this.openSet.insert(neighbor);
                        }
                    }
                }
            }
        }

        Path path = vanadium$reconstructPath(target.getBestNode(), targetPos, reached);
        profiler.pop();
        return path;
    }

    @Unique
    private @Nullable Path vanadium$findMultiTarget(
            Node from, Map<Target, BlockPos> targetMap, float maxPathLength, int reachRange, float maxVisitedNodesMultiplier, boolean captureDebug) {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("find_path");
        profiler.markForCharting(MetricCategory.PATH_FINDING);

        Set<Target> targets = targetMap.keySet();
        Target[] targetArray = targets.toArray(new Target[0]);
        int targetCount = targetArray.length;

        from.g = 0.0F;
        from.h = vanadium$getBestH(from, targetArray, targetCount);
        from.f = from.h;

        this.openSet.clear();
        this.openSet.insert(from);
        Set<Node> closedSet = captureDebug ? new HashSet<>() : Set.of();
        int count = 0;
        Set<Target> reachedTargets = HashSet.newHashSet(targetCount);
        int maxVisitedNodesAdjusted = (int) (this.maxVisitedNodes * maxVisitedNodesMultiplier);

        while (!this.openSet.isEmpty()) {
            if (++count >= maxVisitedNodesAdjusted) {
                break;
            }

            Node current = this.openSet.pop();
            current.closed = true;

            for (int t = 0; t < targetCount; t++) {
                Target target = targetArray[t];
                if (current.distanceManhattan(target) <= reachRange) {
                    target.setReached();
                    reachedTargets.add(target);
                }
            }

            if (!reachedTargets.isEmpty()) {
                break;
            }

            if (captureDebug) {
                closedSet.add(current);
            }

            if (!(current.distanceTo(from) >= maxPathLength)) {
                int neighborCount = this.nodeEvaluator.getNeighbors(this.neighbors, current);
                for (int i = 0; i < neighborCount; i++) {
                    Node neighbor = this.neighbors[i];
                    float distance = this.distance(current, neighbor);
                    neighbor.walkedDistance = current.walkedDistance + distance;
                    float tentativeGScore = current.g + distance + neighbor.costMalus;
                    if (neighbor.walkedDistance < maxPathLength && (!neighbor.inOpenSet() || tentativeGScore < neighbor.g)) {
                        neighbor.cameFrom = current;
                        neighbor.g = tentativeGScore;
                        neighbor.h = vanadium$getBestH(neighbor, targetArray, targetCount) * 1.5F;
                        if (neighbor.inOpenSet()) {
                            this.openSet.changeCost(neighbor, neighbor.g + neighbor.h);
                        } else {
                            neighbor.f = neighbor.g + neighbor.h;
                            this.openSet.insert(neighbor);
                        }
                    }
                }
            }
        }

        Path bestPath = null;
        if (!reachedTargets.isEmpty()) {
            int bestCount = Integer.MAX_VALUE;
            for (Target target : reachedTargets) {
                Path candidate = vanadium$reconstructPath(target.getBestNode(), targetMap.get(target), true);
                int nodeCount = candidate.getNodeCount();
                if (bestPath == null || nodeCount < bestCount) {
                    bestPath = candidate;
                    bestCount = nodeCount;
                }
            }
        } else {
            double bestDist = Double.POSITIVE_INFINITY;
            int bestCount = Integer.MAX_VALUE;
            for (int t = 0; t < targetCount; t++) {
                Target target = targetArray[t];
                Path candidate = vanadium$reconstructPath(target.getBestNode(), targetMap.get(target), false);
                double dist = candidate.getDistToTarget();
                int nodeCount = candidate.getNodeCount();
                if (bestPath == null) {
                    bestPath = candidate;
                    bestDist = dist;
                    bestCount = nodeCount;
                } else {
                    int cmp = Double.compare(dist, bestDist);
                    if (cmp < 0 || (cmp == 0 && nodeCount < bestCount)) {
                        bestPath = candidate;
                        bestDist = dist;
                        bestCount = nodeCount;
                    }
                }
            }
        }

        profiler.pop();
        if (bestPath == null) {
            return null;
        }
        if (captureDebug) {
            bestPath.setDebug(List.of(this.openSet.getHeap()), List.copyOf(closedSet), targets);
        }
        return bestPath;
    }

    @Unique
    private static float vanadium$getBestH(Node from, Target[] targets, int targetCount) {
        float bestH = Float.MAX_VALUE;
        for (int i = 0; i < targetCount; i++) {
            Target target = targets[i];
            float h = from.distanceTo(target);
            target.updateBest(h, from);
            if (h < bestH) {
                bestH = h;
            }
        }
        return bestH;
    }

    @Unique
    private static Path vanadium$reconstructPath(Node closest, BlockPos target, boolean reached) {
        List<Node> nodes = new ArrayList<>();
        Node node = closest;
        nodes.add(node);
        while (node.cameFrom != null) {
            node = node.cameFrom;
            nodes.add(node);
        }
        Collections.reverse(nodes);
        return new Path(nodes, target, reached);
    }
}
