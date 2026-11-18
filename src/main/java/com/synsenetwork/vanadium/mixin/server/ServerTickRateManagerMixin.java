package com.synsenetwork.vanadium.mixin.server;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.synsenetwork.vanadium.diagnostics.TickBenchmark;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerTickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerTickRateManager.class)
public class ServerTickRateManagerMixin {
    /** /vanadium benchmark drives a vanilla tick-sprint but sends its own report — mute vanilla's
     *  "Sprint completed with N ticks per second" while the benchmark owns the sprint. A manual
     *  /tick sprint keeps its message. */
    @WrapWithCondition(method = "finishTickSprint", at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/CommandSourceStack;sendSuccess(Ljava/util/function/Supplier;Z)V"))
    private boolean muteSprintReportDuringBenchmark(CommandSourceStack source, Supplier<Component> feedback, boolean broadcastToOps) {
        return !TickBenchmark.isActive();
    }
}
