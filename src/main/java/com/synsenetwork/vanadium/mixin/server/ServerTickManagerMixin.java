package com.synsenetwork.vanadium.mixin.server;

import com.synsenetwork.vanadium.commands.TickBenchmark;
import java.util.function.Supplier;
import net.minecraft.server.ServerTickManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerTickManager.class)
public class ServerTickManagerMixin {
    /** /vanadium benchmark drives a vanilla tick-sprint but sends its own report — mute vanilla's
     *  "Sprint completed with N ticks per second" while the benchmark owns the sprint. A manual
     *  /tick sprint keeps its message. */
    @Redirect(method = "finishSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/command/ServerCommandSource;sendFeedback(Ljava/util/function/Supplier;Z)V"))
    private void muteSprintReportDuringBenchmark(ServerCommandSource source, Supplier<Text> feedback, boolean broadcastToOps) {
        if (!TickBenchmark.isActive()) {
            source.sendFeedback(feedback, broadcastToOps);
        }
    }
}
