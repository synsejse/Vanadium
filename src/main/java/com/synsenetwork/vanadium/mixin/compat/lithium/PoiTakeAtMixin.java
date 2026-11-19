package com.synsenetwork.vanadium.mixin.compat.lithium;

import com.llamalad7.mixinextras.sugar.Local;
import com.synsenetwork.vanadium.concurrent.PoiClaims;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lithium's exact-position claim ({@code ai.poi}; its {@code ai.poi.tasks} job-site search calls it in place of
 * {@code PoiManager.take}) drops {@code acquireTicket}'s result and returns the position whether or not the record
 * still had a free ticket. Once Lithium has found the record and matched its type, this claims the ticket through
 * {@code PoiClaims} (the record must still be installed) and returns the position only if it took one, so
 * {@code AcquirePoiMixin} only gives the POI to a villager holding a ticket. Lithium's own {@code acquireTicket}
 * call is never reached.
 * <p>
 * One injector, so it binds or not as a whole. {@code require = 0} because the method is absent with
 * {@code ai.poi} disabled; when it is present, {@code VanadiumMixinPlugin} fails the transformation unless this bound.
 */
@Mixin(value = PoiManager.class, priority = 1100) // After Lithium: injects into a method it merges.
public abstract class PoiTakeAtMixin {
    @Inject(method = "lithium$takeAt", require = 0, cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;acquireTicket()Z"))
    private void vanadium$claimOrEmpty(Predicate<Holder<PoiType>> type, BiPredicate<Holder<PoiType>, BlockPos> filter,
            BlockPos pos, CallbackInfoReturnable<Optional<BlockPos>> cir, @Local PoiRecord record) {
        cir.setReturnValue(PoiClaims.claim((PoiManager) (Object) this, record) ? Optional.of(record.getPos()) : Optional.empty());
    }
}
