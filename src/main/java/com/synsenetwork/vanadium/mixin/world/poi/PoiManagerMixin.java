package com.synsenetwork.vanadium.mixin.world.poi;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.synsenetwork.vanadium.concurrent.PoiDistanceAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

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

    /**
     * Vanilla passes the section's village-centre level, computed before the graph's monitor, to the graph. Two
     * threads changing one section could publish in the opposite order to their reads and leave a stale centre
     * (or none). The level is read again holding the monitor, so the last update to a section publishes a state no
     * older than every change made before it. The read takes no lock (see {@code PoiRecordMixin} and the
     * unsynchronized {@code PoiSection.getRecords}), so the graph's monitor stays terminal.
     */
    @WrapOperation(method = {"setDirty", "onSectionLoad"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$DistanceTracker;update(JIZ)V"))
    private void vanadium$publishLevelUnderGraphLock(PoiManager.DistanceTracker tracker, long sectionPos, int staleLevel,
            boolean onlyDecreased, Operation<Void> original) {
        synchronized (tracker) {
            original.call(tracker, sectionPos, ((PoiDistanceAccess) (Object) tracker).vanadium$levelFromSource(sectionPos), onlyDecreased);
        }
    }
}
