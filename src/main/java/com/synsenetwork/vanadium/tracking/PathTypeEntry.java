package com.synsenetwork.vanadium.tracking;

import net.minecraft.world.level.pathfinder.PathType;

/** One immutable path-type cache slot; final fields make racy publication safe. */
public record PathTypeEntry(long key, PathType type) {}
