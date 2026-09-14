package com.synsenetwork.vanadium.mixin.server;

import com.synsenetwork.vanadium.commands.TickBenchmark;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerTickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerTickRateManager.class)
public class ServerTickManagerMixin {
    /** /vanadium benchmark drives a vanilla tick-sprint but sends its own report — mute vanilla's
     *  "Sprint completed with N ticks per second" while the benchmark owns the sprint. A manual
     *  /tick sprint keeps its message. */
    @Redirect(method = "finishTickSprint", at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/CommandSourceStack;sendSuccess(Ljava/util/function/Supplier;Z)V"))
    private void muteSprintReportDuringBenchmark(CommandSourceStack source, Supplier<Component> feedback, boolean broadcastToOps) {
        if (!TickBenchmark.isActive()) {
            source.sendSuccess(feedback, broadcastToOps);
        }
    }
}
