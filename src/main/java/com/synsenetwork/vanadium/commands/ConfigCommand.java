package com.synsenetwork.vanadium.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.shedaniel.autoconfig.AutoConfig;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.config.VanadiumConfig;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

public class ConfigCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> vanadiumconfig = literal("vanadium");
        vanadiumconfig = vanadiumconfig.then(registerConfig(literal("config")));
        vanadiumconfig = vanadiumconfig.then(RegionCommand.registerRegion(literal("region")));
        dispatcher.register(vanadiumconfig);
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
                        })).then(literal("world").executes(cmdCtx -> {
                            config.disableWorld = !config.disableWorld;
                            MutableText message = Text.literal(
                                    "Vanadium's world threading is now " + (config.disableWorld ? "disabled" : "enabled"));
                            cmdCtx.getSource().sendFeedback(() -> message, true);
                            return 1;
                        }))
                )
                .then(literal("state").executes(cmdCtx -> {
                    StringBuilder messageString = new StringBuilder(
                            "Vanadium is currently " + (config.disabled ? "disabled" : "enabled"));
                    if (!config.disabled) {
                        messageString.append(" World:" + (config.disableWorld ? "disabled" : "enabled"));
                        messageString.append(" Entity:" + (config.disableEntity ? "disabled" : "enabled"));
                        messageString.append(" TE:" + (config.disableBlockEntity ? "disabled" : "enabled"));
                        messageString.append(" Env:" + (config.disableEnvironment ? "disabled" : "enabled"));
                        messageString.append(" SCP:" + (config.disableChunkProvider ? "disabled" : "enabled"));
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
}
