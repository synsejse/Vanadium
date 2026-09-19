package com.synsenetwork.vanadium.featurebench.mixin;

import com.synsenetwork.vanadium.featurebench.FeatureBench;
import com.synsenetwork.vanadium.tracking.NearbyTrackers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Select the existing inline diff path without changing tracking algorithms or production config. */
@Mixin(value = NearbyTrackers.class, remap = false)
public abstract class TrackingPreparationSwitch {
    @ModifyConstant(method = "diffPlayers", constant = @Constant(longValue = 1024L))
    private long preparationThreshold(long threshold) {
        return FeatureBench.parallelTrackingPreparation ? threshold : Long.MAX_VALUE;
    }
}
