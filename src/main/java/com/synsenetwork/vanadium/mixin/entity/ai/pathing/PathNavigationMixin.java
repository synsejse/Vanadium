package com.synsenetwork.vanadium.mixin.entity.ai.pathing;

import com.synsenetwork.vanadium.tracking.NavigationIndex;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
    @Shadow @Final protected Mob mob;

    @Inject(method = {"tick", "stop", "recomputePath"}, at = @At("HEAD"))
    private void invalidateNavigation(CallbackInfo ci) {
        NavigationIndex.invalidate(mob);
    }

    @Inject(method = "moveTo(Lnet/minecraft/world/level/pathfinder/Path;D)Z", at = @At("HEAD"))
    private void invalidatePath(CallbackInfoReturnable<Boolean> cir) {
        NavigationIndex.invalidate(mob);
    }
}
