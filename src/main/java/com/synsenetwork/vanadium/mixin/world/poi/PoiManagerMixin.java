package com.synsenetwork.vanadium.mixin.world.poi;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.PoiDistanceAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PoiManager.class)
public abstract class PoiManagerMixin {
    @Shadow
    @SuppressWarnings("unused")
    private PoiManager.DistanceTracker distanceTracker;

    /**
     * Mobs ask this every tick (village movement goals). Vanilla runs the tracker's pending updates
     * first, which is a synchronized pass; with no pending work only the concurrent level map is read.
     */
    @WrapMethod(method = "sectionsToVillage")
    private int sectionsToVillageWithoutIdleUpdates(SectionPos sectionPos, Operation<Integer> original) {
        PoiDistanceAccess tracker = (PoiDistanceAccess) (Object) distanceTracker;
        if (!tracker.vanadium$hasPendingUpdates()) {
            return tracker.vanadium$storedLevel(sectionPos.asLong());
        }
        return original.call(sectionPos);
    }
}
