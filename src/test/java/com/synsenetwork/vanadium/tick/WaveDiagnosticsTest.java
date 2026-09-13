package com.synsenetwork.vanadium.tick;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WaveDiagnosticsTest {
    @Test void disabledDiagnosticsDoNotCreateWatches() {
        var reports = new ArrayList<String>();
        try (var diagnostics = new WaveDiagnostics(reports::add, () -> 0, false)) {
            diagnostics.configure(0, true);
            assertFalse(diagnostics.detailed());
            assertNull(diagnostics.watch(Stage.ENTITY, "test:world", 0, 3));
            diagnostics.poll();
            assertTrue(reports.isEmpty());
        }
    }

    @Test void reportsBlockedCallerAndWorkerWithLabelsAndRateLimit() throws Exception {
        var reports = new ArrayList<String>();
        AtomicLong clock = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        WorkerPool pool = new WorkerPool(1);
        var caller = Executors.newSingleThreadExecutor(task -> new Thread(task, "diagnostic-caller"));
        try (var diagnostics = new WaveDiagnostics(reports::add, clock::get, false)) {
            diagnostics.configure(100, true);
            Runnable blocked = () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout");
                    completed.incrementAndGet();
                } catch (InterruptedException e) {
                    throw new AssertionError("diagnostics interrupted work", e);
                }
            };
            Cell left = new Cell(-2, 0);
            Cell right = new Cell(0, 0);
            left.add(new WaveDiagnostics.NamedTask("example:machine", blocked));
            right.add(new WaveDiagnostics.NamedTask("minecraft:pig", blocked));
            var watch = diagnostics.watch(Stage.BLOCK_ENTITY, "test:dimension", 0, 3);
            var wave = caller.submit(() -> {
                try (watch) { pool.runWave(watch.wrap(List.of(left, right))); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            clock.set(TimeUnit.MILLISECONDS.toNanos(99));
            diagnostics.poll();
            assertTrue(reports.isEmpty());
            clock.set(TimeUnit.MILLISECONDS.toNanos(100));
            diagnostics.poll();
            assertEquals(1, reports.size());
            String report = reports.getFirst();
            for (String expected : List.of("BLOCK_ENTITY", "test:dimension", "cell=(-2,0)", "cell=(0,0)",
                    "example:machine", "minecraft:pig", "diagnostic-caller", "Vanadium-Worker-", "\n  at ")) {
                assertTrue(report.contains(expected), report);
            }
            assertEquals(0, completed.get());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            diagnostics.poll();
            assertEquals(1, reports.size());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            diagnostics.poll();
            assertEquals(2, reports.size());
            release.countDown();
            wave.get(5, TimeUnit.SECONDS);
            assertEquals(2, completed.get());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            diagnostics.poll();
            assertEquals(2, reports.size(), "completed wave was retained");
        } finally {
            release.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            pool.shutdown();
        }
    }

    @Test void singleCellStillRunsInlineAndFailureClearsWatch() {
        AtomicLong clock = new AtomicLong();
        var reports = new ArrayList<String>();
        WorkerPool pool = new WorkerPool(1);
        try (var diagnostics = new WaveDiagnostics(reports::add, clock::get, false)) {
            diagnostics.configure(1, false);
            TickScheduler scheduler = new TickScheduler(pool, 2);
            scheduler.setDiagnostics(diagnostics);
            scheduler.setDimension("test:inline");
            scheduler.begin(Stage.ENTITY);
            Thread caller = Thread.currentThread();
            scheduler.enqueue(Stage.ENTITY, -1, -1, () -> {
                assertSame(caller, Thread.currentThread());
                clock.set(TimeUnit.MILLISECONDS.toNanos(2));
                diagnostics.poll();
                throw new IllegalStateException("fixture");
            });
            assertThrows(IllegalStateException.class, () -> scheduler.run(Stage.ENTITY));
            assertEquals(1, reports.size());
            assertTrue(reports.getFirst().contains("cell=(-1,-1)"));
            clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
            diagnostics.poll();
            assertEquals(1, reports.size());
        } finally {
            pool.shutdown();
        }
    }
}
