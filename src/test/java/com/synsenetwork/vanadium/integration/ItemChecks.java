package com.synsenetwork.vanadium.integration;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.ItemLockAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

final class ItemChecks {
    static void run(ServerLevel level) throws Exception {
        Method merge = ItemEntity.class.getDeclaredMethod("tryToMerge", ItemEntity.class);
        merge.setAccessible(true);
        checkFailureRelease(level);
        for (int round = 0; round < 16; round++) {
            List<ItemEntity> items = new ArrayList<>();
            for (int i = 0; i < 32; i++) items.add(new ItemEntity(level, 0, 100, 0, new ItemStack(Items.STONE, 16)));
            List<SimpleContainer> containers = List.of(new SimpleContainer(27), new SimpleContainer(27));
            var pool = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().factory());
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> tasks = new ArrayList<>();
                for (int worker = 0; worker < 4; worker++) {
                    int index = worker;
                    tasks.add(pool.submit(() -> {
                        try {
                            check(start.await(10, TimeUnit.SECONDS), "item start timeout");
                            for (int i = 0; i < 256; i++) {
                                ItemEntity first = items.get(i % items.size());
                                ItemEntity second = items.get((i + 1) % items.size());
                                if (index == 0) merge.invoke(first, second);
                                else if (index == 1) merge.invoke(second, first);
                                else HopperBlockEntity.addItem(containers.get(index - 2), first);
                            }
                        } catch (Exception e) { throw new RuntimeException(e); }
                    }));
                }
                start.countDown();
                for (Future<?> task : tasks) task.get(15, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
                check(pool.awaitTermination(5, TimeUnit.SECONDS), "item workers failed to stop");
            }
            int total = items.stream().filter(item -> !item.isRemoved()).mapToInt(item -> item.getItem().getCount()).sum();
            for (SimpleContainer container : containers) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) total += container.getItem(slot).getCount();
            }
            check(total == 512, "concurrent merge/pickup changed total: " + total);
            for (ItemEntity item : items) check(item.getItem().getCount() <= 64, "merge overflowed a stack");
        }
        System.out.println("ITEM_MERGE_PICKUP_CHECKS_PASSED rounds=16");
    }

    private static void checkFailureRelease(ServerLevel level) throws Exception {
        ItemEntity first = new ItemEntity(level, 0, 100, 0, new ItemStack(Items.STONE, 16));
        ItemEntity second = new ItemEntity(level, 0, 100, 0, new ItemStack(Items.STONE, 16));
        Method wrapper = Arrays.stream(ItemEntity.class.getDeclaredMethods())
                .filter(method -> method.getName().endsWith("tryMerge") && method.getParameterCount() == 2)
                .findFirst().orElseThrow();
        wrapper.setAccessible(true);
        try {
            wrapper.invoke(first, second, (Operation<Void>) args -> { throw new IllegalStateException("fixture"); });
            throw new AssertionError("merge failure swallowed");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException, "wrong merge failure");
        }
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            executor.submit(() -> {
                for (ItemEntity item : List.of(first, second)) {
                    var lock = ((ItemLockAccess) item).vanadium$itemLock();
                    check(lock.tryLock(), "failed merge retained an item lock");
                    lock.unlock();
                }
            }).get(5, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
