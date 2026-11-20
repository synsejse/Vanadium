package com.synsenetwork.vanadium.mixin.util;

import java.util.concurrent.Semaphore;
import net.minecraft.util.ThreadingDetector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla gives each detector (every {@code PalettedContainer} and {@code LegacyRandomSource} has one) a single
 * permit and crashes when a second thread acquires it, which assumes one server thread. 255 permits let parallel
 * workers hold one container at once.
 */
@Mixin(ThreadingDetector.class)
public abstract class ThreadingDetectorMixin {
    @Shadow
    @Final
    @Mutable
    private Semaphore lock;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void installSharedPermits(CallbackInfo ci) {
        lock = new Semaphore(255);
    }
}
