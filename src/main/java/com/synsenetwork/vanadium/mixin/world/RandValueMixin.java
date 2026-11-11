package com.synsenetwork.vanadium.mixin.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marker: {@code SynchronisePlugin} rewrites every {@code randValue} access in these classes, including Lithium's
 * random-tick code, to {@code PerThreadRandValue}. The rewrite runs after every mixin has been applied.
 */
@Mixin(value = {Level.class, ServerLevel.class}, priority = 2000)
public class RandValueMixin {
}
