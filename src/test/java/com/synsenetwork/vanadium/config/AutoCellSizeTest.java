package com.synsenetwork.vanadium.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AutoCellSizeTest {
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
