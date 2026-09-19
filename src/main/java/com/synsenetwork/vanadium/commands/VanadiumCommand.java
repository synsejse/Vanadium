package com.synsenetwork.vanadium.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.config.VanadiumConfig;
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

/** Registers /vanadium status, config reload, profiling, and benchmarking commands. */
public final class VanadiumCommand {
    private VanadiumCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("vanadium")
                .executes(VanadiumCommand::status)
                .then(literal("status").executes(VanadiumCommand::status))
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
                .then(literal("reload").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(VanadiumCommand::reload))
                .then(literal("benchmark").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(VanadiumCommand::benchmark)));
    }

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

        message.append(Component.literal("\n  parallelEntities: ")).append(onOff(config.parallelEntities, "on", "off"));
        message.append(Component.literal("\n  parallelBlockEntities: ")).append(onOff(config.parallelBlockEntities, "on", "off"));
        message.append(Component.literal("\n  serialEntityTypes: "
                + (config.serialEntityTypes.isEmpty() ? "none" : String.join(", ", config.serialEntityTypes))));
        message.append(Component.literal("\n  serialBlockEntityTypes: "
                + (config.serialBlockEntityTypes.isEmpty() ? "none" : String.join(", ", config.serialBlockEntityTypes))));
        message.append(Component.literal("\n  parallelChunkTicks: ")).append(onOff(config.parallelChunkTicks, "on", "off"));
        message.append(Component.literal("\n  parallelScheduledTicks: ")).append(onOff(config.parallelScheduledTicks, "on", "off"));
        message.append(Component.literal("\n  parallelSpawning: ")).append(onOff(config.parallelSpawning, "on", "off"));
        message.append(Component.literal("\n  parallelTracking: ")).append(onOff(config.parallelTracking, "on", "off"));
        message.append(Component.literal("\n  consolidateFlushes: ")).append(onOff(config.consolidateFlushes, "on", "off"));
        message.append(Component.literal("\n  chunkCache: ")).append(onOff(config.chunkCache, "on", "off"));
        message.append(Component.literal("\n  parallelChunkLoads: ")).append(onOff(config.parallelChunkLoads, "on", "off"));
        message.append(Component.literal("\n  slowWaveMillis: " + config.slowWaveMillis));
        message.append(Component.literal("\n  detailedTickDiagnostics: ")).append(onOff(config.detailedTickDiagnostics, "on", "off"));

        ctx.getSource().sendSuccess(() -> message, false);
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

    private static MutableComponent onOff(boolean value, String on, String off) {
        return Component.literal(value ? on : off).withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer("vanadium")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }
}
