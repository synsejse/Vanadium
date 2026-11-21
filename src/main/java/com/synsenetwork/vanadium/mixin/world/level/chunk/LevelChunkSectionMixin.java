package com.synsenetwork.vanadium.mixin.world.level.chunk;

import com.synsenetwork.vanadium.navigation.PathfindingFastPath;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures Lithium's block-state pathing and flag caches are initialized before Lithium's {@code initAirSection}
 * evaluates {@code PATH_NOT_OPEN} on {@code Blocks.AIR}. Without this, air sections constructed before the first
 * {@code setBlockState} (including newly generated spawn chunks) observe an uninitialized {@code null} neighbor path
 * type on air and stamp {@code countsByFlag[PATH_NOT_OPEN] = 4096} permanently, defeating safe-section skipping.
 */
@Mixin(value = LevelChunkSection.class, priority = 900)
public abstract class LevelChunkSectionMixin {
    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/PalettedContainerFactory;)V", at = @At("RETURN"))
    private void ensureLithiumBlockFlagsInitialized(PalettedContainerFactory factory, CallbackInfo ci) {
        PathfindingFastPath.ensureLithiumBlockFlagsInitialized();
    }
}
