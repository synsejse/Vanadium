package com.synsenetwork.vanadium

import com.synsenetwork.vanadium.commands.VanadiumCommand
import com.synsenetwork.vanadium.concurrent.Interactions
import com.synsenetwork.vanadium.config.DeveloperProperties
import com.synsenetwork.vanadium.config.VanadiumConfig
import com.synsenetwork.vanadium.config.VanadiumConfigSerializer
import com.synsenetwork.vanadium.diagnostics.ServerTickEvent
import com.synsenetwork.vanadium.diagnostics.TickBenchmark
import com.synsenetwork.vanadium.diagnostics.TickProfiler
import com.synsenetwork.vanadium.diagnostics.WaveDiagnostics
import com.synsenetwork.vanadium.entity.CrowdedSection
import com.synsenetwork.vanadium.navigation.NavigationAccess
import com.synsenetwork.vanadium.tick.IdleWorkers
import com.synsenetwork.vanadium.tick.ServerSpin
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WorkerPool
import java.util.function.LongSupplier
import me.shedaniel.autoconfig.AutoConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Util
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

    /** The idle-worker mode the running pool uses (the config's, from the next tick start); null when stopped. */
    fun idleWorkersActive(): IdleWorkers? = pool?.idleWorkers

    override fun onInitialize() {
        LOGGER.info("Initializing Vanadium...")
        DeveloperProperties.warnRetired()
        val holder = AutoConfig.register(VanadiumConfig::class.java) { definition, type -> VanadiumConfigSerializer(definition, type) }
        config = holder.config
        // Crowded-section grids are bypassed and rebuilt after an entity write ran without its owner's lock.
        CrowdedSection.unprotectedWrites = LongSupplier { Interactions.timeouts + Interactions.cycles }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> VanadiumCommand.register(dispatcher) }
        ServerLifecycleEvents.SERVER_STARTING.register(::onServerStarting)
        ServerTickEvents.START_SERVER_TICK.register(::onTickStart)
        ServerTickEvents.START_LEVEL_TICK.register { level ->
            scheduler.setDimension(level.dimension().identifier().toString())
            if (config.enabled) (level as NavigationAccess).`vanadium$navigations`().refresh()
        }
        ServerTickEvents.END_LEVEL_TICK.register { scheduler.finishWorld() }
        ServerTickEvents.END_SERVER_TICK.register(::onTickEnd)
        ServerLifecycleEvents.SERVER_STOPPED.register(::onServerStopped)
        LOGGER.info("Vanadium Initialized")
    }

    private fun onServerStarting(server: MinecraftServer) {
        // Validated before any thread starts: a mistyped experiment fails the launch instead of measuring `auto`.
        val spawnParticipants = DeveloperProperties.participantLimit(TickScheduler.SPAWN_PARTICIPANTS_PROPERTY)
        val workers = VanadiumConfig.resolveWorkers()
        if (config.workers <= 0) {
            LOGGER.info("Will use {} threads, isolation {} chunks ({})", workers, config.isolation, VanadiumConfig.describeAutoWorkers())
        } else {
            LOGGER.info("Will use {} threads, isolation {} chunks", workers, config.isolation)
        }
        val pool = WorkerPool(workers)
        this.pool = pool
        scheduler = TickScheduler(pool, config.isolation, spawnParticipants)
        if (spawnParticipants != null) {
            LOGGER.warn("Experimental: natural spawn attempts use at most {} of {} threads (-D{})",
                spawnParticipants, workers + 1, TickScheduler.SPAWN_PARTICIPANTS_PROPERTY)
        }
        val diagnostics = WaveDiagnostics(LOGGER::warn)
        this.diagnostics = diagnostics
        scheduler.diagnostics = diagnostics
    }

    private fun onTickStart(server: MinecraftServer) {
        val event = ServerTickEvent()
        event.begin()
        tickEvent = event
        val pool = pool
        if (pool != null) {
            pool.configureIdle(config.idleWorkersMode())
            pool.tickStarted()
        }
        config.refreshSerialRules()
        TickProfiler.onTickStart()
        diagnostics?.configure(config.slowWaveMillis, config.detailedTickDiagnostics)
    }

    private fun onTickEnd(server: MinecraftServer) {
        // How long the server will sleep before the next tick decides whether idle workers park now, and when
        // they are woken again.
        val pool = pool
        if (pool != null) {
            val nextTick = server.nextTickTime
            pool.tickEnded(nextTick - Util.getNanos(), server.tickRateManager().isSprinting)
            ServerSpin.arm(nextTick, pool.serverSpinNanos)
        }
        tickEvent?.commit()
        tickEvent = null
        TickBenchmark.onTickEnd(server)
        TickProfiler.onTickEnd()
    }

    private fun onServerStopped(server: MinecraftServer) {
        TickBenchmark.onServerStopped()
        TickProfiler.onServerStopped()
        diagnostics?.close()
        ServerSpin.disarm()
        pool?.close()
        diagnostics = null
        pool = null
        tickEvent = null
    }
}
