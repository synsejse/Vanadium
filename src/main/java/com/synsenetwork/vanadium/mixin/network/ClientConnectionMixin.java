package com.synsenetwork.vanadium.mixin.network;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.synsenetwork.vanadium.Vanadium;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.AbstractEventExecutor;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Two VMP-derived (MIT, Copyright (c) ishland) networking patches:
 *
 * <p><b>Flush consolidation</b> — vanilla already marks play-phase sends made on the server
 * thread during the tick as no-flush and flushes each connection once per tick (the
 * disableFlush/enableFlush cycle in tickWorlds), but it still wakes the Netty event loop
 * once per packet and runs a redundant mid-tick flush per connection from
 * ClientConnection.tick(). No-flush sends are enqueued with lazyExecute (no selector
 * wakeup) and the tick() flush is dropped; the end-of-tick enableFlush wakes the loop once,
 * after all of the tick's write tasks, so the whole tick's packets go out in one flush.
 *
 * <p><b>Non-blocking disconnect</b> — vanilla's disconnect blocks the calling thread on
 * channel-close (awaitUninterruptibly) purely so isOpen() reads false afterwards. Under
 * Vanadium a disconnect can fire from a worker mid-wave, where any blocking wait stalls the
 * whole wave. The wait is dropped and the post-condition recreated with a volatile flag
 * folded into every internal isOpen() check.
 */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {

    @Shadow
    private Channel channel;

    @Unique
    private volatile boolean vanadium$closing = false;

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lio/netty/channel/Channel;flush()Lio/netty/channel/Channel;", remap = false))
    private Channel vanadium$skipMidTickFlush(Channel instance) {
        if (!Vanadium.config.enabled || !Vanadium.config.consolidateFlushes) {
            return instance.flush();
        }
        return instance;
    }

    @WrapOperation(method = "sendImmediately", at = @At(value = "INVOKE",
            target = "Lio/netty/channel/EventLoop;execute(Ljava/lang/Runnable;)V", remap = false))
    private void vanadium$lazyExecuteNoFlushSends(EventLoop instance, Runnable runnable, Operation<Void> original,
                                                  Packet<?> packet, @Nullable PacketCallbacks callbacks, boolean flush) {
        if (!flush && Vanadium.config.enabled && Vanadium.config.consolidateFlushes
                && instance instanceof AbstractEventExecutor executor) {
            executor.lazyExecute(runnable);
        } else {
            original.call(instance, runnable);
        }
    }

    @Redirect(method = "disconnect(Lnet/minecraft/network/DisconnectionInfo;)V", at = @At(value = "INVOKE",
            target = "Lio/netty/channel/ChannelFuture;awaitUninterruptibly()Lio/netty/channel/ChannelFuture;", remap = false))
    private ChannelFuture vanadium$dontBlockOnClose(ChannelFuture instance) {
        this.vanadium$closing = true;
        return instance;
    }

    @Redirect(method = "*", at = @At(value = "INVOKE",
            target = "Lio/netty/channel/Channel;isOpen()Z", remap = false))
    private boolean vanadium$isOpenAndNotClosing(Channel instance) {
        return this.channel != null && this.channel.isOpen() && !this.vanadium$closing;
    }
}
