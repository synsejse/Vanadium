package com.synsenetwork.vanadium.mixin.world.level.pathfinder;

import com.synsenetwork.vanadium.navigation.NodeAccess;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Node.class)
public abstract class NodeMixin implements NodeAccess {
    @Mutable @Shadow @Final public int x;
    @Mutable @Shadow @Final public int y;
    @Mutable @Shadow @Final public int z;
    @Mutable @Shadow @Final private int hash;
    @Shadow public int heapIdx;
    @Shadow public float g;
    @Shadow public float h;
    @Shadow public float f;
    @Shadow public @Nullable Node cameFrom;
    @Shadow public boolean closed;
    @Shadow public float walkedDistance;
    @Shadow public float costMalus;
    @Shadow public PathType type;

    @Override
    public void vanadium$reset(int x, int y, int z, int hash) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.hash = hash;
        this.heapIdx = -1;
        this.g = 0.0F;
        this.h = 0.0F;
        this.f = 0.0F;
        this.cameFrom = null;
        this.closed = false;
        this.walkedDistance = 0.0F;
        this.costMalus = 0.0F;
        this.type = PathType.BLOCKED;
    }
}
