package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BatchEnqueueTest {
    /** Item i belongs to chunk (i % 5 - 2, i % 3 - 1); every seventh is serial, every eleventh skipped. */
    private record Item(int index) {}

    private static final class Mapper implements BatchEnqueue.Mapper<Item> {
        final Map<Long, List<Integer>> ran = new ConcurrentHashMap<>();

        @Override public long key(Item item) {
            if (item.index() % 11 == 0) return BatchEnqueue.SKIP;
            if (item.index() % 7 == 0) return BatchEnqueue.SERIAL;
            return ChunkPos.pack(item.index() % 5 - 2, item.index() % 3 - 1);
        }

        @Override public Runnable task(Item item) {
            long key = key(item);
            return () -> ran.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(item.index());
        }
    }

    @Test void keepsRecordingOrderPerChunkAndRunsSerialItemsInline() {
        for (int size : new int[]{10, 3000}) { // inline and parallel mapping paths
            WorkerPool pool = new WorkerPool(4);
            TickScheduler scheduler = new TickScheduler(pool, 1);
            Mapper mapper = new Mapper();
            BatchEnqueue<Item> batch = new BatchEnqueue<>(mapper);
            List<Integer> serial = new ArrayList<>();
            for (int round = 0; round < 2; round++) {
                mapper.ran.clear();
                serial.clear();
                scheduler.begin(Stage.ENTITY);
                for (int i = 0; i < size; i++) batch.add(new Item(i));
                batch.dispatch(scheduler, Stage.ENTITY, item -> serial.add(item.index()));
                scheduler.run(Stage.ENTITY);

                int expectedSerial = 0, expectedQueued = 0;
                for (int i = 0; i < size; i++) {
                    if (i % 11 == 0) continue;
                    if (i % 7 == 0) expectedSerial++; else expectedQueued++;
                }
                assertEquals(expectedSerial, serial.size());
                for (int i = 1; i < serial.size(); i++) assertTrue(serial.get(i) > serial.get(i - 1));
                int queued = 0;
                for (List<Integer> values : mapper.ran.values()) {
                    queued += values.size();
                    for (int i = 1; i < values.size(); i++) assertTrue(values.get(i) > values.get(i - 1), "order changed");
                }
                assertEquals(expectedQueued, queued, "batch must be cleared and fully dispatched each round");
            }
            pool.close();
        }
    }
}
