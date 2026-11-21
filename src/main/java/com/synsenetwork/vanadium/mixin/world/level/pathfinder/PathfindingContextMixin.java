package com.synsenetwork.vanadium.mixin.world.level.pathfinder;

import com.synsenetwork.vanadium.navigation.PathfindingFastPath;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs before Fabric API's default-priority {@code LandPathTypeRegistry} hook so that when Lithium's block-state cache
 * has the exact raw path type and no custom provider covers the block, lookups skip both the redundant registry check
 * and cross-socket writes to the shared level {@code PathTypeCache}.
 */
@Mixin(value = PathfindingContext.class, priority = 900)
public abstract class PathfindingContextMixin {
    @Shadow @Final private CollisionGetter level;

    @Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
    private void fastGetPathTypeFromState(int x, int y, int z, CallbackInfoReturnable<PathType> cir) {
        PathType fast = PathfindingFastPath.fastPathTypeFromState(this.level, x, y, z);
        if (fast != null) {
            cir.setReturnValue(fast);
        }
    }
}
