package com.synsenetwork.vanadium.mixin.world.level.pathfinder;

import com.synsenetwork.vanadium.navigation.NodePoolAccess;
import com.synsenetwork.vanadium.navigation.PathfindingFastPath;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(NodeEvaluator.class)
public abstract class NodeEvaluatorMixin implements NodePoolAccess {
    @Shadow @Final protected Int2ObjectMap<Node> nodes;

    @Unique
    private @Nullable PathfindingFastPath.WalkScratch vanadium$nodeScratch;

    @Override
    public void vanadium$beginNodePool() {
        if (this.vanadium$nodeScratch != null) {
            PathfindingFastPath.releaseWalkScratch(this.vanadium$nodeScratch);
        }
        this.vanadium$nodeScratch = PathfindingFastPath.borrowWalkScratch();
    }

    @Override
    public void vanadium$endNodePool() {
        PathfindingFastPath.releaseWalkScratch(this.vanadium$nodeScratch);
        this.vanadium$nodeScratch = null;
    }

    @Override
    public PathfindingFastPath.@Nullable WalkScratch vanadium$nodeScratch() {
        return this.vanadium$nodeScratch;
    }

    /**
     * @author Vanadium
     * @reason Use the per-thread generation-stamped node table and pool during {@code PathFinder.findPath} (when debug
     *         capture is off), and avoid {@code computeIfAbsent} lambda allocation on the unpooled fallback path.
     */
    @Overwrite
    protected Node getNode(int x, int y, int z) {
        PathfindingFastPath.WalkScratch scratch = this.vanadium$nodeScratch;
        if (scratch != null) {
            return scratch.getOrCreateNode(x, y, z);
        }
        int hash = Node.createHash(x, y, z);
        Node node = this.nodes.get(hash);
        if (node == null) {
            node = new Node(x, y, z);
            this.nodes.put(hash, node);
        }
        return node;
    }
}
