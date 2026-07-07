package com.synsenetwork.vanadium;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import com.synsenetwork.vanadium.config.VanadiumConfig;
import com.synsenetwork.vanadium.tick.TickScheduler;
import com.synsenetwork.vanadium.tick.WorkerPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Vanadium implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();
    public static VanadiumConfig config;
    public static TickScheduler scheduler;
    /** Worker count the pool was actually built with; /vanadium status flags drift from config. */
    public static int bootWorkers;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Vanadium...");

        ConfigHolder<VanadiumConfig> holder = AutoConfig.register(VanadiumConfig.class, Toml4jConfigSerializer::new);
        holder.load();
        config = holder.getConfig();

        int workers = VanadiumConfig.resolveWorkers();
        int cellSize = VanadiumConfig.resolveCellSize();
        bootWorkers = workers;
        LOGGER.info("Will use {} threads and cell size {} by {} chunks", workers, cellSize, cellSize);

        WorkerPool pool = new WorkerPool(workers);
        scheduler = new TickScheduler(pool, cellSize);

        LOGGER.info("Vanadium Initialized");
    }
}
