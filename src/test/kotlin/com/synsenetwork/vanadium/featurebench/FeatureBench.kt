package com.synsenetwork.vanadium.featurebench

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.Stage
import com.synsenetwork.vanadium.tick.TickProfile
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WorkerPool
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import java.nio.file.Files
import java.nio.file.Path

/** Paired feature benchmarks at server start; packaged only by featureBenchJar. */
class FeatureBench : ModInitializer {
    override fun onInitialize() {
        com.synsenetwork.vanadium.live.LiveServer.start()
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val previous = Vanadium.scheduler
            val workers = Integer.getInteger("vanadium.bench.workers", 4)
            try {
                WorkerPool(workers).use { pool ->
                    Measurements().use { measurements ->
                        Vanadium.scheduler = TickScheduler(pool, 2)
                        Vanadium.config.enabled = true
                        Files.writeString(Path.of("feature-bench/environment.txt"),
                            "java=${System.getProperty("java.runtime.version")}\nworkers=$workers\nisolation=2\n" +
                                    "processors=${Runtime.getRuntime().availableProcessors()}\nmaxHeap=${Runtime.getRuntime().maxMemory()}\n" +
                                    "os=${System.getProperty("os.name")} ${System.getProperty("os.arch")}\n" +
                                    "jvmArgs=${java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments}\n" +
                                    "filter=${System.getProperty("vanadium.bench.filter", "")}\n" +
                                    "rounds=${System.getProperty("vanadium.bench.rounds", "12")}\n" +
                                    "scale=${System.getProperty("vanadium.bench.scale", "1")}\n" +
                                    net.fabricmc.loader.api.FabricLoader.getInstance().allMods.joinToString("\n") {
                                        "${it.metadata.id}=${it.metadata.version.friendlyString}"
                                    } + "\n")
                        val level = server.overworld()
                        NavigationTrackingBench.navigation(level, measurements)
                        NavigationTrackingBench.tracking(level, measurements, 256)
                        NavigationTrackingBench.tracking(level, measurements, 2048)
                        SpawnPacketBench.spawning(level, measurements)
                        LockBench.items(level, measurements)
                        LockBench.neighbors(level, measurements)
                        SpawnPacketBench.packets(level, measurements)
                        profile(measurements, 0)
                        profile(measurements, 2000)
                        println("FEATURE_BENCH_PASSED")
                        com.synsenetwork.vanadium.live.LiveServer.finish(server, "featureBench")
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException("Feature benchmark failed", e)
            } finally {
                Vanadium.scheduler = previous
            }
        }
    }

    private fun profile(measurements: Measurements, work: Int) {
        val output = LongArray(128)
        val tasks = Array(128) { index ->
            Runnable {
                var value = output[index]
                repeat(work) { value = value * 1664525 + 1013904223 }
                output[index] = value
            }
        }
        val profile = TickProfile()
        measurements.compare("profiler-128-cells-work-$work", { on -> Vanadium.scheduler.profile = if (on) profile else null }) {
            val scheduler = Vanadium.scheduler
            scheduler.setDimension("benchmark")
            scheduler.begin(Stage.ENTITY)
            tasks.forEachIndexed { i, task -> scheduler.enqueue(Stage.ENTITY, i % 16 * 2, i / 16 * 2, task, null) }
            scheduler.run(Stage.ENTITY)
            scheduler.finishWorld()
        }
        Vanadium.scheduler.profile = null
        sink = output
    }

    companion object {
        /** Read by the TrackingPreparationSwitch test mixin. */
        @JvmField
        @Volatile
        var parallelTrackingPreparation = true

        @Volatile
        internal var sink: Any? = null
    }
}
