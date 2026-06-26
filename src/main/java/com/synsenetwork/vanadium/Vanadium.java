package com.synsenetwork.vanadium;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import com.synsenetwork.vanadium.commands.ConfigCommand;
import com.synsenetwork.vanadium.commands.DebugCommand;
import com.synsenetwork.vanadium.config.VanadiumConfig;
import com.synsenetwork.vanadium.debug.DebugDispatcher;
import com.synsenetwork.vanadium.debug.DebugFramePayload;
import com.synsenetwork.vanadium.tick.SchedulerStats;
import com.synsenetwork.vanadium.tick.TickScheduler;
import com.synsenetwork.vanadium.tick.WorkerPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Vanadium implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();
    public static VanadiumConfig config;
    public static TickScheduler scheduler;
    public static DebugDispatcher debug;
    public static SchedulerStats stats;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Vanadium...");

        ConfigHolder<VanadiumConfig> holder = AutoConfig.register(VanadiumConfig.class, Toml4jConfigSerializer::new);
        holder.load();
        config = holder.getConfig();

        stats = new SchedulerStats();
        stats.setWorkers(VanadiumConfig.getParallelism());

        WorkerPool pool = new WorkerPool(VanadiumConfig.getParallelism());
        scheduler = new TickScheduler(pool, VanadiumConfig.resolveCellSize(), stats);

        debug = new DebugDispatcher(VanadiumConfig.resolveCellSize(), stats);
        PayloadTypeRegistry.playS2C().register(DebugFramePayload.ID, DebugFramePayload.CODEC);
        debug.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ConfigCommand.register(dispatcher);
            DebugCommand.register(dispatcher);
        });

        LOGGER.info("Vanadium Initialized");
    }
}
