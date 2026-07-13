package com.synsenetwork.vanadium.mixin.server.network;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.WorkerPool;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {

    /**
     * Vanilla only marks a send no-flush when it happens on the server thread inside the
     * tick's disableFlush window ({@code !flushDisabled || !server.isOnThread()}). Packets
     * sent from Vanadium's workers — the whole TRACKING wave — fail the thread check and go
     * out as one write+flush+selector-wakeup each. Waves only run inside the tick, so worker
     * sends are always inside the flush window: treat workers as on-thread here and they
     * batch into the same end-of-tick flush as main-thread sends.
     */
    @Redirect(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isOnThread()Z"))
    private boolean vanadium$workersAreInFlushWindow(MinecraftServer server) {
        if (Vanadium.config.enabled && Vanadium.config.consolidateFlushes && WorkerPool.isWorkerThread()) {
            return true;
        }
        return server.isOnThread();
    }
}
