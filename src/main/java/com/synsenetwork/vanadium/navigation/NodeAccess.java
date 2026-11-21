package com.synsenetwork.vanadium.navigation;

/** Resets a pooled {@link net.minecraft.world.level.pathfinder.Node} to a fresh coordinate and hash. */
public interface NodeAccess {
    void vanadium$reset(int x, int y, int z, int hash);
}
