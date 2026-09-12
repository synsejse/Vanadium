package com.synsenetwork.vanadium.bench;

import com.sun.management.ThreadMXBean;
import com.synsenetwork.vanadium.tracking.AreaMap;
import net.minecraft.util.math.ChunkPos;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/** Synthetic index maintenance benchmark; no Minecraft server or performance assertions. */
public final class AreaMapBench {
    private static final int WARMUP_MILLIS = 250;
    private static final int MEASURED = 50000;
    private static volatile int sink;

    public static void main(String[] args) {
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        System.out.printf("warmupMs=%d measured=%d thread=caller java=%s%n", WARMUP_MILLIS, MEASURED,
                System.getProperty("java.version"));
        System.out.println("radius,workload,ns/update,bytes/update");
        for (int radius : new int[]{2, 8, 16}) {
            for (String workload : new String[]{"stationary", "one-chunk", "diagonal", "teleport", "resize"}) {
                AreaMap<Object> map = new AreaMap<>();
                Object mover = new Object();
                map.add(new Object(), 0, 0, radius + 2); // overlapping, stationary coverage
                map.add(mover, 0, 0, radius);
                int dx = workload.equals("teleport") ? radius * 3 : 1;
                int dz = workload.equals("diagonal") ? 1 : 0;
                if (workload.equals("stationary") || workload.equals("resize")) dx = 0;
                int nextRadius = workload.equals("resize") ? radius + 1 : radius;
                long warmupEnd = System.nanoTime() + WARMUP_MILLIS * 1_000_000L;
                do {
                    for (int i = 0; i < 1000; i++) update(map, mover, i, radius, dx, dz, nextRadius);
                } while (System.nanoTime() < warmupEnd);
                long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                for (int i = 0; i < MEASURED; i++) update(map, mover, i, radius, dx, dz, nextRadius);
                long elapsed = System.nanoTime() - start;
                bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes;
                sink += map.objectsAt(ChunkPos.toLong(0, 0)).size();
                System.out.printf(Locale.ROOT, "%d,%s,%.1f,%d%n", radius, workload,
                        (double) elapsed / MEASURED, bytes / MEASURED);
            }
        }
        System.out.println("sink=" + sink);
    }

    private static void update(AreaMap<Object> map, Object mover, int i, int radius,
                               int dx, int dz, int nextRadius) {
        boolean moved = (i & 1) == 0;
        map.update(mover, moved ? dx : 0, moved ? dz : 0, moved ? nextRadius : radius);
    }
}
