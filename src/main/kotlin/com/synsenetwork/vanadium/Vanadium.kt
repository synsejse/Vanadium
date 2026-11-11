package com.synsenetwork.vanadium

import com.synsenetwork.vanadium.commands.TickBenchmark
import com.synsenetwork.vanadium.commands.TickProfiler
import com.synsenetwork.vanadium.commands.VanadiumCommand
import com.synsenetwork.vanadium.config.VanadiumConfig
import com.synsenetwork.vanadium.tick.ServerTickEvent
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WaveDiagnostics
import com.synsenetwork.vanadium.tick.WorkerPool
import com.synsenetwork.vanadium.tracking.NavigationAccess
import me.shedaniel.autoconfig.AutoConfig
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/** Mod entry point. Owns the config and, per server lifecycle, the worker pool and tick scheduler. */
object Vanadium : ModInitializer {
    @JvmField
    val LOGGER: Logger = LogManager.getLogger("Vanadium")

    /** Replaced on every config load; mixins read it as a static field. */
    @JvmField
    var config = VanadiumConfig()

    /** Created at server start; mixins read it as a static field. */
    lateinit var scheduler: TickScheduler

    private var pool: WorkerPool? = null
    private var diagnostics: WaveDiagnostics? = null
    private var tickEvent: ServerTickEvent? = null

    override fun onInitialize() {
        LOGGER.info("Initializing Vanadium...")
        val holder = AutoConfig.register(VanadiumConfig::class.java) { definition, type -> Toml4jConfigSerializer(definition, type) }
        holder.load()
        config = holder.config

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> VanadiumCommand.register(dispatcher) }
        ServerLifecycleEvents.SERVER_STARTING.register(::onServerStarting)
        ServerTickEvents.START_SERVER_TICK.register(::onTickStart)
        ServerTickEvents.START_LEVEL_TICK.register { level ->
            scheduler.setDimension(level.dimension().identifier().toString())
            if (config.enabled) (level as NavigationAccess).`vanadium$navigations`().refresh()
        }
        ServerTickEvents.END_LEVEL_TICK.register { scheduler.finishWorld() }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            tickEvent?.commit()
            tickEvent = null
            TickBenchmark.onTickEnd(server)
            TickProfiler.onTickEnd()
        }
        ServerLifecycleEvents.SERVER_STOPPED.register(::onServerStopped)
        LOGGER.info("Vanadium Initialized")
    }

    private fun onServerStarting(server: MinecraftServer) {
        val workers = VanadiumConfig.resolveWorkers()
        LOGGER.info("Will use {} threads, isolation {} chunks", workers, config.isolation)
        if (config.flatEntities) LOGGER.info("Entities tick in one flat wave with per-entity ownership")
        val pool = WorkerPool(workers).also { pool = it }
        scheduler = TickScheduler(pool, config.isolation)
        diagnostics = WaveDiagnostics(LOGGER::warn).also { scheduler.diagnostics = it }
    }

    private fun onTickStart(server: MinecraftServer) {
        tickEvent = ServerTickEvent().also { it.begin() }
        config.refreshSerialRules()
        TickProfiler.onTickStart()
        diagnostics?.configure(config.slowWaveMillis, config.detailedTickDiagnostics)
    }

    private fun onServerStopped(server: MinecraftServer) {
        TickBenchmark.onServerStopped()
        TickProfiler.onServerStopped()
        diagnostics?.close()
        pool?.close()
        diagnostics = null
        pool = null
        tickEvent = null
    }
}
