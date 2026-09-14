package com.synsenetwork.vanadium.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.config.ConfigOptions;
import com.synsenetwork.vanadium.config.VanadiumConfig;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.Permissions;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Registers /vanadium — status plus toggle/set/save/reload/defaults generated from {@link ConfigOptions}. */
public final class VanadiumCommand {
    private VanadiumCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("vanadium")
                .executes(VanadiumCommand::status)
                .then(literal("status").executes(VanadiumCommand::status))
                .then(toggle())
                .then(set())
                .then(literal("profile").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(ctx -> profile(ctx, 30))
                        .then(argument("seconds", IntegerArgumentType.integer(1, 300))
                                .executes(ctx -> profile(ctx, IntegerArgumentType.getInteger(ctx, "seconds"))))
                        .then(literal("stop").executes(ctx -> {
                            if (TickProfiler.stop()) return 1;
                            ctx.getSource().sendFailure(Component.literal("Vanadium: no profile is running"));
                            return 0;
                        }))
                        .then(literal("report").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(TickProfiler.lastReport()), false);
                            return 1;
                        })))
                .then(literal("save").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(VanadiumCommand::save))
                .then(literal("reload").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(VanadiumCommand::reload))
                .then(literal("defaults").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(VanadiumCommand::defaults))
                .then(literal("benchmark").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(VanadiumCommand::benchmark)));
    }

    // --- subtrees generated from the registry ---------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> toggle() {
        LiteralArgumentBuilder<CommandSourceStack> toggle =
                literal("toggle").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
        for (Field field : ConfigOptions.fields()) {
            if (field.getType() == boolean.class) {
                toggle.then(literal(field.getName()).executes(ctx -> {
                    boolean next = !ConfigOptions.getBool(field, Vanadium.config);
                    ConfigOptions.setBool(field, Vanadium.config, next);
                    feedback(ctx, field.getName() + " is now " + (next ? "on" : "off") + restartHint(field));
                    return 1;
                }));
            }
        }
        return toggle;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> set() {
        LiteralArgumentBuilder<CommandSourceStack> set =
                literal("set").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
        for (Field field : ConfigOptions.fields()) {
            String name = field.getName();
            if (field.getType() == boolean.class) {
                set.then(literal(name)
                        .then(argument("value", BoolArgumentType.bool()).executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            ConfigOptions.setBool(field, Vanadium.config, value);
                            feedback(ctx, name + " is now " + (value ? "on" : "off") + restartHint(field));
                            return 1;
                        })));
            } else if (field.getType() == int.class) {
                set.then(literal(name)
                        .then(argument("value", IntegerArgumentType.integer(ConfigOptions.min(field))).executes(ctx -> {
                            int value = IntegerArgumentType.getInteger(ctx, "value");
                            ConfigOptions.setInt(field, Vanadium.config, value);
                            feedback(ctx, name + " is now " + value + restartHint(field));
                            return 1;
                        })));
            } else if (field.getType() == List.class) {
                set.then(literal(name)
                        .then(argument("value", StringArgumentType.greedyString()).executes(ctx -> {
                            String value = StringArgumentType.getString(ctx, "value");
                            try {
                                List<String> ids = value.equals("none") ? List.of()
                                        : Arrays.stream(value.split(",", -1)).map(String::trim).toList();
                                ConfigOptions.setList(field, Vanadium.config, ids);
                            } catch (IllegalArgumentException e) {
                                ctx.getSource().sendFailure(Component.literal(e.getMessage()));
                                return 0;
                            }
                            feedback(ctx, name + " is now " + value + restartHint(field));
                            return 1;
                        })));
            } else {
                throw new IllegalStateException("Unsupported config field type: " + field);
            }
        }
        return set;
    }

    // --- executors -------------------------------------------------------------

    private static int status(CommandContext<CommandSourceStack> ctx) {
        VanadiumConfig config = Vanadium.config;
        MutableComponent message = Component.literal("Vanadium " + version() + " — ")
                .append(onOff(config.enabled, "enabled", "disabled"));

        int resolvedWorkers = VanadiumConfig.resolveWorkers();
        int activeWorkers = Vanadium.scheduler.workerCount();
        MutableComponent workers = Component.literal("\n  workers: " + activeWorkers + " active (config "
                + config.workers + " → " + resolvedWorkers + ")");
        if (resolvedWorkers != activeWorkers) {
            workers.append(Component.literal(" restart pending").withStyle(ChatFormatting.YELLOW));
        }
        message.append(workers);

        message.append(Component.literal("\n  cellSize: " + config.cellSize
                + (config.cellSize == 0 ? " (auto → " + VanadiumConfig.resolveCellSize() + ")" : "")));

        for (Field field : ConfigOptions.fields()) {
            if (field.getType() == boolean.class && !field.getName().equals("enabled")) {
                message.append(Component.literal("\n  " + field.getName() + ": "))
                        .append(onOff(ConfigOptions.getBool(field, config), "on", "off"));
            } else if (field.getType() == List.class) {
                List<String> rules = ConfigOptions.getList(field, config);
                message.append(Component.literal("\n  " + field.getName() + ": " + (rules.isEmpty() ? "none" : String.join(", ", rules))));
            } else if (field.getType() == int.class && !field.getName().equals("workers")
                    && !field.getName().equals("cellSize")) {
                message.append(Component.literal("\n  " + field.getName() + ": " + ConfigOptions.getInt(field, config)));
            }
        }

        ctx.getSource().sendSuccess(() -> message, false);
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        AutoConfig.getConfigHolder(VanadiumConfig.class).save();
        feedback(ctx, "config saved to disk");
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ConfigHolder<VanadiumConfig> holder = AutoConfig.getConfigHolder(VanadiumConfig.class);
        boolean ok = holder.load();
        Vanadium.config = holder.getConfig(); // load() builds a fresh instance; consumers read the static
        if (ok) {
            feedback(ctx, "config reloaded from disk");
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(
                "Vanadium config reload failed — check the file and server log; /vanadium status shows active values"));
        return 0;
    }

    private static int defaults(CommandContext<CommandSourceStack> ctx) {
        ConfigOptions.resetToDefaults(Vanadium.config);
        feedback(ctx, "all options reset to defaults (in memory — /vanadium save to persist)");
        return 1;
    }

    private static int benchmark(CommandContext<CommandSourceStack> ctx) {
        if (!TickBenchmark.start(ctx.getSource().getServer(), ctx.getSource())) {
            ctx.getSource().sendFailure(Component.literal("Vanadium: a benchmark (or /tick sprint) is already running"));
            return 0;
        }
        feedback(ctx, "benchmark started — TPS unlocked for the next 30s");
        return 1;
    }

    private static int profile(CommandContext<CommandSourceStack> ctx, int seconds) {
        if (!TickProfiler.start(ctx.getSource(), seconds)) {
            ctx.getSource().sendFailure(Component.literal("Vanadium: a profile is already running"));
            return 0;
        }
        feedback(ctx, "profiling for " + seconds + "s at the current tick rate; /vanadium profile report shows the last result");
        return 1;
    }

    // --- helpers -----------------------------------------------------------------

    private static void feedback(CommandContext<CommandSourceStack> ctx, String text) {
        MutableComponent message = Component.literal("Vanadium: " + text);
        ctx.getSource().sendSuccess(() -> message, true);
    }

    private static String restartHint(Field field) {
        return ConfigOptions.isLive(field) ? "" : " (takes effect on restart)";
    }

    private static MutableComponent onOff(boolean value, String on, String off) {
        return Component.literal(value ? on : off).withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer("vanadium")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }
}
