package com.synsenetwork.vanadium;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import com.synsenetwork.vanadium.commands.TickProfiler;
import com.synsenetwork.vanadium.commands.TickBenchmark;
import com.synsenetwork.vanadium.commands.VanadiumCommand;
import com.synsenetwork.vanadium.config.VanadiumConfig;
import com.synsenetwork.vanadium.tick.TickScheduler;
import com.synsenetwork.vanadium.tick.WorkerPool;
import com.synsenetwork.vanadium.tick.WaveDiagnostics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Vanadium implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();
    public static VanadiumConfig config;
    public static TickScheduler scheduler;
    private static WaveDiagnostics diagnostics;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Vanadium...");

        ConfigHolder<VanadiumConfig> holder = AutoConfig.register(VanadiumConfig.class, Toml4jConfigSerializer::new);
        holder.load();
        config = holder.getConfig();

        int workers = VanadiumConfig.resolveWorkers();
        int cellSize = VanadiumConfig.resolveCellSize();
        LOGGER.info("Will use {} threads and cell size {} by {} chunks", workers, cellSize, cellSize);

        WorkerPool pool = new WorkerPool(workers);
        scheduler = new TickScheduler(pool, cellSize);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                VanadiumCommand.register(dispatcher));
        ServerTickEvents.START_SERVER_TICK.register(server -> config.refreshSerialRules());
        ServerTickEvents.END_SERVER_TICK.register(TickBenchmark::onTickEnd);
        ServerTickEvents.START_SERVER_TICK.register(TickProfiler::onTickStart);
        ServerTickEvents.END_SERVER_TICK.register(TickProfiler::onTickEnd);
        ServerLifecycleEvents.SERVER_STOPPED.register(TickProfiler::onServerStopped);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            diagnostics = new WaveDiagnostics(LOGGER::warn);
            scheduler.setDiagnostics(diagnostics);
        });
        ServerTickEvents.START_SERVER_TICK.register(server ->
                diagnostics.configure(config.slowWaveMillis, config.detailedTickDiagnostics));
        ServerTickEvents.START_WORLD_TICK.register(world -> {
            if (diagnostics.enabled()) scheduler.setDimension(world.getRegistryKey().getValue().toString());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            diagnostics.close();
            scheduler.setDiagnostics(null);
            diagnostics = null;
        });

        LOGGER.info("Vanadium Initialized");
    }
}
