package com.synsenetwork.vanadium.tick;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CellTest {
    @Test void runsTasksInInsertionOrder() {
        List<Integer> log = new ArrayList<>();
        Cell cell = new Cell(0, 0);
        cell.add(() -> log.add(1));
        cell.add(() -> log.add(2));
        cell.add(() -> log.add(3));
        cell.run();
        assertEquals(List.of(1, 2, 3), log);
    }

    @Test void colorReflectsCellCoordinates() {
        assertEquals(1, new Cell(1, 0).color());
        assertEquals(2, new Cell(0, -1).color());
        assertEquals(3, new Cell(-1, -1).color());
    }

    @Test void hasTasksReflectsContent() {
        Cell cell = new Cell(0, 0);
        assertFalse(cell.hasTasks());
        cell.add(() -> { });
        assertTrue(cell.hasTasks());
    }

    @Test void clearTasksEmptiesButKeepsCellReusable() {
        List<Integer> log = new ArrayList<>();
        Cell cell = new Cell(0, 0);
        cell.add(() -> log.add(1));
        cell.clearTasks();
        assertFalse(cell.hasTasks());
        cell.add(() -> log.add(2)); // reuse
        cell.run();
        assertEquals(List.of(2), log);
    }

    @Test void markActiveRecordsTick() {
        Cell cell = new Cell(0, 0);
        cell.markActive(42L);
        assertEquals(42L, cell.lastActiveTick());
    }

    @Test void cellIsRunnable() {
        int[] ran = {0};
        Runnable cell = makeCell(() -> ran[0]++);
        cell.run();
        assertEquals(1, ran[0]);
    }

    @Test void colorIsAlwaysZeroToThree() {
        for (int x = -10; x <= 10; x++)
            for (int z = -10; z <= 10; z++) {
                int c = new Cell(x, z).color();
                assertTrue(c >= 0 && c <= 3, "color out of range at " + x + "," + z);
            }
    }

    @Test void sameColorCellsAreNeverAdjacent() {
        int[][] neighbours = {{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) {
                int c = new Cell(x, z).color();
                for (int[] d : neighbours) {
                    int nc = new Cell(x + d[0], z + d[1]).color();
                    assertNotEquals(c, nc,
                        "cell (" + x + "," + z + ") shares color with neighbour");
                }
            }
    }

    private static Cell makeCell(Runnable task) {
        Cell cell = new Cell(0, 0);
        cell.add(task);
        return cell;
    }
}
