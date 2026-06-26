package com.synsenetwork.vanadium.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.synsenetwork.vanadium.Vanadium;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/** Registers {@code /vanadium debug on|off} — subscribing the player to the cell-tick debug renderer. */
public class DebugCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("vanadium").then(debug()));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> debug() {
        return literal("debug")
                .then(literal("on").executes(cmdCtx -> {
                    ServerPlayerEntity player = cmdCtx.getSource().getPlayerOrThrow();
                    Vanadium.debug.subscribe(player.getUuid());
                    cmdCtx.getSource().sendFeedback(
                            () -> Text.literal("Vanadium debug rendering enabled"), false);
                    return 1;
                }))
                .then(literal("off").executes(cmdCtx -> {
                    ServerPlayerEntity player = cmdCtx.getSource().getPlayerOrThrow();
                    Vanadium.debug.unsubscribe(player.getUuid());
                    cmdCtx.getSource().sendFeedback(
                            () -> Text.literal("Vanadium debug rendering disabled"), false);
                    return 1;
                }));
    }
}
