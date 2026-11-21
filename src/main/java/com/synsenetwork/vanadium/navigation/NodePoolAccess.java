package com.synsenetwork.vanadium.navigation;

import org.jetbrains.annotations.Nullable;

/** Controls per-search pooled node allocation on a {@link net.minecraft.world.level.pathfinder.NodeEvaluator}. */
public interface NodePoolAccess {
    void vanadium$beginNodePool();

    void vanadium$endNodePool();

    PathfindingFastPath.@Nullable WalkScratch vanadium$nodeScratch();
}
