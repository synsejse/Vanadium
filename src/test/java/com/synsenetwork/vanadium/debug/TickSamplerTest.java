package com.synsenetwork.vanadium.debug;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TickSamplerTest {

    private static final RegistryKey<World> W = World.OVERWORLD;

    @Test
    void keepsOnlySamplesInsideTheRequestedCell() {
        var sampler = new TickSampler(8); // 8 chunks per cell -> cell (0,0) covers blocks [0,128)

        sampler.recordEntity(W, 1, 10.0, 64.0, 10.0, 100L);   // chunk (0,0)   -> cell (0,0)  KEEP
        sampler.recordEntity(W, 2, 200.0, 64.0, 10.0, 50L);   // chunk (12,0)  -> cell (1,0)  DROP
        sampler.recordChunk(W, 0, 0, 5L);                     // cell (0,0)                  KEEP
        sampler.recordChunk(W, 8, 0, 7L);                     // chunk (8,0)   -> cell (1,0)  DROP
        sampler.recordBlockEntity(W, new BlockPos(5, 64, 5).asLong(), 12L);   // cell (0,0)  KEEP
        sampler.recordBlockEntity(W, new BlockPos(200, 64, 5).asLong(), 3L);  // cell (1,0)  DROP

        DebugFramePayload frame = sampler.frameFor(W, 0, 0); // player in chunk (0,0) -> cell (0,0)

        assertEquals(8, frame.cellSize());
        assertEquals(1, frame.entities().size());
        assertEquals(1, frame.entities().get(0).id());
        assertEquals(100L, frame.entities().get(0).nanos());
        assertEquals(1, frame.chunks().size());
        assertEquals(0, frame.chunks().get(0).chunkX());
        assertEquals(1, frame.blockEntities().size());
        assertEquals(12L, frame.blockEntities().get(0).nanos());
    }

    @Test
    void resetClearsAllSamples() {
        var sampler = new TickSampler(8);
        sampler.recordEntity(W, 1, 0.0, 0.0, 0.0, 1L);
        sampler.reset();

        DebugFramePayload frame = sampler.frameFor(W, 0, 0);
        assertTrue(frame.entities().isEmpty());
        assertTrue(frame.blockEntities().isEmpty());
        assertTrue(frame.chunks().isEmpty());
    }
}
