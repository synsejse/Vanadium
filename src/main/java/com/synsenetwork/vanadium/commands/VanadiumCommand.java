package com.synsenetwork.vanadium.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.config.ConfigOptions;
import com.synsenetwork.vanadium.config.ConfigOptions.BoolOption;
import com.synsenetwork.vanadium.config.ConfigOptions.IntOption;
import com.synsenetwork.vanadium.config.ConfigOptions.Option;
import com.synsenetwork.vanadium.config.VanadiumConfig;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Registers /vanadium — status plus toggle/set/save/reload/defaults generated from {@link ConfigOptions}. */
public final class VanadiumCommand {
    private VanadiumCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("vanadium")
                .executes(VanadiumCommand::status)
                .then(literal("status").executes(VanadiumCommand::status))
                .then(toggle())
                .then(set())
                .then(literal("save").requires(src -> src.hasPermissionLevel(2)).executes(VanadiumCommand::save))
                .then(literal("reload").requires(src -> src.hasPermissionLevel(2)).executes(VanadiumCommand::reload))
                .then(literal("defaults").requires(src -> src.hasPermissionLevel(2)).executes(VanadiumCommand::defaults)));
    }

    // --- subtrees generated from the registry ---------------------------------

    private static LiteralArgumentBuilder<ServerCommandSource> toggle() {
        LiteralArgumentBuilder<ServerCommandSource> toggle =
                literal("toggle").requires(src -> src.hasPermissionLevel(2));
        for (Option option : ConfigOptions.ALL) {
            if (option instanceof BoolOption bool) {
                toggle.then(literal(bool.name()).executes(ctx -> {
                    boolean next = !bool.get().apply(Vanadium.config);
                    bool.set().accept(Vanadium.config, next);
                    feedback(ctx, bool.name() + " is now " + (next ? "on" : "off") + restartHint(bool));
                    return 1;
                }));
            }
        }
        return toggle;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> set() {
        LiteralArgumentBuilder<ServerCommandSource> set =
                literal("set").requires(src -> src.hasPermissionLevel(2));
        for (Option option : ConfigOptions.ALL) {
            set.then(switch (option) {
                case BoolOption bool -> literal(bool.name())
                        .then(argument("value", BoolArgumentType.bool()).executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            bool.set().accept(Vanadium.config, value);
                            feedback(ctx, bool.name() + " is now " + (value ? "on" : "off") + restartHint(bool));
                            return 1;
                        }));
                case IntOption anInt -> literal(anInt.name())
                        .then(argument("value", IntegerArgumentType.integer(anInt.min())).executes(ctx -> {
                            int value = IntegerArgumentType.getInteger(ctx, "value");
                            anInt.set().accept(Vanadium.config, value);
                            feedback(ctx, anInt.name() + " is now " + value + restartHint(anInt));
                            return 1;
                        }));
            });
        }
        return set;
    }

    // --- executors -------------------------------------------------------------

    private static int status(CommandContext<ServerCommandSource> ctx) {
        VanadiumConfig config = Vanadium.config;
        MutableText message = Text.literal("Vanadium " + version() + " — ")
                .append(onOff(config.enabled, "enabled", "disabled"));

        int resolvedWorkers = VanadiumConfig.resolveWorkers();
        MutableText workers = Text.literal("\n  workers: " + Vanadium.bootWorkers + " active (config "
                + config.workers + " → " + resolvedWorkers + ")");
        if (resolvedWorkers != Vanadium.bootWorkers) {
            workers.append(Text.literal(" restart pending").formatted(Formatting.YELLOW));
        }
        message.append(workers);

        message.append(Text.literal("\n  cellSize: " + config.cellSize
                + (config.cellSize == 0 ? " (auto → " + VanadiumConfig.resolveCellSize() + ")" : "")));

        for (Option option : ConfigOptions.ALL) {
            if (option instanceof BoolOption bool && !bool.name().equals("enabled")) {
                message.append(Text.literal("\n  " + bool.name() + ": "))
                        .append(onOff(bool.get().apply(config), "on", "off"));
            }
        }

        ctx.getSource().sendFeedback(() -> message, false);
        return 1;
    }

    private static int save(CommandContext<ServerCommandSource> ctx) {
        AutoConfig.getConfigHolder(VanadiumConfig.class).save();
        feedback(ctx, "config saved to disk");
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> ctx) {
        ConfigHolder<VanadiumConfig> holder = AutoConfig.getConfigHolder(VanadiumConfig.class);
        boolean ok = holder.load();
        Vanadium.config = holder.getConfig(); // load() builds a fresh instance; consumers read the static
        if (ok) {
            feedback(ctx, "config reloaded from disk");
            return 1;
        }
        ctx.getSource().sendError(Text.literal(
                "Vanadium config reload failed — check the file and server log; /vanadium status shows active values"));
        return 0;
    }

    private static int defaults(CommandContext<ServerCommandSource> ctx) {
        ConfigOptions.resetToDefaults(Vanadium.config);
        feedback(ctx, "all options reset to defaults (in memory — /vanadium save to persist)");
        return 1;
    }

    // --- helpers -----------------------------------------------------------------

    private static void feedback(CommandContext<ServerCommandSource> ctx, String text) {
        MutableText message = Text.literal("Vanadium: " + text);
        ctx.getSource().sendFeedback(() -> message, true);
    }

    private static String restartHint(Option option) {
        return option.live() ? "" : " (takes effect on restart)";
    }

    private static MutableText onOff(boolean value, String on, String off) {
        return Text.literal(value ? on : off).formatted(value ? Formatting.GREEN : Formatting.RED);
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer("vanadium")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }
}
