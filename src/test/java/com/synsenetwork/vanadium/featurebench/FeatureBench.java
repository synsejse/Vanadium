package com.synsenetwork.vanadium.featurebench;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tick.TickProfile;
import com.synsenetwork.vanadium.tick.TickScheduler;
import com.synsenetwork.vanadium.tick.WorkerPool;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class FeatureBench implements ModInitializer {
    public static volatile boolean parallelTrackingPreparation = true;
    static volatile Object sink;

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            TickScheduler previous = Vanadium.scheduler;
            int workers = Integer.getInteger("vanadium.bench.workers", 4);
            try (WorkerPool pool = new WorkerPool(workers); Measurements measurements = new Measurements()) {
                Vanadium.scheduler = new TickScheduler(pool, 2);
                Vanadium.config.enabled = true;
                Files.writeString(Path.of("feature-bench/environment.txt"),
                        "java=" + System.getProperty("java.runtime.version") + "\nworkers=" + workers
                        + "\ncellSize=2\nprocessors=" + Runtime.getRuntime().availableProcessors()
                        + "\nmaxHeap=" + Runtime.getRuntime().maxMemory() + "\n");
                var level = server.overworld();
                NavigationTrackingBench.navigation(level, measurements);
                NavigationTrackingBench.tracking(level, measurements, 256);
                NavigationTrackingBench.tracking(level, measurements, 2048);
                SpawnPacketBench.spawning(level, measurements);
                LockBench.items(level, measurements);
                LockBench.neighbors(level, measurements);
                SpawnPacketBench.packets(level, measurements);
                profile(measurements, 0);
                profile(measurements, 2000);
                System.out.println("FEATURE_BENCH_PASSED");
            } catch (Exception e) {
                throw new IllegalStateException("Feature benchmark failed", e);
            } finally {
                Vanadium.scheduler = previous;
            }
        });
    }

    private static void profile(Measurements measurements, int work) throws Exception {
        long[] output = new long[128];
        Runnable[] tasks = new Runnable[128];
        for (int i = 0; i < tasks.length; i++) {
            int index = i;
            tasks[i] = () -> {
                long value = output[index];
                for (int n = 0; n < work; n++) value = value * 1664525 + 1013904223;
                output[index] = value;
            };
        }
        TickProfile profile = new TickProfile();
        measurements.compare("profiler-128-cells-work-" + work,
                on -> Vanadium.scheduler.setProfile(on ? profile : null), () -> {
                    Vanadium.scheduler.setDimension("benchmark");
                    Vanadium.scheduler.begin(Stage.ENTITY);
                    for (int i = 0; i < tasks.length; i++) {
                        Vanadium.scheduler.enqueue(Stage.ENTITY, i % 16 * 2, i / 16 * 2, tasks[i], null);
                    }
                    Vanadium.scheduler.run(Stage.ENTITY);
                    Vanadium.scheduler.finishWorld();
                });
        Vanadium.scheduler.setProfile(null);
        sink = output;
    }
}
