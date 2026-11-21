package com.synsenetwork.vanadium.mixin.world.level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Allows parallel spawning checks to read spawn potential charges without locking against rare charge additions. */
@Mixin(PotentialCalculator.class)
public abstract class PotentialCalculatorMixin {
    @Shadow @Final @Mutable private List<PotentialCalculator.PointCharge> charges;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$installCopyOnWriteCharges(CallbackInfo ci) {
        this.charges = new CopyOnWriteArrayList<>();
    }
}
