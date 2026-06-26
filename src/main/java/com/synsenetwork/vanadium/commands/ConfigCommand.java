package com.synsenetwork.vanadium.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.shedaniel.autoconfig.AutoConfig;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.config.VanadiumConfig;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/** Registers {@code /vanadium config ...} — toggling threading flags, inspecting state, and saving. */
public class ConfigCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("vanadium").then(config()));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> config() {
        VanadiumConfig config = Vanadium.config;
        return literal("config")
                .then(literal("toggle").requires(cmdSrc -> cmdSrc.hasPermissionLevel(2)).executes(cmdCtx -> {
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
                        })))
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
                .then(literal("save").requires(cmdSrc -> cmdSrc.hasPermissionLevel(2)).executes(cmdCtx -> {
                    MutableText message = Text.literal("Saving Vanadium config to disk...");
                    cmdCtx.getSource().sendFeedback(() -> message, true);
                    AutoConfig.getConfigHolder(VanadiumConfig.class).save();
                    cmdCtx.getSource().sendFeedback(() -> Text.literal("Done!"), true);
                    return 1;
                }));
    }
}
