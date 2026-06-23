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
}
