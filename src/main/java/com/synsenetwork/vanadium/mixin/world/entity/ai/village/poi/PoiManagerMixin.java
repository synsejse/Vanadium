package com.synsenetwork.vanadium.mixin.world.entity.ai.village.poi;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.synsenetwork.vanadium.concurrent.PoiClaims;
import com.synsenetwork.vanadium.concurrent.PoiDistanceAccess;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PoiManager.class)
public abstract class PoiManagerMixin {
    @Shadow
    @SuppressWarnings("unused")
    private PoiManager.DistanceTracker distanceTracker;

    @Shadow
    public abstract Stream<PoiRecord> getInRange(Predicate<Holder<PoiType>> predicate, BlockPos center, int radius,
            PoiManager.Occupancy occupancy);

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

    /**
     * @author Vanadium
     * @reason Vanilla returns its first candidate whether or not the ticket was still free: two claimants of one
     * record could both be given its last ticket. A candidate whose last ticket another thread took after the
     * occupancy filter, or that is no longer installed (removed, dropped by a refresh, or its column unloaded), is
     * skipped and the search goes on, as if that change had come first ({@code PoiClaims}); otherwise the order,
     * filters and result are vanilla's. Callers: breeding's bed search ({@code VillagerMakeLove}, any reachable
     * bed in range) and the job-site claim ({@code AcquirePoiMixin}, one exact position) when Lithium's
     * {@code ai.poi.tasks} does not redirect it to {@code takeAt}.
     */
    @Overwrite
    public Optional<BlockPos> take(Predicate<Holder<PoiType>> predicate, BiPredicate<Holder<PoiType>, BlockPos> filter,
            BlockPos center, int radius) {
        return this.getInRange(predicate, center, radius, PoiManager.Occupancy.HAS_SPACE)
                .filter(poi -> filter.test(poi.getPoiType(), poi.getPos()) && PoiClaims.claim((PoiManager) (Object) this, poi))
                .findFirst()
                .map(PoiRecord::getPos);
    }
}
