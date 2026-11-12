package com.synsenetwork.vanadium.mixin.server;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.ServerSpin;
import com.synsenetwork.vanadium.tick.WorkerPool;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickChildren", at = @At(value = "INVOKE", target = "Ljava/lang/Iterable;iterator()Ljava/util/Iterator;"))
    private void preTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        Vanadium.scheduler.setIsolation(Vanadium.config.isolation);
    }

    @Shadow
    private boolean waitingForNextTick;

    @Shadow
    private long nextTickTimeNanos;

    /**
     * In the last part of the wait for the next tick (armed by the tick that just ended), spin a short slice
     * instead of parking; the surrounding managedBlock loop keeps polling tasks between slices, so task
     * processing during the wait is unchanged.
     */
    @Inject(method = "waitForTasks", at = @At("HEAD"), cancellable = true)
    private void vanadium$spinBeforeTick(CallbackInfo ci) {
        if (this.waitingForNextTick && ServerSpin.spin(this.nextTickTimeNanos, Util.getNanos())) ci.cancel();
    }

    /** Wakes from the wait for the next tick when the spin window starts instead of when the tick is due. */
    @ModifyArg(method = "waitForTasks", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/locks/LockSupport;parkNanos(Ljava/lang/Object;J)V"), index = 1)
    private long vanadium$parkUntilSpin(long nanos) {
        return this.waitingForNextTick ? ServerSpin.parkNanos(this.nextTickTimeNanos, nanos) : nanos;
    }

    @Redirect(method = "reloadResources", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    private boolean onServerExecutionThreadPatch(MinecraftServer minecraftServer) {
        return WorkerPool.isWorkerThread();
    }


}

