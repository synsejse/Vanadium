package com.synsenetwork.vanadium.tick;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CellPosTest {
    @Test void fromChunkGroupsByCellSize() {
        assertEquals(new CellPos(0, 0), CellPos.fromChunk(0, 0, 8));
        assertEquals(new CellPos(0, 0), CellPos.fromChunk(7, 7, 8));
        assertEquals(new CellPos(1, 0), CellPos.fromChunk(8, 0, 8));
        assertEquals(new CellPos(-1, -1), CellPos.fromChunk(-1, -1, 8));
    }

    @Test void colorIsAlwaysZeroToThree() {
        for (int x = -10; x <= 10; x++)
            for (int z = -10; z <= 10; z++) {
                int c = new CellPos(x, z).color();
                assertTrue(c >= 0 && c <= 3, "color out of range at " + x + "," + z);
            }
    }

    @Test void sameColorCellsAreNeverAdjacent() {
        int[][] neighbours = {{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) {
                int c = new CellPos(x, z).color();
                for (int[] d : neighbours) {
                    int nc = new CellPos(x + d[0], z + d[1]).color();
                    assertNotEquals(c, nc,
                        "cell (" + x + "," + z + ") shares color with neighbour");
                }
            }
    }
}
