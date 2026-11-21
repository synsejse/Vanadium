package com.synsenetwork.vanadium.mixin.world.level.pathfinder;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NodeEvaluator.class)
public abstract class NodeEvaluatorMixin {
    @Shadow @Final protected Int2ObjectMap<Node> nodes;

    /**
     * @author Vanadium
     * @reason Avoid capturing lambda allocation in {@code computeIfAbsent} while preserving {@link Node#createHash} identity.
     */
    @Overwrite
    protected Node getNode(int x, int y, int z) {
        int hash = Node.createHash(x, y, z);
        Node node = this.nodes.get(hash);
        if (node == null) {
            node = new Node(x, y, z);
            this.nodes.put(hash, node);
        }
        return node;
    }
}
