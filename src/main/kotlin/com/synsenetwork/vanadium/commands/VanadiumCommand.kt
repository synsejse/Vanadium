package com.synsenetwork.vanadium.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.config.LiveOptions
import com.synsenetwork.vanadium.config.VanadiumConfig
import me.shedaniel.autoconfig.AutoConfig
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.permissions.Permissions
import java.lang.reflect.Modifier

/** Registers /vanadium status, live options, config reload, profiling, and benchmarking commands. */
object VanadiumCommand {
    private const val SUCCESS = 1
    private const val FAILURE = 0

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val operator = { source: CommandSourceStack -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
        dispatcher.register(
            literal("vanadium")
                .executes(::status)
                .then(literal("status").executes(::status))
                .then(literal("profile").requires(operator)
                    .executes { profile(it, 30) }
                    .then(argument("seconds", IntegerArgumentType.integer(1, 300))
                        .executes { profile(it, IntegerArgumentType.getInteger(it, "seconds")) })
                    .then(literal("stop").executes {
                        if (TickProfiler.stop()) SUCCESS else fail(it, "no profile is running")
                    })
                    .then(literal("report").executes {
                        it.source.sendSuccess({ Component.literal(TickProfiler.lastReport()) }, false)
                        SUCCESS
                    }))
                .then(literal("set").requires(operator)
                    .then(argument("option", StringArgumentType.word())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(LiveOptions.names, builder) }
                        .then(argument("value", StringArgumentType.word())
                            .suggests { ctx, builder ->
                                val option = StringArgumentType.getString(ctx, "option")
                                if (LiveOptions.isBoolean(option)) SharedSuggestionProvider.suggest(listOf("on", "off"), builder)
                                else builder.buildFuture()
                            }
                            .executes(::set))))
                .then(literal("reload").requires(operator).executes(::reload))
                .then(literal("benchmark").requires(operator)
                    .executes { benchmark(it, TickBenchmark.DEFAULT_SECONDS) }
                    .then(argument("seconds", IntegerArgumentType.integer(5, 300))
                        .executes { benchmark(it, IntegerArgumentType.getInteger(it, "seconds")) })))
    }

    /** Lists every option from the config class, so new options appear without command changes. */
    private fun status(ctx: CommandContext<CommandSourceStack>): Int {
        val config = Vanadium.config
        val message = Component.literal("Vanadium ${version()} — ").append(onOff(config.enabled, "enabled", "disabled"))
        for (field in VanadiumConfig::class.java.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || Modifier.isTransient(field.modifiers) || field.name == "enabled") continue
            message.append("\n  ${field.name}: ")
            when (field.name) {
                "workers" -> {
                    val resolved = VanadiumConfig.resolveWorkers()
                    val active = Vanadium.scheduler.workerCount
                    message.append("$active active (config ${config.workers} → $resolved)")
                    if (resolved != active) message.append(Component.literal(" restart pending").withStyle(ChatFormatting.YELLOW))
                }
                "cellSize" -> message.append(config.cellSize.toString() +
                        if (config.cellSize == 0) " (auto → ${VanadiumConfig.resolveCellSize()})" else "")
                else -> when (val value = field.get(config)) {
                    is Boolean -> message.append(onOff(value, "on", "off"))
                    is List<*> -> message.append(if (value.isEmpty()) "none" else value.joinToString(", "))
                    else -> message.append(value.toString())
                }
            }
        }
        ctx.source.sendSuccess({ message }, false)
        return SUCCESS
    }

    private fun reload(ctx: CommandContext<CommandSourceStack>): Int {
        val holder = AutoConfig.getConfigHolder(VanadiumConfig::class.java)
        val loaded = holder.load()
        Vanadium.config = holder.config // load() builds a fresh instance; consumers read the static field
        if (!loaded) {
            return fail(ctx, "config reload failed — check the file and server log; /vanadium status shows active values")
        }
        feedback(ctx, "config reloaded from disk")
        return SUCCESS
    }

    /** Runtime-only change for A/B comparisons; `/vanadium reload` restores the file's values. */
    private fun set(ctx: CommandContext<CommandSourceStack>): Int {
        val option = StringArgumentType.getString(ctx, "option")
        LiveOptions.set(Vanadium.config, option, StringArgumentType.getString(ctx, "value"))?.let { return fail(ctx, it) }
        val shown = when (val value = LiveOptions.get(Vanadium.config, option)) {
            is Boolean -> if (value) "on" else "off"
            else -> value.toString()
        }
        feedback(ctx, "$option = $shown (not saved; /vanadium reload restores the config file)")
        return SUCCESS
    }

    private fun benchmark(ctx: CommandContext<CommandSourceStack>, seconds: Int): Int {
        if (!TickBenchmark.start(ctx.source.server, ctx.source, seconds)) {
            return fail(ctx, "a benchmark (or /tick sprint) is already running")
        }
        feedback(ctx, "benchmark started — TPS unlocked for the next ${seconds}s")
        return SUCCESS
    }

    private fun profile(ctx: CommandContext<CommandSourceStack>, seconds: Int): Int {
        if (!TickProfiler.start(ctx.source, seconds)) return fail(ctx, "a profile is already running")
        feedback(ctx, "profiling for ${seconds}s at the current tick rate; /vanadium profile report shows the last result")
        return SUCCESS
    }

    private fun feedback(ctx: CommandContext<CommandSourceStack>, text: String) {
        val message = Component.literal("Vanadium: $text")
        ctx.source.sendSuccess({ message }, true)
    }

    private fun fail(ctx: CommandContext<CommandSourceStack>, text: String): Int {
        ctx.source.sendFailure(Component.literal("Vanadium: $text"))
        return FAILURE
    }

    private fun onOff(value: Boolean, on: String, off: String): MutableComponent =
        Component.literal(if (value) on else off).withStyle(if (value) ChatFormatting.GREEN else ChatFormatting.RED)

    private fun version(): String = FabricLoader.getInstance().getModContainer("vanadium")
        .map { it.metadata.version.friendlyString }
        .orElse("?")
}
