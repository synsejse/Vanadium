package com.synsenetwork.vanadium.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VanadiumConfigTest {
    // --- resolveWorkers(workers, cores) --------------------------------------

    @Test void nonPositiveWorkersMeansAllCores() {
        assertEquals(72, VanadiumConfig.resolveWorkers(0, 72));
        assertEquals(8, VanadiumConfig.resolveWorkers(-1, 8));
    }

    @Test void positiveWorkersCapsThreads() {
        assertEquals(16, VanadiumConfig.resolveWorkers(16, 72));
    }

    @Test void flooredAtTwo() {
        assertEquals(2, VanadiumConfig.resolveWorkers(1, 72));
    }

    @Test void cappedAtCoreCount() {
        assertEquals(72, VanadiumConfig.resolveWorkers(200, 72));
    }

    @Test void singleCoreMachineStillValid() {
        assertEquals(2, VanadiumConfig.resolveWorkers(16, 1)); // explicit cap: floor of 2 still wins
        assertEquals(1, VanadiumConfig.resolveWorkers(0, 1));  // auto: exactly the core count
    }

    // --- autoCellSize(parallelism) — unchanged heuristic ----------------------

    @Test void smallerOnMoreCores() {
        assertTrue(VanadiumConfig.autoCellSize(16) <= VanadiumConfig.autoCellSize(4),
                "more cores should not give larger cells");
    }

    @Test void clampedToRange() {
        for (int p = 1; p <= 256; p++) {
            int size = VanadiumConfig.autoCellSize(p);
            assertTrue(size >= 2 && size <= 8, "cellSize out of [2,8] at parallelism " + p);
        }
    }

    @Test void handlesNonPositiveParallelism() {
        assertTrue(VanadiumConfig.autoCellSize(0) >= 2);
        assertTrue(VanadiumConfig.autoCellSize(-1) >= 2);
    }
}
