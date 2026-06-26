package com.synsenetwork.vanadium.tick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerStatsTest {

    @Test
    void accumulatesAcrossTicksAndStages() {
        SchedulerStats stats = new SchedulerStats();
        stats.setWorkers(8);
        stats.markTick();
        stats.markTick();
        stats.recordStage(Stage.CHUNK, 1000, 3, 30);
        stats.recordStage(Stage.ENTITY, 2000, 5, 50);
        stats.recordStage(Stage.BLOCK_ENTITY, 500, 2, 8);
        stats.recordStage(Stage.CHUNK, 1000, 3, 30); // a second tick's CHUNK stage adds on
        stats.addWork(4000);
        stats.cacheHit();
        stats.cacheHit();
        stats.cacheMiss();
        stats.cacheBounce();

        SchedulerStats.Snapshot s = stats.snapshotAndReset();
        assertEquals(2, s.ticks());
        assertEquals(8, s.workers());
        assertEquals(2000, s.chunkNanos());        // 1000 + 1000
        assertEquals(2000, s.entityNanos());
        assertEquals(500, s.blockEntityNanos());
        assertEquals(4000, s.workNanos());
        assertEquals(3 + 5 + 2 + 3, s.cellsRun());  // summed across all stages
        assertEquals(60, s.chunksTicked());         // 30 + 30
        assertEquals(50, s.entitiesTicked());
        assertEquals(8, s.blockEntitiesTicked());
        assertEquals(2, s.cacheHits());
        assertEquals(1, s.cacheMisses());
        assertEquals(1, s.cacheBounces());
    }

    @Test
    void snapshotResetsForNextWindow() {
        SchedulerStats stats = new SchedulerStats();
        stats.markTick();
        stats.recordStage(Stage.ENTITY, 1234, 1, 5);
        stats.addWork(99);
        stats.cacheHit();
        stats.snapshotAndReset();

        SchedulerStats.Snapshot s = stats.snapshotAndReset();
        assertEquals(0, s.ticks());
        assertEquals(0, s.entityNanos());
        assertEquals(0, s.workNanos());
        assertEquals(0, s.cellsRun());
        assertEquals(0, s.cacheHits());
    }

    @Test
    void enabledFlagTogglable() {
        SchedulerStats stats = new SchedulerStats();
        assertFalse(stats.isEnabled());
        stats.setEnabled(true);
        assertTrue(stats.isEnabled());
        stats.setEnabled(false);
        assertFalse(stats.isEnabled());
    }
}
