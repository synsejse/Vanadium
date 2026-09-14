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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
        ServerLifecycleEvents.SERVER_STARTING.register(Vanadium::onServerStarting);
        ServerTickEvents.START_SERVER_TICK.register(Vanadium::onTickStart);
        ServerTickEvents.START_LEVEL_TICK.register(Vanadium::onWorldTickStart);
        ServerTickEvents.END_SERVER_TICK.register(Vanadium::onTickEnd);
        ServerLifecycleEvents.SERVER_STOPPED.register(Vanadium::onServerStopped);

        LOGGER.info("Vanadium Initialized");
    }

    private static void onServerStarting(MinecraftServer server) {
        diagnostics = new WaveDiagnostics(LOGGER::warn);
        scheduler.setDiagnostics(diagnostics);
    }

    private static void onTickStart(MinecraftServer server) {
        config.refreshSerialRules();
        TickProfiler.onTickStart(server);
        diagnostics.configure(config.slowWaveMillis, config.detailedTickDiagnostics);
    }

    private static void onWorldTickStart(ServerLevel world) {
        if (diagnostics.enabled()) {
            scheduler.setDimension(world.dimension().identifier().toString());
        }
    }

    private static void onTickEnd(MinecraftServer server) {
        TickBenchmark.onTickEnd(server);
        TickProfiler.onTickEnd(server);
    }

    private static void onServerStopped(MinecraftServer server) {
        TickProfiler.onServerStopped(server);
        diagnostics.close();
        scheduler.setDiagnostics(null);
        diagnostics = null;
    }
}
