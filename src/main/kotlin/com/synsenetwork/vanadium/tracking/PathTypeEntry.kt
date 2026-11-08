package com.synsenetwork.vanadium.tracking

import net.minecraft.world.level.pathfinder.PathType

/** One immutable path-type cache slot; final fields make racy publication safe. */
class PathTypeEntry(@JvmField val key: Long, @JvmField val type: PathType)
