package com.synsenetwork.vanadium.integration;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.EntityTickRules;
import com.synsenetwork.vanadium.tick.TickScheduler;
import com.synsenetwork.vanadium.tick.WorkerPool;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.BlockEventData;
import java.util.concurrent.Future;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/** Exercises transformed vanilla classes, including the actual scheduled-tick dispatch injection. */
final class TickSafetyChecks {
    static void run(ServerLevel level) throws Exception {
        checkScheduledTicks(level);
        checkPassengers(level);
        checkBlockEvents(level);
        checkRandomPositions(level);
        System.out.println("TICK_SAFETY_CHECKS_PASSED");
    }

    private static void checkScheduledTicks(ServerLevel level) throws Exception {
        Method dispatch = Arrays.stream(ServerLevel.class.getDeclaredMethods())
                .filter(method -> method.getName().endsWith("dispatchScheduledTicks"))
                .findFirst().orElseThrow();
        dispatch.setAccessible(true);
        TickScheduler previous = Vanadium.scheduler;
        boolean enabled = Vanadium.config.enabled;
        boolean parallel = Vanadium.config.parallelScheduledTicks;
        try (WorkerPool pool = new WorkerPool(2)) {
            Vanadium.scheduler = new TickScheduler(pool, 1);
            Vanadium.config.enabled = true;
            Vanadium.config.parallelScheduledTicks = true;
            LevelTicks<String> ticks = new LevelTicks<>(pos -> true);
            ticks.addContainer(new ChunkPos(0, 0), new LevelChunkTicks<>());
            ticks.addContainer(new ChunkPos(2, 0), new LevelChunkTicks<>());
            BlockPos first = new BlockPos(0, 100, 0);
            BlockPos next = first.offset(1, 0, 0);
            BlockPos cancelled = first.offset(2, 0, 0);
            ticks.schedule(new ScheduledTick<>("test", first, 0, 0));
            ticks.schedule(new ScheduledTick<>("test", next, 0, 1));
            ticks.schedule(new ScheduledTick<>("test", cancelled, 0, 2));
            List<BlockPos> ran = new ArrayList<>();
            dispatch.invoke(level, ticks, 0L, 100, (BiConsumer<BlockPos, String>) (pos, type) -> {
                ran.add(pos);
                check(!ticks.willTickThisTick(pos, type), "executing tick still pending");
                if (pos.equals(first)) {
                    check(ticks.willTickThisTick(next, type), "next tick disappeared before execution");
                    ticks.copyArea(new BoundingBox(0, 100, 0, 1, 100, 0), new Vec3i(32, 0, 0));
                    check(ticks.hasScheduledTick(first.offset(32, 0, 0), type), "copy lost executed tick");
                    check(ticks.hasScheduledTick(next.offset(32, 0, 0), type), "copy lost pending tick");
                    ticks.clearArea(new BoundingBox(cancelled));
                    check(!ticks.willTickThisTick(cancelled, type), "cleared tick still pending");
                }
            });
            check(ran.equals(List.of(first, next)), "callback order/cancellation failed: " + ran);
            ticks.clearArea(new BoundingBox(32, 0, 0, 47, 255, 15));

            CountDownLatch both = new CountDownLatch(2);
            for (int x : new int[]{0, 32}) ticks.schedule(new ScheduledTick<>("test", new BlockPos(x, 100, 0), 1, x));
            AtomicInteger calls = new AtomicInteger();
            dispatch.invoke(level, ticks, 1L, 100, (BiConsumer<BlockPos, String>) (pos, type) -> {
                both.countDown();
                try {
                    check(both.await(10, TimeUnit.SECONDS), "wave could not run two callbacks");
                } catch (InterruptedException e) { throw new AssertionError(e); }
                ticks.schedule(new ScheduledTick<>(type, pos, 2, pos.getX()));
                calls.incrementAndGet();
            });
            check(calls.get() == 2 && ticks.count() == 2, "follow-up scheduling lost ticks");
            try {
                dispatch.invoke(level, ticks, 2L, 100, (BiConsumer<BlockPos, String>) (pos, type) -> {
                    throw new IllegalStateException("expected fixture failure");
                });
                throw new AssertionError("callback failure swallowed");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof RuntimeException, "wrong callback failure");
            }
            check(!ticks.willTickThisTick(first, "test"), "failed wave leaked bookkeeping");
            Vanadium.config.parallelScheduledTicks = false;
            ticks.schedule(new ScheduledTick<>("test", first, 3, 0));
            dispatch.invoke(level, ticks, 3L, 100, (BiConsumer<BlockPos, String>) (pos, type) -> calls.incrementAndGet());
            check(calls.get() == 3, "serial fallback failed");
        } finally {
            Vanadium.scheduler = previous;
            Vanadium.config.enabled = enabled;
            Vanadium.config.parallelScheduledTicks = parallel;
        }
    }

    private static void checkPassengers(ServerLevel level) {
        var previous = Vanadium.config.serialEntityTypes;
        try {
            Pig root = new Pig(EntityTypes.PIG, level);
            Pig middle = new Pig(EntityTypes.PIG, level);
            ArmorStand passenger = new ArmorStand(level, 0, 100, 0);
            check(middle.startRiding(root, true, false), "fixture could not mount vehicle");
            check(passenger.startRiding(middle, true, false), "fixture could not mount nested passenger");
            Vanadium.config.serialEntityTypes = List.of();
            Vanadium.config.refreshSerialRules();
            check(!EntityTickRules.requiresSerial(root), "ordinary tree unexpectedly serial");
            Vanadium.config.serialEntityTypes = List.of("minecraft:armor_stand");
            Vanadium.config.refreshSerialRules();
            check(EntityTickRules.requiresSerial(root), "restricted passenger did not serialize root");
            middle.stopRiding();
            check(!EntityTickRules.requiresSerial(root), "dismount did not release root");
        } finally {
            Vanadium.config.serialEntityTypes = previous;
            Vanadium.config.refreshSerialRules();
        }
    }

    private static void checkBlockEvents(ServerLevel level) throws Exception {
        Field field = ServerLevel.class.getDeclaredField("blockEvents");
        field.setAccessible(true);
        Set<?> events = (Set<?>) field.get(level);
        BlockPos first = new BlockPos(0, 100, 0);
        BlockPos second = first.offset(1, 0, 0);
        level.blockEvent(first, Blocks.PISTON, 0, 0);
        level.blockEvent(second, Blocks.PISTON, 0, 0);
        level.blockEvent(first, Blocks.PISTON, 0, 0);
        check(events.size() == 2, "duplicate block event admitted");
        var iterator = events.iterator();
        check(iterator.next().equals(new BlockEventData(first, Blocks.PISTON, 0, 0)), "event order changed");
        level.clearBlockEvents(new BoundingBox(0, 100, 0, 1, 100, 0));
        check(events.isEmpty(), "event clear failed");
    }

    private static void checkRandomPositions(ServerLevel level) throws Exception {
        Field seed = Level.class.getDeclaredField("randValue");
        seed.setAccessible(true);
        seed.setInt(level, 12345);
        for (int i = 0; i < 16384; i++) level.getBlockRandomPos(0, 0, 0, 15);
        int expected = seed.getInt(level);
        seed.setInt(level, 12345);
        var pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 4; worker++) tasks.add(pool.submit(() -> {
                try { check(start.await(10, TimeUnit.SECONDS), "random start timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                for (int i = 0; i < 4096; i++) level.getBlockRandomPos(0, 0, 0, 15);
            }));
            start.countDown();
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
            check(seed.getInt(level) == expected, "concurrent random position seed lost updates");
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(5, TimeUnit.SECONDS), "random workers did not stop");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
