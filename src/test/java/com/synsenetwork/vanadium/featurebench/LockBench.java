package com.synsenetwork.vanadium.featurebench;

import com.synsenetwork.vanadium.Vanadium;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater.NeighborUpdates;

final class LockBench {
    static void items(ServerLevel level, Measurements measurements) throws Exception {
        MethodHandle merge = MethodHandles.privateLookupIn(ItemEntity.class, MethodHandles.lookup())
                .findVirtual(ItemEntity.class, "tryToMerge", MethodType.methodType(void.class, ItemEntity.class));
        Field lock = ItemEntity.class.getDeclaredField("vanadium$lock");
        lock.setAccessible(true);
        for (int pairs : new int[]{1, 32}) {
            List<ItemEntity> items = new ArrayList<>();
            List<ReentrantLock> individual = new ArrayList<>();
            List<Runnable> jobs = new ArrayList<>();
            for (int i = 0; i < pairs; i++) {
                // Nonmatching stacks keep candidate checks repeatable without removals/reset work.
                ItemEntity a = new ItemEntity(level, i * 64, 100, 0, new ItemStack(Items.STONE, 16));
                ItemEntity b = new ItemEntity(level, i * 64, 100, 0, new ItemStack(Items.DIRT, 16));
                items.add(a);
                items.add(b);
                individual.add((ReentrantLock) lock.get(a));
                individual.add((ReentrantLock) lock.get(b));
                jobs.add(() -> {
                    try { for (int n = 0; n < 128; n++) merge.invokeExact(a, b); }
                    catch (Throwable e) { throw new IllegalStateException(e); }
                });
            }
            ReentrantLock global = new ReentrantLock();
            measurements.compare("item-locks-" + pairs + "-pairs", on -> {
                try {
                    // Same safe merge implementation, but all pairs contend on one lock in the off arm.
                    for (int i = 0; i < items.size(); i++) lock.set(items.get(i), on ? individual.get(i) : global);
                } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }, () -> Vanadium.scheduler.prepare(jobs));
        }
    }

    static void neighbors(ServerLevel level, Measurements measurements) throws Exception {
        MethodHandle add = MethodHandles.privateLookupIn(CollectingNeighborUpdater.class, MethodHandles.lookup())
                .findVirtual(CollectingNeighborUpdater.class, "addAndRun",
                        MethodType.methodType(void.class, BlockPos.class, NeighborUpdates.class));
        Field added = CollectingNeighborUpdater.class.getDeclaredField("addedThisLayer");
        added.setAccessible(true);
        for (int width : new int[]{6, 256}) {
            var updater = new CollectingNeighborUpdater(level, 10000);
            List<NeighborUpdates> array = new ArrayList<>();
            List<NeighborUpdates> copy = new CopyOnWriteArrayList<>();
            long[] completed = {0};
            NeighborUpdates leaf = new NeighborUpdates() {
                @Override public boolean runNext(Level world) { completed[0]++; return false; }
                @Override public void forEachUpdatedPos(Consumer<BlockPos> output) {}
            };
            NeighborUpdates root = new NeighborUpdates() {
                @Override public boolean runNext(Level world) {
                    try { for (int n = 0; n < width; n++) add.invokeExact(updater, BlockPos.ZERO, leaf); }
                    catch (Throwable e) { throw new IllegalStateException(e); }
                    return false;
                }
                @Override public void forEachUpdatedPos(Consumer<BlockPos> output) {}
            };
            measurements.compare("neighbor-queue-width-" + width, on -> {
                try { added.set(updater, on ? array : copy); }
                catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }, () -> {
                try { add.invokeExact(updater, BlockPos.ZERO, root); }
                catch (Throwable e) { throw new IllegalStateException(e); }
            });
            FeatureBench.sink = completed;
        }
    }
}
