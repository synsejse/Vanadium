package com.synsenetwork.vanadium;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import com.synsenetwork.vanadium.commands.ConfigCommand;
import com.synsenetwork.vanadium.config.GeneralConfig;
import com.synsenetwork.vanadium.config.ThreadedRegionsConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Vanadium implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();
    public static GeneralConfig config;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Vanadium...");

        ConfigHolder<GeneralConfig> holder = AutoConfig.register(GeneralConfig.class, Toml4jConfigSerializer::new);
        holder.load();
        config = holder.getConfig();

        ConfigHolder<ThreadedRegionsConfig> trHolder = AutoConfig.register(ThreadedRegionsConfig.class, Toml4jConfigSerializer::new);
        trHolder.load();

        trHolder.getConfig().threadedChunksRegions.forEach(ParallelProcessor::addThreadedChunksRegion);

        LOGGER.info("Vanadium Setting up threadpool...");
        ParallelProcessor.setupThreadPool(GeneralConfig.getParallelism());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ConfigCommand.register(dispatcher));

        LOGGER.info("Vanadium Initialized");
    }
}
