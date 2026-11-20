package com.synsenetwork.vanadium.mixin.world.entity.ai.behavior;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A villager picks a POI with a free ticket, paths to it, then claims it; vanilla ignores whether the claim
 * succeeded and stores the memory anyway. Villagers ticking at once can pick the same last ticket, so the memory,
 * acquisition event, retry-cache reset and debug update only follow a claim of the target position itself. A lost
 * claim changes nothing: the next scheduled attempt searches again, and the full POI is no longer a candidate.
 * <p>
 * The claim is vanilla's {@code PoiManager.take} ({@code PoiManagerMixin}) or, with Lithium's {@code ai.poi.tasks},
 * its redirect to the exact-position {@code takeAt} ({@code PoiTakeAtMixin}); this wraps whichever is called. Both
 * return empty unless they took a ticket. The lambda is the {@code getType(target).ifPresent} body.
 */
@Mixin(value = AcquirePoi.class, priority = 1100) // After Lithium's redirect of the claim.
public abstract class AcquirePoiMixin {
    @Unique
    private static final String CLAIM = "lambda$create$7(Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
            + "Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/ai/behavior/declarative/MemoryAccessor;Lnet/minecraft/server/level/ServerLevel;"
            + "Ljava/util/Optional;Lnet/minecraft/world/entity/PathfinderMob;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"
            + "Lnet/minecraft/core/Holder;)V";

    @WrapOperation(method = CLAIM, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;take(Ljava/util/function/Predicate;Ljava/util/function/BiPredicate;Lnet/minecraft/core/BlockPos;I)Ljava/util/Optional;"))
    private static Optional<BlockPos> vanadium$noteClaim(PoiManager poiManager, Predicate<Holder<PoiType>> type,
            BiPredicate<Holder<PoiType>, BlockPos> filter, BlockPos target, int radius, Operation<Optional<BlockPos>> original,
            @Share("claimed") LocalBooleanRef claimed) {
        Optional<BlockPos> taken = original.call(poiManager, type, filter, target, radius);
        claimed.set(taken.isPresent() && taken.get().equals(target));
        return taken;
    }

    /** Everything after the claim (memory, event, retry cache, debug update) is skipped when it failed. */
    @Inject(method = CLAIM, cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/behavior/declarative/MemoryAccessor;set(Ljava/lang/Object;)V"))
    private static void vanadium$onlyAfterClaim(CallbackInfo ci, @Share("claimed") LocalBooleanRef claimed) {
        if (!claimed.get()) {
            ci.cancel();
        }
    }
}
