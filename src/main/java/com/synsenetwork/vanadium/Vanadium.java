package com.synsenetwork.vanadium;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import com.synsenetwork.vanadium.commands.ConfigCommand;
import com.synsenetwork.vanadium.config.VanadiumConfig;
import com.synsenetwork.vanadium.tick.TickScheduler;
import com.synsenetwork.vanadium.tick.WorkerPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Vanadium implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();
    public static VanadiumConfig config;
    public static TickScheduler scheduler;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Vanadium...");

        ConfigHolder<VanadiumConfig> holder = AutoConfig.register(VanadiumConfig.class, Toml4jConfigSerializer::new);
        holder.load();
        config = holder.getConfig();

        int parallelism = VanadiumConfig.getParallelism();
        int cellSize = VanadiumConfig.resolveCellSize();
        LOGGER.info("Will use {} threads and cell size {} by {} chunks", parallelism, cellSize, cellSize);

        WorkerPool pool = new WorkerPool(parallelism);
        scheduler = new TickScheduler(pool, cellSize);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ConfigCommand.register(dispatcher);
        });

        LOGGER.info("Vanadium Initialized");
    }
}
