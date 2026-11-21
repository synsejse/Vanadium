package com.synsenetwork.vanadium.mixin.world.level;

import com.synsenetwork.vanadium.spawning.ConcurrentMobCategoryCounts;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces per-player mob-cap counts with an atomic array so local cap checks and spawns never lock or box. */
@Mixin(LocalMobCapCalculator.MobCounts.class)
public abstract class LocalMobCapCalculatorMobCountsMixin {
    @Shadow @Final @Mutable private Object2IntMap<MobCategory> counts;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$installAtomicCounts(CallbackInfo ci) {
        this.counts = new ConcurrentMobCategoryCounts();
    }

    /**
     * @author Vanadium
     * @reason Lock-free atomic increment without boxing.
     */
    @Overwrite
    public void add(MobCategory category) {
        ((ConcurrentMobCategoryCounts) this.counts).increment(category);
    }

    /**
     * @author Vanadium
     * @reason Lock-free atomic cap check.
     */
    @Overwrite
    public boolean canSpawn(MobCategory category) {
        return ((ConcurrentMobCategoryCounts) this.counts).getCount(category) < category.getMaxInstancesPerChunk();
    }
}
