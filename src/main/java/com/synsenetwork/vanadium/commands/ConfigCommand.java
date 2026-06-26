package com.synsenetwork.vanadium.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.shedaniel.autoconfig.AutoConfig;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.config.VanadiumConfig;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

public class ConfigCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> vanadium = literal("vanadium");
        vanadium = vanadium.then(registerConfig(literal("config")));
        vanadium = vanadium.then(registerDebug(literal("debug")));
        dispatcher.register(vanadium);
    }

    public static ArgumentBuilder<ServerCommandSource, ?> registerConfig(LiteralArgumentBuilder<ServerCommandSource> root) {
        VanadiumConfig config = Vanadium.config;
        return root.then(literal("toggle").requires(cmdSrc -> {
                            return cmdSrc.hasPermissionLevel(2);
                        }).executes(cmdCtx -> {
                            config.disabled = !config.disabled;
                            MutableText message = Text.literal(
                                    "Vanadium is now " + (config.disabled ? "disabled" : "enabled"));
                            cmdCtx.getSource().sendFeedback(() -> message, true);
                            return 1;
                        }).then(literal("te").executes(cmdCtx -> {
                            config.disableBlockEntity = !config.disableBlockEntity;
                            MutableText message = Text.literal("Vanadium's tile entity threading is now "
                                    + (config.disableBlockEntity ? "disabled" : "enabled"));
                            cmdCtx.getSource().sendFeedback(() -> message, true);
                            return 1;
                        })).then(literal("entity").executes(cmdCtx -> {
                            config.disableEntity = !config.disableEntity;
                            MutableText message = Text.literal(
                                    "Vanadium's entity threading is now " + (config.disableEntity ? "disabled" : "enabled"));
                            cmdCtx.getSource().sendFeedback(() -> message, true);
                            return 1;
                        })).then(literal("environment").executes(cmdCtx -> {
                            config.disableEnvironment = !config.disableEnvironment;
                            MutableText message = Text.literal("Vanadium's environment threading is now "
                                    + (config.disableEnvironment ? "disabled" : "enabled"));
                            cmdCtx.getSource().sendFeedback(() -> message, true);
                            return 1;
                        }))
                )
                .then(literal("state").executes(cmdCtx -> {
                    StringBuilder messageString = new StringBuilder(
                            "Vanadium is currently " + (config.disabled ? "disabled" : "enabled"));
                    if (!config.disabled) {
                        messageString.append(" Entity:").append(config.disableEntity ? "disabled" : "enabled");
                        messageString.append(" TE:").append(config.disableBlockEntity ? "disabled" : "enabled");
                        messageString.append(" Env:").append(config.disableEnvironment ? "disabled" : "enabled");
                        messageString.append(" SCP:").append(config.disableChunkProvider ? "disabled" : "enabled");
                    }
                    MutableText message = Text.literal(messageString.toString());
                    cmdCtx.getSource().sendFeedback(() -> message, true);
                    return 1;
                }))
                .then(literal("save").requires(cmdSrc -> {
                    return cmdSrc.hasPermissionLevel(2);
                }).executes(cmdCtx -> {
                    MutableText message = Text.literal("Saving Vanadium config to disk...");
                    cmdCtx.getSource().sendFeedback(() -> message, true);
                    AutoConfig.getConfigHolder(VanadiumConfig.class).save();
                    cmdCtx.getSource().sendFeedback(() -> Text.literal("Done!"), true);
                    return 1;
                }));
    }

    public static ArgumentBuilder<ServerCommandSource, ?> registerDebug(LiteralArgumentBuilder<ServerCommandSource> root) {
        return root
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
