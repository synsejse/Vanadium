package com.synsenetwork.vanadium.tick;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CellTest {
    @Test void runsTasksInInsertionOrder() {
        List<Integer> log = new ArrayList<>();
        Cell cell = new Cell(new CellPos(0, 0));
        cell.add(() -> log.add(1));
        cell.add(() -> log.add(2));
        cell.add(() -> log.add(3));
        cell.run();
        assertEquals(List.of(1, 2, 3), log);
    }

    @Test void colorDelegatesToPos() {
        assertEquals(new CellPos(1, 0).color(), new Cell(new CellPos(1, 0)).color());
    }

    @Test void hasTasksReflectsContent() {
        Cell cell = new Cell(new CellPos(0, 0));
        assertFalse(cell.hasTasks());
        cell.add(() -> { });
        assertTrue(cell.hasTasks());
    }

    @Test void clearTasksEmptiesButKeepsCellReusable() {
        List<Integer> log = new ArrayList<>();
        Cell cell = new Cell(new CellPos(0, 0));
        cell.add(() -> log.add(1));
        cell.clearTasks();
        assertFalse(cell.hasTasks());
        cell.add(() -> log.add(2)); // reuse
        cell.run();
        assertEquals(List.of(2), log);
    }

    @Test void markActiveRecordsTick() {
        Cell cell = new Cell(new CellPos(0, 0));
        cell.markActive(42L);
        assertEquals(42L, cell.lastActiveTick());
    }

    @Test void cellIsRunnable() {
        int[] ran = {0};
        Runnable cell = makeCell(() -> ran[0]++);
        cell.run();
        assertEquals(1, ran[0]);
    }

    private static Cell makeCell(Runnable task) {
        Cell cell = new Cell(new CellPos(0, 0));
        cell.add(task);
        return cell;
    }
}
