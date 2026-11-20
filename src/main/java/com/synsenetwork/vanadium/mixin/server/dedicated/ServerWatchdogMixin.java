package com.synsenetwork.vanadium.mixin.server.dedicated;

import com.synsenetwork.vanadium.diagnostics.WatchdogReport;
import net.minecraft.CrashReport;
import net.minecraft.server.dedicated.ServerWatchdog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds Vanadium's section to the crash report vanilla's watchdog writes for a hung tick ({@link WatchdogReport}). */
@Mixin(ServerWatchdog.class)
public abstract class ServerWatchdogMixin {
    @Inject(method = "createWatchdogCrashReport", at = @At("RETURN"))
    private static void addVanadiumSection(String message, long mainThreadId, CallbackInfoReturnable<CrashReport> cir) {
        WatchdogReport.addTo(cir.getReturnValue());
    }
}
